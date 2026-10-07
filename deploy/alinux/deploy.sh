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
#   SKIP_DB_INIT       可选；=1 跳过③整库导入，只发代码。init_full.sql 对每张表都
#                      DROP TABLE IF EXISTS，跑了就把线上数据退回那份 dump 的快照时刻，
#                      纯代码/无表结构变更的发版应该设这个（④仍会解析 MySQL 密码）
#   ROTATE_JWT_SECRET  可选；=1 强制换一把新的 JWT 签名密钥，**会让所有在线会话立刻失效**
#                      （需要重新登录）。默认不换——密钥存在服务器 $REMOTE_APP/jwt.secret 里
#                      长期复用，所以发版不再等于踢人。只有怀疑密钥泄漏、或就是要清场时才用它。
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
# 单独拆出来：④ 生成 application-local.yml 也要用这个密码，跳过导入时同样得先拿到
resolve_mysql_pass() {
  # 初次环境已经按约定把 MySQL root 密码存到 /root/mysql_pass.txt（MYSQL_ROOT_PASS=xxx）
  if [ -z "$MYSQL_PASS" ]; then
    local got
    got="$(remote_capture "grep -o 'MYSQL_ROOT_PASS=.*' /root/mysql_pass.txt 2>/dev/null | head -n1")"
    # expect 的 stdout 带 \r 等噪声，不能用 ${got#PASS=} 剥前缀（会连噪声一起当密码）
    MYSQL_PASS="$(printf '%s\n' "$got" | tr -d '\r' \
                  | grep -o 'MYSQL_ROOT_PASS=.*' | head -n1 \
                  | cut -d= -f2- | sed 's/[[:space:]]*$//')"
  fi
  [ -n "$MYSQL_PASS" ] || die "无法取得服务器 MySQL root 密码，请用 ALINUX_MYSQL_PASS 传入"
}

# ---------------- JWT 签名密钥：一次生成、长期复用 ----------------
# 以前这里是每次发版 openssl rand 一把新的，于是「发版」在效果上等价于「全员踢下线」。
# 但登录状态本来就有服务端权威源——t_user.token_version（见 SingleSessionService），
# 换签名钥匙对安全性没有额外贡献，只是让旧 token 验不过签名而已。所以改成持久化复用。
# 密钥放 $REMOTE_APP/jwt.secret（600，只在服务器上，不进 git）。
JWT_SECRET=""
JWT_SECRET_FILE="$REMOTE_APP/jwt.secret"

resolve_jwt_secret() {
  if [ "${ROTATE_JWT_SECRET:-0}" = "1" ]; then
    JWT_SECRET="$(openssl rand -hex 32)"
    log "      JWT 密钥：ROTATE_JWT_SECRET=1，强制轮换（本次之后所有在线会话都要重新登录）"
    save_jwt_secret          # 不写回去的话，下次发版读到的还是旧那把，轮换会被悄悄撤销
    return 0
  fi
  local got adopted
  # 正常路径：读服务器上那一份。expect 落的是 pty 输出，带 \r 和提示符噪声，
  # 所以只认「整行正好 64 位小写 hex」这一个形状（-x 是全行匹配），凑不上就当没有——
  # 宁可新生成，也不能把一个被噪声截过的字符串当密钥用（那等于悄悄换了钥匙、把人踢了还没线索）。
  got="$(remote_capture "cat $JWT_SECRET_FILE 2>/dev/null" || true)"
  JWT_SECRET="$(printf '%s\n' "$got" | tr -d '\r' | grep -oxE '[0-9a-f]{64}' | head -n1 || true)"
  if [ -n "$JWT_SECRET" ]; then
    log "      JWT 密钥：复用 $JWT_SECRET_FILE（发版不再踢人）"
    return 0
  fi
  # 第一次换成这套机制时服务器上还没有那份文件，但现网 application-local.yml 里就躺着**正在用的**那把。
  # 先把它接管过来，这样第一次跑新脚本也不会把当时在线的人踢掉。
  # 必须按行锚定 `secret:` 取值：同一个文件里 datasource 的 password 行排在它**前面**，
  # 一旦哪天口令也是 64 位 hex，整文件 grep 会先命中口令——那等于悄悄把签名钥匙换成数据库口令，
  # 既踢了所有人，又把口令塞进签名路径。
  got="$(remote_capture "grep -E '^[[:space:]]*secret:[[:space:]]*[0-9a-f]{64}[[:space:]]*$' $REMOTE_APP/application-local.yml 2>/dev/null | head -n1" || true)"
  adopted="$(printf '%s\n' "$got" | tr -d '\r' \
              | sed -nE 's/^[[:space:]]*secret:[[:space:]]*([0-9a-f]{64})[[:space:]]*$/\1/p' \
              | grep -oxE '[0-9a-f]{64}' | head -n1 || true)"
  if [ -n "$adopted" ]; then
    JWT_SECRET="$adopted"
    log "      JWT 密钥：服务器上还没有 $JWT_SECRET_FILE，从现网配置接管（在线会话不受影响）"
  else
    JWT_SECRET="$(openssl rand -hex 32)"
    log "      JWT 密钥：没取到可复用的密钥，新生成一把（首次环境，或上次没落盘）"
  fi
  save_jwt_secret
}

