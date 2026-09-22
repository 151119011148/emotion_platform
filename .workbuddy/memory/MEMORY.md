# emotion_platform 项目长期约定

## 数据库变更：一律走 Flyway
- 迁移脚本目录：`emotion-server/src/main/resources/db/migration/`，命名 `V{版本}__{英文下划线描述}.sql`。
- **已应用的版本严禁修改**（`validate-on-migrate` 会对 checksum，改历史会让所有环境启动失败）；新变更写下一个版本号。
- 历史表 `t_flyway_history`；`clean-disabled: true`；`baseline-version: 24` 给存量库打基线（不重放 V1–V24，那批有 DELETE+INSERT 种子收敛，会冲掉手工改过的权重）。
- `schema.sql` 已被切成 V1–V24，仅作历史参考，**不再往里加内容**。
- 依赖当前锁在 pom 的 `<flyway.version>6.5.7</flyway.version>`（**不是**父 BOM 托管的 8.5.13）：连的库是 192.168.123.18 的 **MySQL 5.7.29**。该版本 MySQL 支持内置在 flyway-core，不需要 `flyway-mysql` 模块；哪天库升到 8.0 再回退到 8.5.13 并加回该模块。
- **迁移脚本禁止用复合语句**（`CREATE PROCEDURE` / `TRIGGER` / `BEGIN...END`）：6.5.7 的 MySQL 解析器按分号切语句、不认过程体，会在第一个内部分号处切成碎片报 ERROR 1064（V26 就是这么炸的）。幂等补列一律用 V1 已有的套路：`SET @v = (SELECT COUNT(*) ... information_schema)` → `SET @ddl = IF(@v=0, 'ALTER ...', 'SELECT 0')` → `PREPARE/EXECUTE/DEALLOCATE`。
- 迁移失败会在 `t_flyway_history` 留下 `success=0` 的脏行，之后每次启动 `Validate failed: Detected failed migration to version N` 直接拒启。修法=删掉那条失败行（`DELETE ... WHERE version='N' AND success=0`，等同 `flyway repair`），再让脚本幂等重放；**脚本没修好就删，下次启动只会再插一条失败行**。
- 连库排障：本机没有 mysql 客户端，用 `C:/Users/1/.workbuddy/binaries/python/envs/default/Scripts/python.exe` + pymysql（或 node + mysql2）连 192.168.123.18（root/123456，库 emotion_dashboard）。**跑 python 必须清环境变量**：`PYTHONHOME= PYTHONPATH= <绝对路径python>`，否则 SRE module mismatch。

## 定时任务：进程内 Quartz，排班在库里
- 三层：`t_scheduler_job`（人维护：bean/cron/启用/错过策略）→ `QRTZ_*`（Quartz 自己的账，**不要手改**）→ `t_scheduler_run`（执行留痕）。
- 加任务 = 写实现 `ManagedTask` 的 Spring bean + 往 `t_scheduler_job` 插一行（建议写成下一个 Flyway 版本而不是手工 INSERT），重启或 `POST /api/scheduler/reload` 生效。
- 不要用抛异常表达「这次不用做」——那是正常分支，返回 `TaskResult.skip(原因)`。
- 不要配 `spring.quartz.startup-delay`：ApplicationRunner 会先于 Quartz 起来导致装载失败。
- 任务在后端进程内跑，**到点时后端必须开着**；没跑先看 `t_scheduler_run` 有没有那天的行。

## 行情取数：写入侧与读取侧是两条路（别以为落库了就不会再打上游）
- 落库侧：`MarketDataService.compute()` → `stockPoolWriter`(t_market_stock) / t_premium_tier / t_zt_perf / t_index_close，
  再由 `MarketController` 补 `marketDailyStore.upsertSnapshot`（t_market_daily 客观九数，涨跌家数只在 liveBreadth=true 时写）。
- 读取侧：`snapshot(date, refresh)` **只查 JVM 内存缓存**（`ConcurrentHashMap`，历史日不过期、当天按 `market.intraday-ttl-ms`），
  **没有任何「先读库」分支**；`refresh=true` 还会先 `cache.remove(date)`。所以进程重启 / 缓存被 `cache-max-entries` 淘汰 / 一键拉取，都会重打上游——哪怕库里那天数据是齐的。
