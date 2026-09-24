# emotion 部署 —— Alibaba Cloud Linux 版

把 emotion-server（Spring Boot, 8080）和 emotion-web（Vue/Vite, 由 Nginx 托管 :80）一键部署到
**Alibaba Cloud Linux 3** 服务器（原生 MySQL 8.0，走 root 密码 SSH）。

> 另有 **Ubuntu 方案** 在 `scripts/deploy/`（Docker MySQL 5.7、SSH 免密、版本回滚、systemd env 文件）。
> 两套按服务器操作系统选其一，互不影响。

## 目录结构

```
deploy/alinux/
├── deploy.sh            # 主部署脚本：构建→上传→建库→起后端→配前端→健康检查（幂等）
├── scripts/
│   ├── ssh_run.exp      # expect 辅助：密码 SSH 在远端执行一条命令（支持捕获返回值）
│   └── scp_push.exp     # expect 辅助：密码 scp 上传文件/目录到远端
├── config/
│   ├── application-local.yml.template  # 后端生产配置模板（DB密码 + JWT密钥，占位符替换）
│   ├── emotion-server.service          # systemd 服务单元（开机自启 + 崩溃自愈）
│   └── emotion-web.conf                # Nginx 站点配置（静态托管 + /api 反代 8080）
└── README.md            # 本文档
```

同层还有 `deploy/init_full.sql`：全量数据库初始化脚本（自带 `CREATE DATABASE emotion_dashboard`，
含 `t_flyway_history` 已记录的迁移版本，导入后 Flyway 校验直接通过）。

## 快速开始

```bash
# 1) 前置：本机装 expect，装好 mvn 和一个可用 node
brew install expect   # macOS

# 2) 一键部署（必填 SSH 密码；MySQL 密码缺省读服务器 /root/mysql_pass.txt）
ALINUX_SSH_PASS='把服务器登录密码填这里' \
ALINUX_HOST=47.117.110.143 \
bash deploy/alinux/deploy.sh
```

其它可选环境变量见 `deploy.sh` 顶部注释。

## 部署后

| 服务 | 地址 / 端口 | 说明 |
|------|-------------|------|
| 前端页面 | `http://<公网IP>/` | Nginx :80 托管 dist |
| 后端接口 | `http://<公网IP>/api/...` | Nginx 反代到本机 :8080 |
| MySQL | `emotion_dashboard` 库 | 原生 MySQL 8.0，仅本机监听 |

常用运维命令（在服务器上）：

```bash
systemctl status emotion-server     # 后端状态 / 日志：journalctl -u emotion-server -f
systemctl restart emotion-server    # 发版后重启后端
nginx -t && nginx -s reload         # 测试并重载 Nginx
```

## 生产配置说明（application-local.yml）

- 后端 `application.yml` 里 `profiles.active=local` 且 `spring.config.additional-location` 会加载
  **jar 同目录**的 `application-local.yml`，优先级高于 jar 内部配置。
- 因此 DB 口令、JWT 签名密钥这两个敏感项通过模板替换后上传到
  `/opt/emotion/app/application-local.yml`，不进 git。
- 每次部署都会用 `openssl rand -hex 32` 生成**全新 JWT 密钥**并覆盖——若线上已有登录用户，
  发版后需重新登录（token 失效），属预期行为。

## 安全提醒

- 阿里云安全组只需放行 `80`（和已放行的 `22`）。**不要**对外开放 `3306` / `8080`/ `6379`。
- `22` 端口来源建议从 `0.0.0.0/0` 收紧为你自己的固定 IP（如 `117.147.95.96/32`）。
- MySQL/Redis 均只监听本机，需远程连 MySQL 时再按需给安全组加 `3306` 并限定来源 IP。