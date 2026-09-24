#!/usr/bin/env bash
# ============================================================================
# deploy.sh —— emotion-server + emotion-web 一键部署到阿里云
#             （Alibaba Cloud Linux，原生 MySQL 8.0，密码 SSH）
#
# 适用环境
#   · 服务器：Alibaba Cloud Linux 3（yum/dnf/conf.d，非 Ubuntu/Debian）
#   · 数据库：原生安装的 MySQL 8.0（非 Docker mysql:5.7）
#   · 登录：  root 密码 SSH（非密钥免密）——用本目录 scripts/ 下的 expect 驱动
#
# 与 scripts/deploy/（Ubuntu 方案）并存，按服务器系统二选一。
#
# 本机前置
#   1) 装了 expect（macOS/Linux 一般自带；没有则 brew install expect）
#   2) 装好 mvn 和一个可用 node（本机 node 若被 TRAE 自带坏二进制顶到 PATH 最前，
#      脚本会自动兜底探测 $HOME/.local/node 等真实 node）
#   3) 已有 deploy/init_full.sql（全量数据库初始化脚本，自带 CREATE DATABASE）
#
# 环境变量
#   ALINUX_HOST        服务器公网 IP           默认 47.117.110.143
#   ALINUX_SSH_USER    登录用户名              默认 root
#   ALINUX_SSH_PASS    登录密码                【必填】
#   ALINUX_MYSQL_PASS  服务器 MySQL root 密码   可选；为空则尝试从服务器 /root/mysql_pass.txt 读
#
# 用法
#   ALINUX_SSH_PASS='你的密码' bash deploy/alinux/deploy.sh
#
# 流程（幂等，可重复执行）
#   ① 本机构建后端 fat jar + 前端 dist
#   ② 上传一台命令行手动 mkdir + 上传 jar / dist / 配置文件
#   ③ 服务器初始化 MySQL 数据库并导入 init_full.sql
#   ④ 写生产 application-local.yml、装 systemd 服务并启动后端(8080)
#   ⑤ 部署前端静态文件 + 配置 Nginx(80)，/api 反代 8080
#   ⑥ 健康检查（首页 200 + 登录接口探活）
# ============================================================================
set -euo pipefail

# ---------------- 配置区（环境变量覆盖） ----------------
SERVER_IP="${ALINUX_HOST:-47.117.110.143}"
SSH_USER="${ALINUX_SSH_USER:-root}"
SSH_PASS="${ALINUX_SSH_PASS:?必须设置 ALINUX_SSH_PASS（服务器 SSH 登录密码）}"
MYSQL_PASS="${ALINUX_MYSQL_PASS:-}"

REMOTE_APP=/opt/emotion/app      # 后端 jar + application-local.yml 目录
REMOTE_WEB=/var/www/emotion-web  # 前端静态页根目录

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$BASE_DIR/scripts"
CONFIG_DIR="$BASE_DIR/config"
PROJECT_ROOT="$(cd "$BASE_DIR/../.." && pwd)"    # emotion 项目根
INIT_SQL="$BASE_DIR/../init_full.sql"            # deploy/init_full.sql

TMP_DIR="$(mktemp -d)"                            # 本次部署临时工作目录
LOG_SSH="$TMP_DIR/remote.log"                     # 远端命令输出留痕

# ---------------- 工具函数 ----------------
log()  { echo "==> $*"; }
warn() { echo "[WARN] $*"; }
die()  { echo "[ERROR] $*" >&2; exit 1; }

# 远端执行命令：stdout 写留痕文件（verbose）
remote_exec() {
  expect "$SCRIPTS_DIR/ssh_run.exp" "$SERVER_IP" "$SSH_USER" "$SSH_PASS" "$1" "$LOG_SSH"
}

# 远端执行命令并**返回其 stdout**（capture），供变量取值用
remote_capture() {
  expect "$SCRIPTS_DIR/ssh_run.exp" "$SERVER_IP" "$SSH_USER" "$SSH_PASS" "$1" "" 2>/dev/null
}

# 上传本地→远端（scp 不建目录，调用前请确保远端目录已 mkdir）
upload() {
  expect "$SCRIPTS_DIR/scp_push.exp" "$SERVER_IP" "$SSH_USER" "$SSH_PASS" "$1" "$2"
}

# 找一个真正能运行的真实 node（本机 PATH 最前的可能是 TRAE 自带坏二进制）
find_real_node() {
  # 1) 优先用 PATH 里能真正运行的 node：CI(GitHub Actions setup-node / 本机常规安装)都靠它。
  local n
  if n="$(command -v node 2>/dev/null)" && [ -n "$n" ]; then
    if "$n" -v >/dev/null 2>&1; then
      echo "$n"
      return 0
    fi
  fi
  # 2) 兜底：本机 node 若被 TRAE 自带坏二进制顶到 PATH 最前（-v 会失败），
  #    就走下面的白名单找到真实 node；CI 走到这说明 PATH 里没有可用 node，多半会 die。
  for cand in "$HOME/.local/node/bin/node" /usr/local/bin/node /opt/homebrew/bin/node; do
    if [ -x "$cand" ] && "$cand" -v >/dev/null 2>&1; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}

# ---------------- ① 本机构建 ----------------
build() {
  log "① [1/6] 构建后端 fat jar"
  ( cd "$PROJECT_ROOT/emotion-server" && mvn -q -DskipTests clean package )
  local jar
  jar="$(ls "$PROJECT_ROOT"/emotion-server/target/emotion-server-*.jar 2>/dev/null | grep -v '\.original$' | head -n1)"
  [ -n "$jar" ] || die "找不到 emotion-server fat jar，请检查 mvn 是否成功"
  cp "$jar" "$TMP_DIR/emotion-server.jar"

  log "① [1/6] 构建前端 dist"
  local node
  node="$(find_real_node)" || die "本机找不到可用 node"
  log "      使用 node: $node (v$("$node" -v | sed s/^v//))"
  ( cd "$PROJECT_ROOT/emotion-web" && "$node" node_modules/vite/bin/vite.js build )
  cp -r "$PROJECT_ROOT/emotion-web/dist" "$TMP_DIR/web"
}

