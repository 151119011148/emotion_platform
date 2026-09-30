# emotion_platform 项目长期约定

> 本文件只放**跨模块、高频**的判据。已按主题拆出的伴生文件，动手前先读对应的那个：
> - `MEMORY-data.md` —— 行情/日K取数、数据表能力边界、阵眼、节点、持仓记账口径
> - `MEMORY-frontend.md` —— 深色主题、el-table 列宽、登录/账号前端约定
> - `MEMORY-waverider.md` —— WaveRider 选股引擎完整判据（动策略或回测前必读）
> - `MEMORY-build-verify.md` —— 本机构建 / 前端验证 / 行尾与脚本姿势
>
> 拆出原因：单文件曾到 17 KB，超过注入上限后**每次会话读不到文件尾部**，最长的几节等于白写。

## Flyway 迁移
- 目录 `emotion-server/src/main/resources/db/migration/`，`V{n}__{desc}.sql`。**已应用版本严禁改**（checksum 校验）。`schema.sql`＝V1–V24 历史参考，不再加内容。
- **flyway 锁 6.5.7**（非父 BOM 8.5.13），因库 MySQL 5.7.29。解析器**按分号切语句** → 禁用 `CREATE PROCEDURE/TRIGGER/BEGIN...END`（V26 炸 ERROR 1064）；幂等补列用 `SET @v=(SELECT COUNT(*) FROM information_schema...)` → `SET @ddl=IF(@v=0,'ALTER...','SELECT 0')` → `PREPARE/EXECUTE/DEALLOCATE`。
- 迁移失败留 `t_flyway_history` 的 `success=0` 脏行 → 启动报 `Validate failed: Detected failed migration`。修法＝删该行再幂等重放；**脚本没修好就删＝再插一条失败行**。
- 连库排障：`C:/Users/1/.workbuddy/binaries/python/envs/default/Scripts/python.exe` + pymysql（开发库 192.168.123.18，root/123456，emotion_dashboard）。**跑 python 必须清 `PYTHONHOME= PYTHONPATH=`**（否则 SRE module mismatch）。
- 已占版本：V28 / V31 日K缓存 / V32 qfq版本 / V33 tdx行业概念 / V34 空间破局 / V35 WaveRider 7表+模板 / V36 排班 / V37 / V38 position_exit_price / V39 node_event_space_break_comment / V40 node_event_candidate_pool / **V41+V42 都是 review_objective_scheduler_job**（41＝头部写 V41 的变体，42＝改名并改头部的变体，只差一行注释）/ **V43 user_role_and_single_session**（t_user.role + token_version）。
  **V29/V30 是跳过的空号，不可回填**（out-of-order 报错）；**改版本号后必须清 `target/classes/db/migration/` 旧文件**（否则撞号）。
- **⚠️ 多人各自发版 → 版本漂移是常态，发版前必先验版本号对齐**（2026-09-29/30 连撞三次）。排查＝`SELECT installed_rank,version,description,checksum,success,installed_on FROM t_flyway_history ORDER BY installed_rank DESC LIMIT 10`（表是自定义的 `t_flyway_history`）对比本地 `db/migration/`。
- **正解＝按 checksum 反查归属，把本地文件改名到库里记录的版本号**。**别用 `ignore-missing-migrations` 掩盖**（远端 `application-local.yml` 会被别人下次发版整份覆盖）。补号时**逐字节复制**原文件才对得上 checksum。
- **checksum 可以自己算**（`scripts/flyway_checksum.py`）：逐行（\n/\r\n/\r 都算换行）去掉行尾换行符后按 UTF-8 喂 CRC32，**不把分隔符喂回去**，最后取有符号 32 位。
- **新脚本先在临时库试跑**（建 `emotion_probe`、拷一份表结构、连跑两次验幂等、跑完删）：直接往真库放，中途报错会把表留在半改状态，Flyway 之后还要再放一次。

## 定时任务：进程内 Quartz，排班在库
- 三层：`t_scheduler_job`（人维护）→ `QRTZ_*`（**勿手改**）→ `t_scheduler_run`（留痕）。加任务＝`ManagedTask` bean + `t_scheduler_job` 插一行，重启或 `POST /api/scheduler/reload` 生效。
- 「这次不用做」返回 `TaskResult.skip(原因)`，别抛异常表达正常分支。不配 `spring.quartz.startup-delay`。**到点时后端必须开着**；没跑先看 `t_scheduler_run`。

