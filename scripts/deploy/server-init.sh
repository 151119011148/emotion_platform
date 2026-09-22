#!/usr/bin/env bash
# 阿里云 ECS 一次性初始化（Ubuntu 20.04 / 22.04，root 跑）
#
#   sudo bash scripts/deploy/server-init.sh
#
# 幂等，可以重复跑。它只负责「把机器变成能跑 emotion 的样子」：
#   装 JDK8 + nginx + rsync → 起 MySQL 5.7 → 建目录/用户 → 装 systemd 与 nginx 配置 → 生成 env 模板
# 之后每次发版只跑 deploy.sh，不要再跑这个。
#
# 安全组提醒：22 / 80 / 443 对外开放，3306 和 8080 都只监听 127.0.0.1，不要加进安全组。
set -euo pipefail

APP_DIR=/opt/emotion
CONF_DIR=/etc/emotion
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(openssl rand -hex 12)}"

log() { echo "==> $*"; }

log "时区与基础包"
timedatectl set-timezone Asia/Shanghai
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-8-jdk-headless nginx rsync curl docker.io >/dev/null
systemctl enable --now docker

log "MySQL 5.7（容器跑，端口只绑 127.0.0.1）"
# 为什么是 5.7 不是 8.0：Flyway 钉在 6.5.7（最后一个支持 MySQL 5.7 的社区版），
# 本机库就是 5.7.29，云端保持同版本最省事。要用阿里云 RDS 的话选 5.7 版，
# 然后把下面这段换成改 MYSQL_IP 指到 RDS 内网地址即可。
if ! docker ps -a --format '{{.Names}}' | grep -qx 'emotion-mysql'; then
  docker run -d --name emotion-mysql --restart=always \
    -p 127.0.0.1:3306:3306 \
    -v /var/lib/emotion/mysql:/var/lib/mysql \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_ROOT_PASSWORD" \
    mysql:5.7 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
fi
echo "等 MySQL 就绪（首次要拉镜像，可能一两分钟）"
for i in $(seq 1 60); do
  if docker exec emotion-mysql mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent 2>/dev/null; then break; fi
  sleep 3
done
docker exec emotion-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
  "CREATE DATABASE IF NOT EXISTS emotion_dashboard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

log "目录与运行用户"
id -u emotion >/dev/null 2>&1 || useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin emotion
mkdir -p "$APP_DIR/releases" "$APP_DIR/current" /var/log/emotion "$CONF_DIR"
chown -R emotion:emotion "$APP_DIR" /var/log/emotion
mkdir -p "$CONF_DIR"

log "环境变量文件（只生成一次，已存在就不动，避免覆盖真口令）"
if [ ! -f "$CONF_DIR/emotion.env" ]; then
  cat > "$CONF_DIR/emotion.env" <<EOF
# 改完记得 chmod 600 保持住（下面已经设了）
MYSQL_IP=127.0.0.1
MYSQL_USERNAME=root
MYSQL_PASSWORD=$MYSQL_ROOT_PASSWORD
JWT_SECRET=$(openssl rand -hex 32)
LOG_DIR=/var/log/emotion
TZ=Asia/Shanghai
# nginx 同域部署不用配；直连 8080 或前端单独起端口时才填真实访问地址
EMOTION_CORS_ORIGINS=http://127.0.0.1:8080
EOF
  chmod 600 "$CONF_DIR/emotion.env"
  chown root:emotion "$CONF_DIR/emotion.env"
  log "已生成 $CONF_DIR/emotion.env —— 里面的 JWT_SECRET 要自己留一份备份"
else
  log "$CONF_DIR/emotion.env 已存在，跳过"
fi

log "systemd 服务"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/emotion-server.service" /etc/systemd/system/emotion-server.service
systemctl daemon-reload
systemctl enable emotion-server

log "nginx 站点"
cp "$SCRIPT_DIR/nginx-emotion.conf" /etc/nginx/sites-available/emotion
ln -sf /etc/nginx/sites-available/emotion /etc/nginx/sites-enabled/emotion
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx

log "部署用户免密重启（deploy.sh 要用 sudo systemctl restart）"
DEPLOY_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"
if [ "$DEPLOY_USER" != "root" ]; then
  cat > "/etc/sudoers.d/emotion-deploy-$DEPLOY_USER" <<EOF
$DEPLOY_USER ALL=(ALL) NOPASSWD: /bin/systemctl restart emotion-server, /bin/systemctl start emotion-server, /bin/systemctl stop emotion-server, /bin/systemctl status emotion-server
EOF
  chmod 440 "/etc/sudoers.d/emotion-deploy-$DEPLOY_USER"
fi

cat <<EOF

==> 初始化完成。MySQL root 口令写在 $CONF_DIR/emotion.env（600）。

接下来：
  1) 本机跑   bash scripts/deploy/build.sh
  2) 发版跑   DEPLOY_HOST=$DEPLOY_USER@$(curl -s --max-time 3 ifconfig.me || echo '<公网IP>') bash scripts/deploy/deploy.sh
  3) 看日志   sudo journalctl -u emotion-server -f
  4) 首次启动后端会自动跑 Flyway 建表，看到 "Started EmotionApplication" 就成功了
EOF
