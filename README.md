# 情绪周期仪表盘（emotion_platform）

A 股短线情绪周期的录入、打分与复盘工具。后端 Spring Boot 2.7 + MyBatis-Plus + MySQL 8，前端 Vue 3 + Element Plus + ECharts。

设计前提一句话：**行情读数由系统拉，判断由人做。** 九维里每一维的分数你都能手改，改完以你填的进分，清空即退回自动值；`recalc-all` 重算全部历史时不会洗掉你手改的那些格。

架构与算法的完整口径见 [技术方案.md](技术方案.md)，本文件只写"怎么跑起来"。

## 目录

```
emotion-server/     Spring Boot 后端（JDK 8）
emotion-web/        Vue 3 前端（Vite）
技术方案.md         九维口径、表设计、算法、页面设计
```

## 一、建库（Flyway 自动迁移）

**不用手工跑 SQL**：后端启动时会由 Flyway 自动把 `emotion-server/src/main/resources/db/migration/` 下的 `V1__xxx.sql` … 顺序执行到
`emotion_dashboard` 库，执行记录写在 `t_flyway_history` 里。只需要保证库本身存在：

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS emotion_dashboard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

两种情况的差别：

| 库的状态 | Flyway 的行为 |
|---|---|
| **空库**（新装） | 从 V1 顺序回放全部迁移，一次建成现在的样子 |
| **存量库**（已经手工跑过旧 `schema.sql`） | 检测到非空且没有 `t_flyway_history`，按 `baseline-version=24` 打基线，V1–V24 一条都不重放，只跑 V25 及以后 |

为什么存量库不重放：那段历史里有若干 `DELETE + INSERT` 的种子收敛语句，重放会把你在管理端改过的权重冲掉。

**新增/改表一律写新的迁移文件，不许改已经存在的文件**——`validate-on-migrate` 会对 checksum，改历史版本会导致所有环境启动失败。
命名规则：`V{版本号}__{英文下划线描述}.sql`，版本号只增不复用。

`schema.sql` 保留作历史参考，**不要再往里加内容**（它已经被切成 V1–V24 了）。

## 二、后端

### 1. 放好本地凭据（不这一步起不来）

`application.yml` 里只有占位（`password: ${MYSQL_PASSWORD:}`、`secret: ${JWT_SECRET:}`），真值走 Spring 的 profile 文档，未入库。

```bash
cd emotion-server
cp application-local.yml application-local.yml
# 改里面的 datasource.password 与 jwt.secret
```

文件必须放在 **`emotion-server/` 这一层**（模块根），不是 `src/main/resources/`：Spring Boot 默认会搜 `file:./`，IDE 与 `java -jar` 的工作目录都是它；放进 `src/main/resources` 则会被 Maven 抄进 `target/classes` 和打出来的 jar，等于换个地方泄露。

没有这个文件时的表现是**启动即失败**，报在 `jwtUtil` 上：

```
io.jsonwebtoken.security.WeakKeyException: The specified key byte array is 0 bits
which is not secure enough for any JWT HMAC-SHA algorithm
```

这是故意的——JWT bean 比数据源先初始化，所以你先看到的是密钥报错而不是连不上库。宁可起不来并指回这一节，也不要一个"看着跑起来了其实连的是别的库"的状态。

### 2. 起服务

需要 JDK 8（`jdk1.8.0_20x`），Maven 3.x。

```bash
cd emotion-server
mvn spring-boot:run                     # 开发跑
# 或者
mvn -DskipTests package && java -jar target/emotion-server-1.0.0.jar
```

默认端口 8080（`application.yml` 的 `server.port`，可用 `--server.port=` 覆盖）。

## 三、前端

```bash
cd emotion-web
npm install
npm run dev
```

http://localhost:5173 —— Vite 把 `/api` 代理到 `http://localhost:8080`，后端 CORS 白名单认的就是 5173（`SecurityConfig.java:57`，另有一个 3000）。改端口会导致同源 POST 被判 `Invalid CORS request`，非要换请看 `vite.e2e.config.js` 里那段 `proxyReq` 改 `origin` 头的写法。

## 四、测试与构建

```bash
cd emotion-server && mvn test        # 270 个用例，全部离线，不连库不起 Spring 上下文
cd emotion-web && npm run build
```

后端测试是 `java.lang.reflect.Proxy` 和静态函数风格，**不起 ApplicationContext**，所以 `application.yml` 改坏了它们一律照绿——配置的验证只能靠真起一次服务。

`src/test/resources/` 下三份输入是真实数据，别当脱敏样例看：

- `golden/recalc-14d.jsonl` + 生成它的 `recalc-14d.dump.sql` —— 14 个真实交易日的打分输入与结论基线。`RecalcGoldenTest` 断言"这份输入重算出来必须还是这组结论"，改了打分口径它会红。换账号或改判据后要按 `recalc-14d.dump.sql` 头部注释重新 dump（口令读 `$MYSQL_PWD`，不写在文件里）。
- `import/review_2026-09-03.md` —— 一份真实复盘手记，含持仓与仓位。

## 五、接口

