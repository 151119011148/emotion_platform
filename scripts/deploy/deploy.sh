#!/usr/bin/env bash
# 把 dist-deploy/ 传到阿里云并切换版本（保留最近 5 个版本，支持一键回滚）
#
# 前置：
#   1) 服务器上跑过一次 scripts/deploy/server-init.sh
#   2) 本机配好 SSH 免密（ssh user@host 能直接进）
#
# 用法：
#   DEPLOY_HOST=user@1.2.3.4 bash scripts/deploy/deploy.sh            # 部署
#   DEPLOY_HOST=user@1.2.3.4 bash scripts/deploy/deploy.sh rollback   # 回滚到上一版
#   DEPLOY_HOST=user@1.2.3.4 DEPLOY_SRC=./dist-deploy bash scripts/deploy/deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${DEPLOY_SRC:-$ROOT/dist-deploy}"
HOST="${DEPLOY_HOST:?必须设置 DEPLOY_HOST，例如 DEPLOY_HOST=root@1.2.3.4}"
REMOTE_APP=/opt/emotion
RELEASES="$REMOTE_APP/releases"
KEEP=5
ACTION="${1:-deploy}"

log() { echo "==> $*"; }
ssh_do() { ssh -o StrictHostKeyChecking=accept-new "$HOST" "$@"; }

if [ "$ACTION" = "rollback" ]; then
  log "回滚到上一版"
  ssh_do "set -e
    prev=\$(ls -1 $RELEASES | sort | tail -n2 | head -n1)
    if [ -z \"\$prev\" ]; then echo '[ERROR] 没有可回滚的版本' >&2; exit 1; fi
    echo \"回滚到 \$prev\"
    ln -sfn $RELEASES/\$prev $REMOTE_APP/current
    sudo systemctl restart emotion-server"
  log "回滚完成"
  exit 0
fi

[ -f "$SRC/emotion-server.jar" ] || { echo "[ERROR] $SRC/emotion-server.jar 不存在，先跑 build.sh" >&2; exit 1; }
[ -d "$SRC/web" ] || { echo "[ERROR] $SRC/web 不存在，先跑 build.sh" >&2; exit 1; }

TS="$(date +%Y%m%d-%H%M%S)"
TARBALL="emotion-$TS.tar.gz"

log "打包 $SRC -> /tmp/$TARBALL"
tar -czf "/tmp/$TARBALL" -C "$SRC" emotion-server.jar web

log "上传"
scp -o StrictHostKeyChecking=accept-new "/tmp/$TARBALL" "$HOST:/tmp/$TARBALL"

log "释放到 $RELEASES/$TS"
ssh_do "set -e
  sudo mkdir -p $RELEASES/$TS
  sudo tar -xzf /tmp/$TARBALL -C $RELEASES/$TS
  sudo rm -f /tmp/$TARBALL"

PREV="$(ssh_do "test -L $REMOTE_APP/current && readlink $REMOTE_APP/current || echo ''" || true)"

log "切换 current -> $TS 并重启"
if ! ssh_do "set -e
  ln -sfn $RELEASES/$TS $REMOTE_APP/current
  sudo systemctl restart emotion-server"; then
  echo "[ERROR] 重启失败" >&2
  [ -n "$PREV" ] && ssh_do "ln -sfn $PREV $REMOTE_APP/current && sudo systemctl restart emotion-server"
  exit 1
fi

log "健康检查（最多等 90s）"
if ! ssh_do "for i in \$(seq 1 30); do
    code=\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:8080/api/auth/login || true)
    if [ \"\$code\" != '000' ]; then echo \"已就绪（HTTP \$code）\"; exit 0; fi
    sleep 3
  done
  echo '[ERROR] 90s 内没起来，看 journalctl -u emotion-server -n 100' >&2; exit 1"; then
  if [ -n "$PREV" ]; then
    echo "[WARN] 健康检查没过，回滚到 $PREV"
    ssh_do "ln -sfn $PREV $REMOTE_APP/current && sudo systemctl restart emotion-server" || true
  fi
  exit 1
fi

log "清理旧版本（保留最近 $KEEP 个）"
ssh_do "ls -1 $RELEASES | sort | head -n -$KEEP | while read -r d; do sudo rm -rf $RELEASES/\$d; done"

log "部署完成：$TS"
