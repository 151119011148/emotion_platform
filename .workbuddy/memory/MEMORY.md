# emotion_platform 项目长期约定

## Flyway 迁移
- 目录 `emotion-server/src/main/resources/db/migration/`，`V{n}__{desc}.sql`。**已应用版本严禁改**（checksum 校验，改历史全域拒启）。`schema.sql`＝V1–V24 历史参考，不再加内容。
- **flyway 锁 6.5.7**（非父 BOM 8.5.13），因库为 MySQL 5.7.29。其解析器**按分号切语句**，禁用 `CREATE PROCEDURE/TRIGGER/BEGIN...END`（V26 炸过 ERROR 1064）；幂等补列用 `SET @v=(SELECT COUNT(*) FROM information_schema...)` → `SET @ddl=IF(@v=0,'ALTER...','SELECT 0')` → `PREPARE/EXECUTE/DEALLOCATE`。
- 迁移失败留 `t_flyway_history` 的 `success=0` 脏行 → 启动 `Validate failed: Detected failed migration`。修法＝删该行再幂等重放；**脚本没修好就删只会再插一条失败行**。
- 连库排障：`C:/Users/1/.workbuddy/binaries/python/envs/default/Scripts/python.exe` + pymysql（192.168.123.18，root/123456，emotion_dashboard）。**跑 python 必须清 `PYTHONHOME= PYTHONPATH=`**，否则 SRE module mismatch。
- 已占版本：V28 position_quantity、V31 日K缓存、V32 qfq版本、V33 tdx行业概念、V34 空间破局、**V35 WaveRider 7表+模板种子、V36 WaveRider 排班**。**V29/V30 是历史跳过的空号，不可回填**（回填＝out-of-order，`Detected resolved migration not applied to database: 29`）；**改版本号后必须清 `target/classes/db/migration/` 旧文件**，否则 `spring-boot:run` 读的还是旧的。加表/加任务一律写成下一个 Flyway 版本，别手工 INSERT。

## 定时任务：进程内 Quartz，排班在库
- 三层：`t_scheduler_job`（人维护）→ `QRTZ_*`（**不要手改**）→ `t_scheduler_run`（留痕）。
- 加任务＝写 `ManagedTask` bean + 往 `t_scheduler_job` 插一行，重启或 `POST /api/scheduler/reload` 生效。
- 「这次不用做」返回 `TaskResult.skip(原因)`，别用抛异常表达正常分支。不要配 `spring.quartz.startup-delay`（ApplicationRunner 会先起）。**到点时后端必须开着**；没跑先看 `t_scheduler_run`。

## 行情取数：写入侧与读取侧是两条路
- 落库：`MarketDataService.compute()` → `stockPoolWriter`(t_market_stock) / t_premium_tier / t_zt_perf / t_index_close；**`t_market_daily` 的 upsert 挂在 `MarketController`**，不走 HTTP 的调用（定时任务）必须自己补，否则全局九数表是空的。
- 读取：`snapshot(date,refresh)` **只查 JVM 内存缓存**；重启/淘汰/`refresh=true` 都会重打上游。`t_market_daily` 的读者是打分、NodeService、ReviewController，**snapshot 自己从不读它**。
- 走库读口：`/market/stocks`、`/premium-tiers`、`/indexes`。**必然打上游**：`/market/breadth`、`/market/daily-bars`、`/market/score-context`、`/d5/high`（逐只打腾讯日K，日志刷屏大头）、`/surveillance/refresh`。

## 日K取数：一律走 DailyBarService（落库缓存 + 保鲜期）
- 入口唯一 `DailyBarService.bars(symbol,start,end)`；**新代码别再直接调 `tencent.dailyBars()`**。
- 两张表（V31）：`t_daily_bar`（qfq 四价）+ `t_daily_bar_fetch`（覆盖留痕）。三条硬设计：①命中判定只看留痕**不看行数**（停牌日永久缺行）；②qfq 除权会整体重算历史价，留痕 30 天保鲜期；③**近 3 个自然日一律回源且不留痕**。
- 只存四价不存涨跌幅（派生值落库＝两处真相）。落库时向前多要 `lead-days=20` 天。
- 退路：`market.daily-bar.enabled=false` / `?refresh=1` / `evict(symbol)`。**它是缓存不是档案**。
- **qfq：pct 安全，绝对价与跨段拼接不安全**。两件会错：①拿缓存 qfq 价当"当时真实股价"做绝对价比较；②不同时间拉的段拼一起→衔接处假跳变。应对（V32）＝行级 `fq_version`，版本不唯一即整段重拉 + 回源比对重叠日收盘价（检测只能挂回源路径）。

