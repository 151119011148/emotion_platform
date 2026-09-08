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

## 一、建库

```bash
mysql -uroot -p --default-character-set=utf8mb4 < emotion-server/src/main/resources/schema.sql
```

内容是 14 张表的 `CREATE TABLE IF NOT EXISTS`，重复跑无害。

**存量库升级要多看一眼文件末尾「存量库迁移」那一节**：那批注释掉的 `ALTER` 是给已经建过库的人准备的——新建库不用管（列已经在 `CREATE TABLE` 里），但如果你是几周前建的库、现在拉新代码，直接跑会 `Unknown column`。按日期挑你需要的那几条解开注释执行。

## 二、后端

### 1. 放好本地凭据（不这一步起不来）

`application.yml` 里只有占位（`password: ${MYSQL_PASSWORD:}`、`secret: ${JWT_SECRET:}`），真值走 Spring 的 profile 文档，未入库。

```bash
cd emotion-server
cp application-local.yml.example application-local.yml
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
| 复盘 md | `GET /records/review-doc`（复盘页的「导出复盘文档」在用）；`POST /records/import`、`GET /records/import/template`、`GET /records/import/export` **无前端入口**（导入页已撤，契约没撤，要用走 curl）；`GET /records/import/detail` 仍被复盘页底部只读明细块使用 |

## 六、九维口径（速览）

温度 = 已评各维之和 ÷ (3 × 已评维数) × 100。每维取值 -1…3，`null` 表示未评（整维剔出分母，不是 0 分）；已评维数不足 5 维不出阶段。阶段线 15/35/55/80。

1 连板高度 · 2 分档溢价 · 3 涨跌停家数 · 4 炸板·封板·回封 · 5 大面数 · 6 量能 · 7 主线明确度 · 8 阵眼 · 9 监管。

逐维判据、动态分档的归组规则、-1 档的触发条件，全在 [技术方案.md](技术方案.md) 第四节。

## 七、行情源

`application.yml` 的 `market:` 一节配了三路上游（腾讯报价与日 K、东财三池、东财异动公告），字段里注释了哪些主机在本机可用、哪些只能走 http。不联网也能用：所有读数都可以手填，拉不到时对应维退回"未评"。