## 账号与登录（2026-09-30 落地，V43）
- **单点登录＝账号互踢**：不是跨系统 SSO。`t_user.token_version` 每次登录 +1 并写进 token 的 `tv` 声明；`JwtAuthFilter` 比对不符就**不建立认证上下文**（不抛异常——抛了是 500，真实语义是"没登录"，该由 SecurityConfig 出 401/403）。进程内缓存 + 库兜底，重启后旧 token 仍判得对。
- **`t_user.role`**：`USER` / `SUPER_ADMIN`。常量与判据唯一出处 `AuthContext`（`isSuperAdmin()` 读 `ROLE_SUPER_ADMIN` 权限，来源是 token 里的 `role` 声明）。
- **注册已收拢**：前端无入口（`/register` 路由与 `Register.vue` 已删）；后端 `/api/auth/register` **保留但要求超管**。开户正路＝`/api/admin/users`（`AdminController` + `AdminService`）。
- **SecurityConfig 的放行面只有 `/api/auth/login` 与 `/api/auth/register`**，其余 `/api/auth/**` 必须已登录——`/me`、`/logout` 也要拿到 401 前端才会清场跳转；放行的话只会拿到业务码 400，页面停在原处看不出已被顶下线。
- **自锁护栏**：不能删自己、不能把自己降级、至少一个超管。**改角色/重置密码都会 +1 版本号**强制重新登录，否则旧 token 里的角色声明与库里对不上＝两个真相。
- **业务错误走 `BizException`** → `GlobalExceptionHandler` 翻成 HTTP 200 + `code=400` + 中文 message（前端拦截器只在 200 这条分支读 `res.message`，4xx/5xx 会被统一说成"网络错误"，消息根本送不到页面）。只认 `BizException`，其它异常照旧 500。

## 本地联调（2026-09-30 实测）
- **`mvn spring-boot:run` 的工作目录是模块目录 `emotion-server/`**（日志 `started by 1 in D:\gaofeng\emotion_platform\emotion-server`），于是 `spring.config.additional-location` 里的 `file:./` 落在模块目录 → **加载的是 `emotion-server/application-local.yml`，不是根目录那份**。根目录那份只在 `java -jar` 且 cwd 为根时才生效。
- 两份 local 指向**不同库**：根目录 `MYSQL_IP: 192.168.123.18`（开发库 root/123456）；模块那份直接写死 `spring.datasource.url=jdbc:mysql://47.117.110.143`（**阿里云测试库** root/Gf651125）且 `flyway.validate-on-migrate: false`。
- **⚠️ 本地一起后端，新迁移就直接落到阿里云测试库上**（2026-09-30 起服务时 V43 就是这么上的，日志只报 "Successfully applied 1 migration" 是因为那边已到 42）。要连开发库必须显式覆盖数据源 URL。
- **8080 常被另一项目 saas-core 占着**（`E:\gaofeng\worker\project_relation\...\saas-core`，jdk17）。别 kill 它——后端改 `--server.port=8081`，前端用临时配置 `emotion-web/vite.dev-8081.config.js`（继承 `vite.config.js`、只改代理目标）：`node node_modules/vite/bin/vite.js --config vite.dev-8081.config.js`。
- **已存在 `com.emotion.config.GlobalExceptionHandler`**（`RuntimeException`→400、`Exception`→500）。加异常处理要**并进它**；另建一个 `GlobalExceptionHandler`（哪怕在别的包）会撞 bean 名 `globalExceptionHandler`，启动直接 `ConflictingBeanDefinitionException`。
- 未登录/被踢时 Spring Security 走默认的 `Http403ForbiddenEntryPoint`（没配 formLogin/httpBasic），拿到的是 **403 不是 401**；前端拦截器 401/403 都清 token 跳登录页，行为正确，不必为此改配置。

## 发版到阿里云 47.117.110.143 —— 用 `scripts/deploy_alinux.py`（paramiko 顶替缺失的 expect）
- 用法 `PYTHONIOENCODING=utf-8 PYTHONHOME= PYTHONPATH= EMOTION_SSH_PASS=密码 python scripts/deploy_alinux.py --host 47.117.110.143 --user root [--skip-frontend|--skip-backend]`。jar 版本号**自动 glob**（当前 `1.0.0`）。
- 布局：jar `/opt/emotion/app/emotion-server.jar`、静默页 `/var/www/emotion-web`、服务 `emotion-server.service`（Restart=always，**启动失败会无限重启刷日志**）、nginx 反代 8080。
- **不做两件事**：不跑 `init_full.sql`（每表 `DROP TABLE IF EXISTS`）、不覆盖远端 yml 的 JWT（重生成会让全员掉线）。**服务器上没有源码目录、没有历史 jar 备份** → 覆盖前先备份。
- **探活别只用 8 秒**：Spring Boot 约 20 秒才 `Started EmotionApplication`，期间 `/api` 一律 502。要轮询到 `Started EmotionApplication` 或业务码。
- 端到端验证：`POST /api/auth/login`（gaofeng/123456）拿 token → 打目标接口；改动落库的先 `dryRun:true` 再真跑。

## 本机构建验证（详见 `MEMORY-build-verify.md`）
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd` + JDK `jdk1.8.0_251`；`mvn -DskipTests compile && mvn test`（全离线不连库，**2026-09-30 实测 534 用例全绿**；`-q` 会吞 surefire 汇总）。
- **改既有 Java 必须落脚本**（CRLF+中文，Read/Edit 当二进制拒掉）；每条 `old` 断言 `count == 1`。多行/含反斜杠的脚本一律 Write 落文件。
- `.vue` 校验：`scripts/check_vue_sfc.js`（`@vue/compiler-sfc` 的 parse+compileScript+compileTemplate）；要更硬的验证用 vite 真编译链。
- **样式/视觉必须真渲染验证**（本机 Edge `--headless=new --screenshot`，URL 必须 `file:///` 绝对）。只过编译＝没验。