## 数据表能力边界（上功能前先确认）
- **`t_zt_perf` 只有 3 天**（09-14/09-18/09-21）：是「昨日涨停股（**含首板**）今日表现」与 `prev_consecutive` 的唯一来源。晋级率可跨日自连接算，但「昨日高位股(≥3板)今日均涨」只有它给。与 `t_premium_tier`（不含首板、只到 8+ 档）别混。未回填就上线＝**规则永远不命中而非报错**，极难查。
- **`t_stock`（选股搜索字典）易空**：`GET /api/review/stocks/search` 只查它，空则「输什么名都搜不到」。灌数：`POST /api/market/stock-dict/sync` 或 `node scripts/sync_stock_dict.js`；排班周一 08:30 `stock_dict_sync`（V27）。搜索 SQL 已带 t_market_stock 兜底。
- **展示型「当日涨跌幅」不能只从三池取**：`t_market_stock` 只有涨停/炸板/跌停三池，普通涨跌票不在里面。补数顺序（`HighEcoMetricsService.backfillAnchorChg`）：① 日K `dailyBars(sym,date,date)` **只认 date 精确匹配那根**；② 仍缺且是当天 → 批量 `quotes()`，同样校验 `quoteDate == date`。拿不到就留 null，**绝不用相邻交易日顶替**。腾讯日K**个股**当天滞后（9/21 16:00 只到 9/18），**指数**当天有；实时快照 `qt.gtimg.cn` 个股当天有。三池(东财 push2) ≠ 日K(腾讯)，别混。此补数只填展示字段、不进 metrics。
- **区分「未评」与「真的 0」**：后端 VO 原生 int（如 `PressureBlock.survCount/survNuke`）在未评（`score == null`）时回落成 0，前端 `?? '—'` 拦不住。判据统一用 `score == null`＝整支未评；`survAvailable=true` 且确实 0 家时 score 仍有值。HighEcoView 已落 `isRated()/statVal()/nukeText()`。
- **`first_seal_time` 5 位/6 位混存**（`94536`/`104156`），旧解析只认 6 位会**静默丢 192/226 条**。一律先 `str(v).zfill(6)`。
- **`t_stock.listed_at` 全空（实测 0/5914 行）** → WaveRider 的「剔除次新（30 日内上市）」规则**静默不生效**（`isNewStock()` 恒 false，漏斗里那一步在途却永不剔票、**不报错**）。要用这条规则必须先回填上市日；`Stock.listedAt` 为空时按「不因新股被剔」处理是**有意**的，别误当 bug 改成「空即剔除」。

## 前端深色主题
- 全站深色（body `#0f1419` / 卡片 `#1a2332` / hover `#22303f` / 边框 `#2d3748`），Element 默认浅色。**teleport 到 body 的浮层 scoped 够不到**，只能写在 `App.vue` 非 scoped `<style>`。
- 正解＝整块换语义变量（`--el-text-color-*`/`--el-border-color-*`/`--el-fill-color-*`/`--el-bg-color-overlay`/`--el-dialog-bg-color`），浮层选择器覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`；硬编码角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）单独压。
- 交易日面板格子语义 class `day-non-trading`（划掉=休市）/ `day-future`（虚线圈=未到），由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**。
- `el-table` 展开行走独立变量 `--el-table-expanded-cell-bg-color`（默认纯白），压表格变量时**必须单独补**（已设 `#16202e`），并压 `.el-table__expanded-cell:hover`，否则划过闪烁。

## 阵眼（t_anchor）列表顺序：一律按起爆日倒序
- `AnchorService.listInPosition` / `listOverlapping` 用 `orderByDesc(startDate), orderByDesc(id)`；面板、节点页下拉、导出 md 共同消费，统一在服务层排（打分与顺序无关）。

## 板块节点（systemType='B'）已下线 —— 2026-09-23
- `t_node_event` 仅 3 条且**全是 A**，B 类历史 0 条，摘功能零数据成本。此后**所有节点恒为 `systemType='A'`**：前端 payload 写死 `'A'`；`NodeView.vue` 已无 A/B 任何 UI（planA+planB 合并成 `plan`）。
- **别再以为复算分 A/B 两套**：`NodeService/NodeController/NodeVO` 从不读 systemType，分支只在 `NodeSuggestService`（~20 处 `systemB`），已加「已下线」Javadoc 但**代码保留**（V34 破局要动同一个类）。
- `t_node_event.system_type` 列保留（V1 不可改），语义＝「恒 A 的遗留列」。腾出的三个位置留给破局的 `node_type`。