# 把当前 JWT_SECRET 写回服务器，下次发版就靠它续命。
save_jwt_secret() {
  # 用 remote_capture 而不是 remote_exec：后者会把远端输出写进 $LOG_SSH 留痕文件，
  # 密钥不该跟着落一份本地日志。值本身只含 hex，单引号包起来没有注入面。
  remote_capture "umask 077 && printf '%s' '$JWT_SECRET' > $JWT_SECRET_FILE && chmod 600 $JWT_SECRET_FILE && echo JWT_SECRET_SAVED" 2>/dev/null \
    | grep -q JWT_SECRET_SAVED \
    || warn "      JWT 密钥没能写入 $JWT_SECRET_FILE：下次发版会重新生成，届时会踢人"
}

# 用模板 + 真实值生成生产 application-local.yml（只写 $TMP_DIR，上传由调用方做）。
render_local_yml() {
  # 替换只认那两个「值行」，不做全文 g 替换。模板头部的说明注释里就写着占位符的名字，
  # 全文替换会顺手把真值也糊进注释——上一版就是这么把 JWT 密钥复制成了两份
  # （现网 application-local.yml 里就躺着一把真密钥，而那个文件当时是 644）。
  # 分隔符仍用 /：所以 MYSQL_PASS 不能含 / 与 &（README 已写死这条约束）。
  # 渲染在 mac 上跑（BSD sed）：BSD sed 遇到第一个脚本之后就不再接参数，
  # 所以两条表达式必须挂在同一个 -E 下面写成 -e ... -e ...，否则第二条会被当成文件名。
  sed -E -e "s/^([[:space:]]*password:[[:space:]]*)__MYSQL_PASSWORD__[[:space:]]*$/\1$MYSQL_PASS/" \
         -e "s/^([[:space:]]*secret:[[:space:]]*)__JWT_SECRET__[[:space:]]*$/\1$JWT_SECRET/" \
      "$CONFIG_DIR/application-local.yml.template" > "$TMP_DIR/application-local.yml"

  # 真值在这份文件里各只允许出现一次。多出来的那一份一定在注释里，
  # 而它马上就会被上传成一个全世界可读的文件——宁可发版在这里停住。
  local pw_n jwt_n
  pw_n="$(grep -Fc -- "$MYSQL_PASS" "$TMP_DIR/application-local.yml" || true)"
  jwt_n="$(grep -Fc -- "$JWT_SECRET" "$TMP_DIR/application-local.yml" || true)"
  if [ "$pw_n" != "1" ] || [ "$jwt_n" != "1" ]; then
    die "生成的 application-local.yml 里 MySQL 口令出现 $pw_n 次、JWT 密钥 $jwt_n 次（都应正好 1 次），已中止，不上传这份配置"
  fi
}

server_mysql_init() {
  log "③ [3/6] 初始化 MySQL 并导入 deploy/init_full.sql"

  resolve_mysql_pass
  upload "$INIT_SQL" "/tmp/init_full.sql"
  remote_exec "mysql -uroot -p\"$MYSQL_PASS\" < /tmp/init_full.sql && echo IMPORT_OK"
  remote_exec "mysql -uroot -p\"$MYSQL_PASS\" -N -e 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"emotion_dashboard\";'"
}