- t_market_daily 的读者是打分（DailyRecordService）、NodeService、NodeSuggestService、ReviewController，**snapshot 自己从不读它**。
- 走库的读口：`/market/stocks`、`/premium-tiers`、`/indexes`（都是本地表）。
- 必然打上游的读口：`/market/breadth`（东财只有实时口径，无历史）、`/market/daily-bars`、
  `/market/score-context` 与 `/d5/high`（阵眼/监管逐只打腾讯日 K，**既不落库也没缓存**，每次开页面都打，是「请求行情源」日志刷屏的大头）、
  `/surveillance/refresh`（公告，一次十几到三十几次）。
- 一键拉取（ReviewFetchService）与定时任务（DailyMarketPullTask）都显式 `snapshot(date, true)`——拉取=去源取最新，不是读历史；
  只有 T1 回补先查库（`hasZtPool()`）决定要不要补。

## 行情取数的一个事实
`MarketDataService.snapshot(date, true)` 负责写 `t_market_stock / t_premium_tier / t_zt_perf / t_index_close`，
但 **`t_market_daily` 的 upsert 原本挂在 `MarketController` 里**——任何不走 HTTP 的调用（定时任务这类）必须自己补这一步，
否则拉到了数据但全局客观九数那张表是空的。

## 前端深色主题：统一在 App.vue 全局段压 Element 变量
- 全站是深色页（body #0f1419 / 卡片 #1a2332 / hover #22303f / 边框 #2d3748），但 Element Plus 走默认浅色，
  逐页 scoped 覆盖会漏——**凡是 teleport 到 body 的浮层（date-picker 面板、下拉）scoped 样式根本够不到**，只能写在 App.vue 的非 scoped `<style>` 里。
- 正确姿势是整块换语义色变量（`--el-text-color-* / --el-border-color-* / --el-fill-color-* / --el-bg-color-overlay`），
  而不是逐个节点打补丁；硬编码色的角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）要单独压。
- 交易日日期面板的格子语义 class：`day-non-trading`（划掉=休市/周末）、`day-future`（虚线圈=未到），
  由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**（没有全局注入点）。
- el-table 的展开行（`type="expand"`）背景走独立变量 `--el-table-expanded-cell-bg-color`，默认 `var(--el-fill-color-blank)`＝纯白，
  压表格变量时必须**单独补这一条**（已设为 #16202e）；Element 自带 `.el-table__expanded-cell:hover{background-color:transparent!important}`，
  要连同 hover 一起压，否则鼠标划过会透出卡片色闪烁。