## 本机构建验证
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`，JDK `jdk1.8.0_251`。`cd emotion-server && mvn -DskipTests compile && mvn test`（418 用例全离线不连库；**`-q` 会吞 surefire 汇总**，数用例要解析 `target/surefire-reports/TEST-*.xml`）。
- 本机 Bash 的 PATH 是坏的（无 `ls/head/find/grep`）；PowerShell 输出不回显 → 跑命令用绝对路径 node/python。
- **`taskkill` 在 Git Bash 里必须写 `//F //T //PID`**（单斜杠被当路径报 `无效参数 - 'F:/'`）。停自己起的临时进程前**先确认 PID 归属**（曾误杀用户 5173 dev server）。
- 前端校验优先用 **vite 真编译链**：临时 config 另起端口指向验证实例，`GET /src/views/*.vue` 看是否 200 且产物含新代码——比 `@vue/compiler-sfc` 更硬。轻量替代 `%TEMP%/check_sfc.js`（判据＝`compileScript().bindings` 与模板产物 `_ctx.X` 取差集，**差集为空**；非 inline 模式产物统一编 `_ctx.X`，扫 `$setup.` 会假通过）。**别用** `node -e "import('vite').build()"`（挂十几分钟）。**`agent-browser` 本机未装**（需 ~500MB Chromium），别默认可用。
- **Edit 同一条消息里对同一文件连发两次会互相覆盖**；多改同文件要串行或 Write 脚本一次改完。脚本改多处时**每个 `old` 断言 `count == 1`**，用 `new` 自身做幂等护栏。
- **行尾：必须按原始字节判，不能用默认文本读。**`open(p, encoding='utf-8').read()` 是 universal newlines，会把 `\r\n` 翻成 `\n`，**任何文件都数出 CRLF=0**（曾据此把两个真 CRLF 的 `.vue` 误判成 LF，脚本全没命中）。判法：`open(p,'rb').read()` 数 `b.count(b'\r\n')` 与 `b.count(b'\n')`，相等且 >0 即 CRLF；写回 `open(p,'wb')` + `newline=''`。
- **仓库行尾是混的，别按扩展名猜**（`.vue` 21/4、`.java` 208/46、`.sql` 3/31、`.js` 9/2）。脚本姿势：先探 eol → `old.replace('\n', eol)` → 改完统一归一化。**Edit 工具自身会跨行尾归一化**，单点小改可照常 Edit。**新建文件（Write）落下来是 LF**；既有 Java 是 CRLF + 中文，Read/Edit 会当二进制拒掉，**改既有 Java 必须落脚本**。
- **多行/含反斜杠的脚本一律 Write 落文件，别用内联 `-e`/`-c`**：Git Bash 转义链会吞字符（`node -e` 的 `\n` 变字面 `/n`，曾把 `daily-bar:/n` 写进 application.yml 直接 `ScannerException` 拒启；`python -c` 的 `\\` 被吃成 `SyntaxError`）。校验：yml 用 `yaml.safe_load`；.vue 用 `check_sfc.js`；Java 用 `mvn test`。

## WaveRider（连板周期选股策略引擎）—— 完整判据见同目录 `MEMORY-waverider.md`
- 代码/文档/回测脚本都在 `prd/短线连板周期选股策略引擎/`：PRD **v1.4**、预览页 **`短线连板策略-回测与推荐清单.html`**（由 `回测脚本/06→07→08` 构建，**勿手改产物**）；**2026-09-24 已按 PRD 落地第一版**（V35/V36 + `/api/waverider` + `views/WaveRiderView.vue`）。
- **⚠️ 口径头号陷阱：候选池是「T 日已涨停」，凡以 `close(T)` 为起算价的收益一律不可成交。** A 口径 `close(T+1)/close(T)−1`=+3.36%；**B 实盘 `close(T+1)/open(T+1)−1`=−0.20%**；C 隔夜 +3.68%（A≈C×B）→ **alpha 全在吃不到的隔夜跳空里**。此后回测/因子标定起算价一律 `open(T+1)`。
- **封单÷成交额是 T 日唯一能事前预警「T+1 买不进」的指标，且强单调**（`<15%`→8.4% 一字 ｜ `≥300%`→87.0%）；角色是**可执行性折价**，不是加分项。
- **`gap≤3%` 门槛已被样本外证伪**（样本内 n=105 B=+2.19%/56.2%；09-22 样本外 L2 **−2.40%/胜率 23.1%**，被挡下的反而 +0.60%）→ 只能当**执行纪律（高开不追）**，不能当**择时规律**；§6.2 权重在 B 口径重标定前**冻结**。
- **情绪温度只能当粗旋钮**（方差解释上限日间 11%，B 口径降到 8.2%，写入时点极不稳定）→ 必须「读不到就退回默认档」，绝不做候选产出的前置依赖。**候选池规模 + 跌停家数**是更早、且不依赖人工填报的退潮指标。
- 两条硬经验：①**节点/阈值类规则必须配「近 20 日触发次数」面板**（否则参数变死分支没人知道）；②**写死的绝对阈值几乎必然过期**，涉及市场量级的都要有「动态基准（近 N 日分位数）」。