# ---------------- ④ 后端：传 jar + 生成生产配置 + 起 systemd ----------------
server_deploy_backend() {
  log "④ [4/6] 部署后端 jar + 生产配置 + systemd 服务"

  # 先传 .new 再换名：就地覆盖会把运行中进程仍在加载的那个 jar 截断重写，
  # mv 是换指针，旧进程继续用原 inode，重启后才切到新包
  upload "$TMP_DIR/emotion-server.jar" "$REMOTE_APP/emotion-server.jar.new"

  # 用模板 + 真实值生成生产 application-local.yml。
  # MySQL 密码每次现取；JWT 密钥复用服务器上的 $JWT_SECRET_FILE（见 resolve_jwt_secret），
  # 所以发版不再让在线会话失效——想主动清场才 ROTATE_JWT_SECRET=1。
  resolve_jwt_secret
  render_local_yml
  upload "$TMP_DIR/application-local.yml" "$REMOTE_APP/application-local.yml"

  upload "$CONFIG_DIR/emotion-server.service" "/tmp/emotion-server.service"
  # chmod 600：application-local.yml 是 scp 落下来的，默认 644＝本机任何账号都能读走 DB 口令和签名密钥。
  # 服务跑在 root 下（unit 没有 User=），收紧到 600 不影响它读自己的配置。
  remote_exec "chmod 600 $REMOTE_APP/application-local.yml &&
               mv -f $REMOTE_APP/emotion-server.jar.new $REMOTE_APP/emotion-server.jar &&
               cp /tmp/emotion-server.service /etc/systemd/system/emotion-server.service &&
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
  local code api i
  for i in $(seq 1 20); do                                     # 最多等 60s
    code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://$SERVER_IP/" || true)"
    [ "$code" = "200" ] && { log "   前端页面 OK (HTTP 200)"; break; }
    sleep 3
  done
  [ "$code" = "200" ] || warn "   前端首页未就绪，最后 HTTP=$code，请人工核查"

  # 后端要单独轮：前端是 nginx 直接给静态文件，秒回 200，跟后端起没起来毫无关系，
  # 所以上面那个循环几乎立刻 break；而 Spring Boot 实测要 22~27 s（Flyway 校验 + Quartz 装载）。
  # 原来这里只探一次，每次都落在启动窗口里拿到 nginx 的 502，于是「后端响应异常」成了每次发版的固定误报。
  api=""
  for i in $(seq 1 40); do                                     # 最多等 120s
    api="$(curl -s -m 5 -X POST "http://$SERVER_IP/api/auth/login" \
            -H 'Content-Type: application/json' \
            -d '{"username":"__probe__","password":"__probe__"}' || true)"
    case "$api" in
      *'"code":400'*) break ;;                                 # 拿到业务错误码就说明后端真的起来了
    esac
    sleep 3
  done
  echo "   后端 /api 登录探活响应（无效凭据应返回业务错误码，等待约 $((i * 3)) s）:"
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

  build                 # ① 构建（jar + dist 都进 $TMP_DIR）

  log "② [2/6] 准备远端目录"
  remote_exec "mkdir -p $REMOTE_APP $REMOTE_WEB"

  if [ "${SKIP_DB_INIT:-0}" = "1" ]; then
    log "③ [3/6] 跳过整库导入（SKIP_DB_INIT=1），服务器沿用现有数据"
    resolve_mysql_pass      # ④ 写 application-local.yml 仍需要
  else
    [ -f "$INIT_SQL" ] || die "缺少数据库初始化脚本：$INIT_SQL"
    server_mysql_init
  fi
  server_deploy_backend # ④ 后端 + systemd
  server_deploy_frontend # ⑤ 前端 + nginx
  health_check          # ⑥ 健康检查

  log "部署完成 ✔  浏览器打开 http://$SERVER_IP/"
  log "远端后端日志: journalctl -u emotion-server -f（生产配置见 $REMOTE_APP/application-local.yml）"
}

main