- 弹窗同理：`el-dialog` 面板走 `--el-dialog-bg-color`（默认白），深色化在 App.vue 全局段整块压；
  弹窗里的下拉/日期面板是 teleport 到 body 的，浮层变量块的选择器要覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`。

## 一个容易踩的空表：t_stock（选股搜索字典）
- 选股远程搜索（`GET /api/review/stocks/search`）只查 t_stock；新库第一次灌之前它是空的，表现为「输什么名都搜不到」。
- 灌数据的两条路：`POST /api/market/stock-dict/sync`（StockDictService，拉东财全市场 5900+ 只 upsert），
  或 `node scripts/sync_stock_dict.js`（绕过重启直接写库，口径一致）。排班是每周一 08:30 的 stock_dict_sync（V27）。
- 搜索 SQL 已带 t_market_stock 兜底（行情明细里落过的代码也能搜到），字典没同步时不至于全空。

## 阵眼（t_anchor）列表顺序：一律按起爆日倒序
- `AnchorService.listInPosition` / `listOverlapping` 用 `orderByDesc(startDate), orderByDesc(id)`——最近一轮起爆的排最前。
- 这个顺序被面板、节点页「锚定龙头」下拉、导出 md 共同消费，所以统一在服务层排，别在各消费方各排各的。
- 打分与顺序无关（D5 是角色加权平均，第 8 维取最差），改顺序不影响任何分数。

## 展示型「当日涨跌幅」不能只从三池取
- `t_market_stock` 只有涨停/炸板/跌停三个池，**普通涨跌的票根本不在里面**。凡是卡片上要显示某只票当日涨幅
  （阵眼、持仓、自选这类），只查三池就会大面积出现「—」，看着像数据丢了。
- 补数顺序（见 `HighEcoMetricsService.backfillAnchorChg`）：① 日 K `TencentClient.dailyBars(sym, date, date)`，
  **只认 date 精确匹配那一根**；② 还缺且是当天 → 批量 `quotes()` 实时快照，同样必须校验 `quoteDate == date`。
  拿不到就留 null，**绝不用相邻交易日的涨幅顶替**。
- 腾讯日 K **个股**当天滞后（实测 9/21 16:00 拉 sz000993 / sh600127，最新只到 9/18），
  但**指数**当天就有（sh000001 到 9/21）。实时快照 `qt.gtimg.cn` 个股当天也有，所以两层都要。
- 两个上游别混：三池（东财 push2，当天就有）≠ 日 K（腾讯 newfqkline，个股滞后）。
  凡是「今天」要看个股日线的功能（阵眼跨度/第 8 维、异动监管追踪、五维曲线当日点）都会缺当天，
  要当天数据只能靠实时快照补。
- 这种补数只填展示字段、不进 metrics，不影响评分；判定涨跌停另走阈值逻辑，不依赖这里的 chg。

## 本机构建验证
- Maven：`C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`；JDK `jdk1.8.0_251`。
- `cd emotion-server && mvn -DskipTests compile && mvn test`（既有 407 个用例全离线不连库）。
- 本机 Bash 的 PATH 是坏的（`ls/head/find/grep` 都没有，只有内置命令）、PowerShell 输出不回显：要跑命令就用绝对路径的 node/python，详见当日日志「本机环境」一节。
- 前端校验**别用** `node -e "import('vite').build()"`（会挂住十几分钟无输出）；用 `@vue/compiler-sfc` 的 `parse + compileTemplate` 逐个编译 .vue，秒级出结果。
- .vue 校验的正确判据（3.5.42 实测）：`compileScript().bindings` 与模板产物里的 `_ctx.X` 取差集，**差集为空**才算模板无未定义引用。
  非 inline 模式下产物统一把 setup 绑定编译成 `_ctx.X`，`$setup.` 前缀一个都不出现——扫 `$setup.` 会得到「引用 0 个」的假通过。
  现成脚本：`%TEMP%/check_sfc.js <绝对路径.vue>`（含 parse/script/template 三段错误与差集检查）。
- Edit 工具同一条消息里对**同一个文件**连发两次会互相覆盖，改多处的同一文件必须串行改完再 grep 复核。

## 日 K 取数：一律走 DailyBarService（落库缓存，带保鲜期）
- 入口唯一：`DailyBarService.bars(symbol, start, end)`。新代码**不要再直接调 `tencent.dailyBars()`**——那是无缓存无落库的裸上游调用，一只票一次网络往返。
- 两张表（迁移 V31）：`t_daily_bar`（qfq 四价）+ `t_daily_bar_fetch`（拉取覆盖留痕）。V29/V30 已预留给 WaveRider，所以表建在 V31。
- 三条硬设计：①命中判定只看 `t_daily_bar_fetch` 有无覆盖记录，**不看行数**——停牌日天然缺行且永久缺，按行数判会让停牌股永远回源；②qfq 不是真静态，除权会整体重算历史价，所以留痕有 30 天保鲜期（`market.daily-bar.max-age-days`），过期重拉；③近 3 个自然日（`volatile-days`）一律回源且**不留痕**，免得盘中拉一次把当天终值钉死。
- 只存四价、不存涨跌幅：pct / lowPct 读时按库内前一根现算。派生值落库会出现两处真相，除权后错得更隐蔽。
- 落库时向前多要 `lead-days=20` 天一并存下，保证窗口首日也有前收可比。
- 退路：配置 `market.daily-bar.enabled=false` 全关；`/api/market/daily-bars?refresh=1` 强制回源；`DailyBarService.evict(symbol)` 清留痕。
- **它是缓存不是档案**：任何新逻辑别把它当不可变历史事实表用（成交额那两个方法 `twoMarketDayAmountBillion` / `dayAmountBillion` 仍走裸日 K，没接进来）。
## 日 K 复权（qfq）：涨跌幅安全，绝对价和跨段拼接不安全
- 实测：腾讯日 K 第 6 段留空 = 不复权（`day`），`qfq` = 前复权（`qfqday`）；响应带 `version`（复权版本号）和 `prec`（区间前一日收盘）。8 只票 6 只在 3 个月内除过权（茅台 6/1 bfq 1309.60 / qfq 1281.58，差 2.14%）。
- **pct 是安全的**：相邻两日共享同一复权因子，比值里抵消，未来除权不会改变历史区间的涨跌幅。除权日当天只有用 bfq 才会算出假跌幅，我们走 qfq 所以对。
- **两件会错的事**：①拿缓存的 qfq 价当"当时真实股价"做绝对价比较（会随新除权漂移）；②不同时间拉的段拼在一起，衔接处因子不同 → 假跳变。
- 应对（V32）：行级 `fq_version`；读一段时校验版本唯一，两种即判未命中整段重拉；回源时比对重叠日收盘价，对不上就整只票作废旧行与留痕。检测只能挂回源路径——命中路径不打上游，发现不了。
- 别改存不复权价 + 因子表：上游不给因子，要双口径各拉一次或引除权事件源，成本远大于收益。

## 写文件：多行文本一律用 Write 落脚本，别用内联 node -e
- 本机 Git Bash 的转义链会把 `node -e "..."` 里字符串中的 `\n` 变成**字面的 `/n`（正斜杠+n）**写进文件。
- 已踩一次：09-21 给 application.yml 加 `market.daily-bar` 段时踩了，09-22 启动直接 `ScannerException: while scanning a simple key ... daily-bar:/n` 拒启。纯文本/配置文件没有编译期保护，只有运行时炸。
- 正确姿势：要改多行文本，先用 Write 工具写一份 .js 到临时目录，再用绝对路径 node 执行它（脚本里还能顺手处理 BOM / CRLF 保持原样）。单行替换才可以用内联。
- 校验手段：yml 用 `PYTHONHOME= PYTHONPATH= .../envs/default/Scripts/python.exe -c "import yaml;yaml.safe_load(...)"`（已装 pyyaml）；.vue 用 `%TEMP%/check_sfc.js`；Java 用 mvn test。
## 数据就绪度：t_zt_perf 只有 3 天（重要，任何依赖它的新功能先确认）
- 2026-09-21 实测：`t_zt_perf` 仅有 09-14 / 09-18 / 09-21 三天行（`t_market_stock` 从 08-24 起是齐的）。它是「昨日涨停股（**含首板**）今日表现」的唯一来源，也是 `prev_consecutive` 的唯一出处。
- 任何读它的新逻辑上线前必须先回填历史，否则历史区间静默失效，且症状是「规则永远不命中」而非报错，非常难查。注意能力边界：**晋级率可以靠三池跨日自连接算出**，但「昨日高位股（≥3板）今日平均涨幅」只有它能给。
- 判定前期与课程表 `t_premium_tier` 不一样：后者不含首板、只到 8+ 档；两者别混用。

## WaveRider（连板周期选股策略引擎）待办状态
- PRD v1.1 已定稿在 `prd/WaveRider-PRD.html`，定位是 emotion_platform 的策略层模块，**尚未写任何代码**。
- 迁移预留 **V29**（t_strategy / t_strategy_version / t_strategy_template / t_strategy_run / t_node_detect / t_candidate_stock / t_candidate_t1）+ **V30**（waveriderDailyScanTask 排班）。V28 已被 position_quantity 占用。
- 两条硬经验（做别的功能也适用）：①**节点/阈值类规则必须配「近 20 日触发次数」面板**，否则参数变成死分支没人知道（v1.0 三条规则就是这么失效的）；②**写死的绝对阈值几乎必然过期**，凡涉及市场量级的都要有「动态基准（近 N 日分位数）」模式。

## 展示口径：区分「未评」与「真的 0」
- 后端子项 VO 里有**原生 int**（如 `PressureBlock.survCount/survNuke`）：未评（`score == null`，如监管事件窗为空）时它们回落成 0，
  前端 `xx ?? '—'` 拦不住，页面会把「不知道」印成「0 家」「0 只」，看着像事实。
- 判据统一用 `score == null` = 整支未评；`survAvailable=true` 且确实 0 家时 score 仍有值。HighEcoView 已落成 `isRated()/statVal()/nukeText()` 三个 helper。