# ---------------- ③ 服务器 MySQL 建库 + 导入 ----------------
server_mysql_init() {
  log "③ [3/6] 初始化 MySQL 并导入 deploy/init_full.sql"

  # 初次环境已经按约定把 MySQL root 密码存到 /root/mysql_pass.txt（MYSQL_ROOT_PASS=xxx）
  if [ -z "$MYSQL_PASS" ]; then
    local got
    got="$(remote_capture "grep -o 'PASS=[a-f0-9]*' /root/mysql_pass.txt 2>/dev/null | head -n1")"
    MYSQL_PASS="${got#PASS=}"
  fi
  [ -n "$MYSQL_PASS" ] || die "无法取得服务器 MySQL root 密码，请用 ALINUX_MYSQL_PASS 传入"

  upload "$INIT_SQL" "/tmp/init_full.sql"
  remote_exec "mysql -uroot -p\"$MYSQL_PASS\" < /tmp/init_full.sql && echo IMPORT_OK"
  remote_exec "mysql -uroot -p\"$MYSQL_PASS\" -N -e 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"emotion_dashboard\";'"
}

# ---------------- ④ 后端：传 jar + 生成生产配置 + 起 systemd ----------------
server_deploy_backend() {
  log "④ [4/6] 部署后端 jar + 生产配置 + systemd 服务"

  upload "$TMP_DIR/emotion-server.jar" "$REMOTE_APP/emotion-server.jar"

  # 用模板 + 真实值生成生产 application-local.yml（覆盖 DB 密码 + 新 JWT 密钥）
  local jwt
  jwt="$(openssl rand -hex 32)"
  sed -e "s/__MYSQL_PASSWORD__/$MYSQL_PASS/g" \
      -e "s/__JWT_SECRET__/$jwt/g" \
      "$CONFIG_DIR/application-local.yml.template" > "$TMP_DIR/application-local.yml"
  upload "$TMP_DIR/application-local.yml" "$REMOTE_APP/application-local.yml"

  upload "$CONFIG_DIR/emotion-server.service" "/tmp/emotion-server.service"
  remote_exec "cp /tmp/emotion-server.service /etc/systemd/system/emotion-server.service &&
               systemctl daemon-reload &&
               systemctl enable emotion-server &&
               systemctl restart emotion-server &&
               echo BACKEND_STARTED"
}

# ---------------- ⑤ 前端：传 dist + 配 Nginx ----------------
server_deploy_frontend() {
  log "⑤ [5/6] 部署前端静态文件 + Nginx"

  ( cd "$TMP_DIR" && tar -czf dist.tgz -C web . )
  upload "$TMP_DIR/dist.tgz" "/tmp/dist.tgz"
  remote_exec "mkdir -p $REMOTE_WEB &&
               cd $REMOTE_WEB &&
               rm -rf * &&
               tar -xzf /tmp/dist.tgz &&
               find . -name '._*' -delete &&
               echo WEB_UNPACKED"

  upload "$CONFIG_DIR/emotion-web.conf" "/tmp/emotion-web.conf"
  remote_exec "cp /tmp/emotion-web.conf /etc/nginx/conf.d/emotion-web.conf &&
               nginx -t &&
               nginx -s reload &&
               echo NGINX_RELOADED"
}

# ---------------- ⑥ 健康检查 ----------------
health_check() {
  log "⑥ [6/6] 健康检查"
  local code
  for i in $(seq 1 20); do                                     # 最多等 60s
    code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://$SERVER_IP/" || true)"
    [ "$code" = "200" ] && { log "   前端页面 OK (HTTP 200)"; break; }
    sleep 3
  done
  [ "$code" = "200" ] || warn "   前端首页未就绪，最后 HTTP=$code，请人工核查"

  local api
  api="$(curl -s -m 5 -X POST "http://$SERVER_IP/api/auth/login" \
          -H 'Content-Type: application/json' \
          -d '{"username":"__probe__","password":"__probe__"}' || true)"
  echo "   后端 /api 登录探活响应（无效凭据应返回业务错误码）:"
  echo "   $api"
  case "$api" in
    *'"code":400'*) log "   后端正常（返回 400 业务错误，符合预期）" ;;
    *) warn "   后端响应异常，请查看日志：ssh ... journalctl -u emotion-server -n 100" ;;
  esac
}

# ---------------- 主流程 ----------------
main() {
  log "开始部署 emotion → http://$SERVER_IP/ ($SSH_USER)"
  command -v expect >/dev/null || die "本机缺 expect，请安装（macOS: brew install expect）"
  [ -f "$INIT_SQL" ] || die "缺少数据库初始化脚本：$INIT_SQL"

  build                 # ① 构建（jar + dist 都进 $TMP_DIR）

  log "② [2/6] 准备远端目录"
  remote_exec "mkdir -p $REMOTE_APP $REMOTE_WEB"

  server_mysql_init     # ③ 建库导入
  server_deploy_backend # ④ 后端 + systemd
  server_deploy_frontend# ⑤ 前端 + nginx
  health_check          # ⑥ 健康检查

  log "部署完成 ✔  浏览器打开 http://$SERVER_IP/"
  log "远端后端日志: journalctl -u emotion-server -f（生产配置见 $REMOTE_APP/application-local.yml）"
}

main