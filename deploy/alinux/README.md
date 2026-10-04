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

## 服务器上的手改项（deploy.sh 不会重放，重建机器要照着补）

这几项改的是 `/etc` 和 mysqld 运行时，发版流程碰不到它们，所以只存在于当前这台机器上：

1. **2 GiB swapfile + dnf 错峰**（`mysqld` 被 OOM 杀过 4 次，全是 `dnf` 在 02:00 前后引发的）
   ```bash
   dd if=/dev/zero of=/swapfile bs=1M count=2048 && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
   echo '/swapfile none swap sw,pri=10 0 0' >> /etc/fstab      # 追加前先确认没重复
   mkdir -p /etc/systemd/system/dnf-makecache.timer.d && printf '[Timer]\nOnCalendar=*-*-* 03:00:00\nRandomizedDelaySec=0\n' > /etc/systemd/system/dnf-makecache.timer.d/10-quiet.conf
   mkdir -p /etc/systemd/system/dnf-makecache.service.d && printf '[Service]\nNice=19\nIOSchedulingClass=idle\n' > /etc/systemd/system/dnf-makecache.service.d/10-quiet.conf
   systemctl daemon-reload && systemctl restart dnf-makecache.timer
   ```
   坑：`RandomizedDelaySec=` 留空**不会**清掉继承来的 60 分钟抖动，必须显式写 `=0`；
   `systemctl restart` 一个 OnCalendar 已“过期”的 timer 会立刻跑一次该 service；
   systemd 239 的 `systemctl show -p OnCalendar/-p RandomizedDelaySec` 是空的（属性名不匹配），
   要验证生效只读 `systemctl show -p NextElapseUSecRealtime`，应正好落在 `03:00:00` 而不是一个抖动区间。

2. **Nginx 访问时间打点**（`/etc/nginx/nginx.conf` 的 `http` 段，站点文件 `conf.d/emotion-web.conf` 里没有 access_log，发版不会覆盖）
   ```nginx
   log_format timed '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" "$http_user_agent" '
                    'rt=$request_time urt=$upstream_response_time';
   access_log /var/log/nginx/access.log timed;
   ```
   收益：每个接口有真实毫秒数，`urt` 是后端耗时、`rt-urt` 是网关自身开销，不用再靠请求簇反推。

3. **MySQL 慢查询日志**（用 `SET PERSIST` 而不是改 `/etc/my.cnf.d`：写坏 cnf 会让 mysqld 起不来）
   ```bash
   mysql -uroot -p -e "SET PERSIST slow_query_log=ON, long_query_time=0.5, log_output='FILE';"
   ```
   只持久化这三个动态变量，`slow_query_log_file` **没动**，仍是 mysqld 默认的 datadir 内主机名文件
   `/var/lib/mysql/<hostname>-slow.log`。要挪到 `/var/log/mysql/` 得先建目录并 `chown mysql:mysql`
   再 SET PERSIST，顺序反了 mysqld 会因为打不开日志文件报错。
   写入的是 `/var/lib/mysql/mysqld-auto.cnf`，段名是 `mysql_dynamic_variables`
   （不是 `Variables`，也不是 `mysql_server`），重启后仍在。
   阈值从默认 10 s 降到 0.5 s——本库热查询实测 14~37 ms，0.5 s 已经只抓真正的异常。

4. **systemd 启动限速**：仓库里的 `emotion-server.service` 已带 `StartLimit*`，但服务器还多一份
   `/etc/systemd/system/emotion-server.service.d/10-start-limit.conf`（drop-in 优先级更高）。
   下次发版后它冗余，确认取值一致即可 `rm -rf` 那个目录。

5. **journald 封顶 500 M**（`StdOutImpl` 关掉前它实测吃到 896 M）
   ```bash
   mkdir -p /etc/systemd/journald.conf.d && printf '[Journal]\nSystemMaxUse=500M\n' > /etc/systemd/journald.conf.d/10-cap.conf
   systemctl restart systemd-journald && journalctl --vacuum-size=500M
   ```
   重启 journald 本身就会切段并按新上限回收（那次 896 M → 384 M，`--vacuum-size` 再跑就是 0 B 了）；
   生效与否读 journald 自己的开机行：`System journal (…) is 384.0M, max 500.0M`。
   **vacuum 是不可逆的删档**，动手前先把 OOM / 崩溃循环那些唯一线索抽到
   `/root/p0-incident-logs-<时间戳>.txt.gz`（现存 119 行、2.6 KB）。磁盘其实很宽（40 G 用了 23%），
   这条是卫生项不是救火项。

6. **mysqld 的 OOM 顺位**（30 天被杀 4 次，而库是唯一不可重建的状态）
   ```bash
   mkdir -p /etc/systemd/system/mysqld.service.d && printf '[Service]\nOOMScoreAdjust=-600\n' > /etc/systemd/system/mysqld.service.d/10-oom-priority.conf
   systemctl daemon-reload
   echo -600 > /proc/$(systemctl show -p MainPID --value mysqld)/oom_score_adj   # 当场生效，不用重启 mysqld
   ```
   最后一条是关键：drop-in 只在**下次重启**才起作用，而重启数据库本身就是一次故障；
   直写 `/proc` 与 systemd 起效后的结果完全等价（`oom_score` 从 752 掉到 354）。
   `-600` 不是免死金牌，只是把顺序改成「宁可杀 dnf / java 让 systemd 拉起来，也不杀库」。

## 安全提醒

- 阿里云安全组只需放行 `80`（和已放行的 `22`）。**不要**对外开放 `3306` / `8080`/ `6379`。
- `22` 端口来源建议从 `0.0.0.0/0` 收紧为你自己的固定 IP（如 `117.147.95.96/32`）。
- MySQL/Redis 均只监听本机，需远程连 MySQL 时再按需给安全组加 `3306` 并限定来源 IP。