全部挂在 `/api`，除 `auth` 外都要 `Authorization: Bearer <token>`。

| 前缀 | 端点 |
|---|---|
| `/auth` | `POST /register`、`POST /login` |
| `/records` | `POST /`、`PUT /{id}`、`GET /today`、`GET /date/{date}`、`GET /range`、`GET /latest`、`GET /advice`、`GET /curve`、`POST /recalc`、`POST /recalc-all`、`PUT /positions`、`PUT /predictions` |
| `/market` | `GET /snapshot`、`GET /stocks`、`GET /premium-tiers`、`POST /premium-tiers`、`GET /score-context`、`GET /premium-pool`、`GET /daily-bars`、`GET /surveillance`、`GET /surveillance/tracked`、`POST /surveillance/refresh` |
| `/themes` | `GET /`、`POST /`、`PUT /{id}`、`GET /{themeId}/stocks`、`POST /{themeId}/stocks` |
| `/anchors` | `GET /`、`GET /span`、`GET /series`、`POST /`、`PUT /{id}`、`DELETE /{id}` |
| `/nodes` | `GET /`、`GET /current`、`POST /`、`PUT /{id}`、`GET /{id}/suggest`、`POST /{id}/adopt` |
| `/stocks` | `POST /refresh`、`GET /search` |
| `/scheduler` | `GET /jobs`、`POST /reload`、`POST /jobs/{name}/cron?cron=`、`POST /jobs/{name}/enabled?enabled=`、`POST /jobs/{name}/run`、`GET /runs?job=&limit=` |
| 复盘 md | `GET /records/review-doc`（复盘页的「导出复盘文档」在用）；`POST /records/import`、`GET /records/import/template`、`GET /records/import/export` **无前端入口**（导入页已撤，契约没撤，要用走 curl）；`GET /records/import/detail` 仍被复盘页底部只读明细块使用 |

## 六、九维口径（速览）

温度 = 已评各维之和 ÷ (3 × 已评维数) × 100。每维取值 -1…3，`null` 表示未评（整维剔出分母，不是 0 分）；已评维数不足 5 维不出阶段。阶段线 15/35/55/80。

1 连板高度 · 2 分档溢价 · 3 涨跌停家数 · 4 炸板·封板·回封 · 5 大面数 · 6 量能 · 7 主线明确度 · 8 阵眼 · 9 监管。

逐维判据、动态分档的归组规则、-1 档的触发条件，全在 [技术方案.md](技术方案.md) 第四节。

## 七、行情源

`application.yml` 的 `market:` 一节配了三路上游（腾讯报价与日 K、东财三池、东财异动公告），字段里注释了哪些主机在本机可用、哪些只能走 http。不联网也能用：所有读数都可以手填，拉不到时对应维退回"未评"。

## 八、定时任务（单机 Quartz + 库表维护）

任务不由外部脚本触发，也不挂在操作系统计划任务上：**进程内的 Quartz 自己到点唤醒**，
任务定义躺在 `t_scheduler_job` 表里，`QRTZ_*` 那套调度账只记 Quartz 自己的状态。
三者分工：

| 层 | 表 / 位置 | 干什么 |
|---|---|---|
| 排班（人维护） | `t_scheduler_job` | 挂哪个 `bean_name`、cron、是否启用、错过怎么处置 |
| 调度（Quartz） | `QRTZ_*` | 触发时刻计算、状态、实例心跳。**不要手工改** |
| 留痕（人看） | `t_scheduler_run` | 每次成功/失败/跳过的结论、耗时、异常原因 |

现在只有一个任务：

| job_name | 触发 | 干什么 |
|---|---|---|
| `daily_market_pull` | 每天 19:00（`0 0 19 * * ?`，按 Asia/Shanghai） | 拉当天公开行情落库：三池明细 / 档位溢价 / 昨日涨停今日表现 / 五大指数收盘 / 全局客观九数。非交易日自动跳过 |

维护动作（都要 Bearer token）：

```bash
curl -s localhost:8080/api/scheduler/jobs -H "Authorization: Bearer $TOKEN"      # 列表
curl -s -X POST "localhost:8080/api/scheduler/jobs/daily_market_pull/run" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "localhost:8080/api/scheduler/jobs/daily_market_pull/cron?cron=0%200%2020%20*%20*%20%3F" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "localhost:8080/api/scheduler/jobs/daily_market_pull/enabled?enabled=false" -H "Authorization: Bearer $TOKEN"
curl -s "localhost:8080/api/scheduler/runs?limit=20" -H "Authorization: Bearer $TOKEN"
```

**加一个新任务**两步：写一个实现 `ManagedTask` 的 Spring bean（返回 `TaskResult`），
往 `t_scheduler_job` 插一行（建议写成下一个 Flyway 迁移版本，别手工 INSERT，环境之间才一致），
重启或 `POST /api/scheduler/reload` 生效。

前提：任务在后端进程内执行，**19:00 那一刻后端必须开着**。开了但没到晚饭没拉数，
第一件事是看 `t_scheduler_run` 里有没有那天的行——没有就是进程当时不在。
