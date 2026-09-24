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
- **并发 upsert 会死锁**（实测 4 次，`Deadlock found`，多只票同一毫秒各自 `upsertBatch` → `(symbol,trade_date)` 插入意向锁互撞）。`store()` 已 try-catch，代价只是这几只票下次仍回源、页面不受影响；且留痕与 bar 行同一 try，失败时**留痕也不写**，不会造出「留痕说有、行没有」的假覆盖。**同类先例**：`SurveillanceService:182` 已注释「上游可并发，**写库不行**」并串行写，`DailyBarService` 缺这层（修法＝写库串行 或 死锁后重试）。
- **qfq：pct 安全，绝对价与跨段拼接不安全**。两件会错：①拿缓存 qfq 价当"当时真实股价"做绝对价比较；②不同时间拉的段拼一起→衔接处假跳变。应对（V32）＝行级 `fq_version`，版本不唯一即整段重拉 + 回源比对重叠日收盘价（检测只能挂回源路径）。

## 数据表能力边界（上功能前先确认）
- **`t_zt_perf` 只有 3 天**（09-14/09-18/09-21）：是「昨日涨停股（**含首板**）今日表现」与 `prev_consecutive` 的唯一来源。晋级率可跨日自连接算，但「昨日高位股(≥3板)今日均涨」只有它给。与 `t_premium_tier`（不含首板、只到 8+ 档）别混。未回填就上线＝**规则永远不命中而非报错**，极难查。
- **`t_stock`（选股搜索字典）易空**：`GET /api/review/stocks/search` 只查它，空则「输什么名都搜不到」。灌数：`POST /api/market/stock-dict/sync` 或 `node scripts/sync_stock_dict.js`；排班周一 08:30 `stock_dict_sync`（V27）。搜索 SQL 已带 t_market_stock 兜底。
- **展示型「当日涨跌幅」不能只从三池取**：`t_market_stock` 只有涨停/炸板/跌停三池，普通涨跌票不在里面。补数顺序（`HighEcoMetricsService.backfillAnchorChg`）：① 日K `dailyBars(sym,date,date)` **只认 date 精确匹配那根**；② 仍缺且是当天 → 批量 `quotes()`，同样校验 `quoteDate == date`。拿不到就留 null，**绝不用相邻交易日顶替**。腾讯日K**个股**当天滞后（9/21 16:00 只到 9/18），**指数**当天有；实时快照 `qt.gtimg.cn` 个股当天有。三池(东财 push2) ≠ 日K(腾讯)，别混。此补数只填展示字段、不进 metrics。
- **区分「未评」与「真的 0」**：后端 VO 原生 int（如 `PressureBlock.survCount/survNuke`）在未评（`score == null`）时回落成 0，前端 `?? '—'` 拦不住。判据统一用 `score == null`＝整支未评；`survAvailable=true` 且确实 0 家时 score 仍有值。HighEcoView 已落 `isRated()/statVal()/nukeText()`。
- **`first_seal_time` 5 位/6 位混存**（`94536`/`104156`），旧解析只认 6 位会**静默丢 192/226 条**。一律先 `str(v).zfill(6)`。
- **`t_stock.listed_at` 全空（实测 0/5914 行）** → WaveRider 的「剔除次新（30 日内上市）」规则**静默不生效**（`isNewStock()` 恒 false，漏斗里那一步在途却永不剔票、**不报错**）。要用这条规则必须先回填上市日；`Stock.listedAt` 为空时按「不因新股被剔」处理是**有意**的，别误当 bug 改成「空即剔除」。
- **通达信（海王星）三张表的实际口径**（V23/V33 从 `incon.dat`/`tdxhy.cfg`/`infoharbor_block.dat`/`tdxbk.cfg` 导入）：`t_industry`(145 字典) + `t_industry_stock`(5581, **二级行业** by code) + `t_concept_board`(269 字典, board_code=简称/board_name=全称/index_code=880xxx) + **`t_stock_concept`(46149, 个股→题材板块成分)**。
  **`StockConcept` 的 Javadoc 写「东财 BKxxxx」是错的**，实际 concept_code 是通达信板块简称（266 个，100% 命中 `t_concept_board`）。用户说「题材」时默认指这张表。
  **⚠️ 雷**：`ConceptIndexService.rebuild()`（`POST /api/review/concept-index/rebuild`、`ReviewController:258`）会**先清空 `t_stock_concept` 再按东财 ~504 概念整盘重建** → 一点就把通达信题材冲掉（已在该类 Javadoc 标注，行为未改，交用户决策）。
- **`t_market_stock.industry` 是东财行业且被截断成 4 个汉字**（「房地产开」「农产品加」），58 个值里只 12 个能命中通达信行业字典 → 展示层别直接拿它当「题材」给用户看。

## 前端深色主题
- 全站深色（body `#0f1419` / 卡片 `#1a2332` / hover `#22303f` / 边框 `#2d3748`），Element 默认浅色。**teleport 到 body 的浮层 scoped 够不到**，只能写在 `App.vue` 非 scoped `<style>`。
- 正解＝整块换语义变量（`--el-text-color-*`/`--el-border-color-*`/`--el-fill-color-*`/`--el-bg-color-overlay`/`--el-dialog-bg-color`），浮层选择器覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`；硬编码角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）单独压。
- 交易日面板格子语义 class `day-non-trading`（划掉=休市）/ `day-future`（虚线圈=未到），由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**。
- `el-table` 展开行走独立变量 `--el-table-expanded-cell-bg-color`（默认纯白），压表格变量时**必须单独补**（已设 `#16202e`），并压 `.el-table__expanded-cell:hover`，否则划过闪烁。
- **`el-table` 的 `stripe` 斑马纹走 `--el-fill-color-lighter`（默认 `#fafafa`＝纯白），只压 `--el-table-*` 挡不住**——那条规则写的是 `background:var(--el-fill-color-lighter)`，不经过表格自有变量（与「展开行」同因）。已在 `App.vue` `.el-table` 块补 `#1f2b3b`。**实害是浅色文字掉进白底直接不可读**，不只是难看。
- **全站输入框 / 告警条底色是纯白 `#fff` / 浅色**（`--el-fill-color-blank`、`--el-color-*-light-9` 从未覆盖；实测 `/review`、`/mainline`、`/waverider` 三页一致）→ **既有全站状态，非单页 bug**；改它＝全站换观感，需单独决策。

## 阵眼（t_anchor）列表顺序：一律按起爆日倒序
- `AnchorService.listInPosition` / `listOverlapping` 用 `orderByDesc(startDate), orderByDesc(id)`；面板、节点页下拉、导出 md 共同消费，统一在服务层排（打分与顺序无关）。

## 板块节点（systemType='B'）已下线 —— 2026-09-23
- `t_node_event` 仅 3 条且**全是 A**，B 类历史 0 条，摘功能零数据成本。此后**所有节点恒为 `systemType='A'`**：前端 payload 写死 `'A'`；`NodeView.vue` 已无 A/B 任何 UI（planA+planB 合并成 `plan`）。
- **别再以为复算分 A/B 两套**：`NodeService/NodeController/NodeVO` 从不读 systemType，分支只在 `NodeSuggestService`（~20 处 `systemB`），已加「已下线」Javadoc 但**代码保留**（V34 破局要动同一个类）。
- `t_node_event.system_type` 列保留（V1 不可改），语义＝「恒 A 的遗留列」。腾出的三个位置留给破局的 `node_type`。

## 本机构建验证 —— 详细步骤与全部坑见同目录 `MEMORY-build-verify.md`（动手编译/验证前读）
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`，JDK `jdk1.8.0_251`；`cd emotion-server && mvn -DskipTests compile && mvn test`（418 用例全离线不连库；**`-q` 会吞 surefire 汇总**，数用例要解析 `target/surefire-reports/TEST-*.xml`）。
- 本机 Bash 的 PATH 是坏的（无 `ls/head/find/grep`）；PowerShell 输出不回显 → 跑命令用绝对路径 node/python。**`taskkill` 在 Git Bash 里必须写 `//F //T //PID`**，停临时进程前**先确认 PID 归属**（曾误杀用户 5173 dev server）。
- **改文件先按原始字节判行尾**（`open(p,'rb').read()` 数 `\r\n`，别用默认文本读）：仓库行尾是混的，别按扩展名猜；**Edit 同一条消息里对同一文件连发两次会互相覆盖**，多处改动落脚本、每个 `old` 断言 `count == 1`。新建文件是 LF；既有 Java 是 CRLF + 中文，Read/Edit 会当二进制拒掉，**改既有 Java 必须落脚本**。
- **多行/含反斜杠的脚本一律 Write 落文件**，别用内联 `-e`/`-c`（Git Bash 转义链会吞字符）。校验：yml 用 `yaml.safe_load`，.vue 用 `check_sfc.js`，Java 用 `mvn test`。
- **样式/视觉问题必须真渲染验证**（本机 Edge 无头 + iframe 探针量 `getComputedStyle`）：只过编译等于没验；**别肉眼判缩略截图的颜色**（`#fafafa` 白行曾被我误读成「正常」）。`agent-browser` 本机未装。

## WaveRider（连板周期选股策略引擎）—— 完整判据见同目录 `MEMORY-waverider.md`
- PRD **v1.4** + 预览页 **`短线连板策略-回测与推荐清单.html`** + 回测脚本都在 `prd/短线连板周期选股策略引擎/`（预览页是 `06→07→08` 构建产物，**勿手改**）；**已落地实现**：V35/V36/V37 + `/api/waverider` + `views/WaveRiderView.vue`。
- **先说清买点再谈收益**：A = `close(T+1)/close(T)−1`（**T 日打板买入 = 本策略真实买点**）｜B = `close(T+1)/open(T+1)−1`（**D+1 开盘再接**）；同批 226 样本 A=+3.36% / B=−0.20%，差全在隔夜跳空里（**≠「A 不可执行」**）。两口径**必须并排看**，只摆 A 乐观、只摆 B 悲观。
- **封单强度（封单额÷成交额）= 打板口径下唯一跨档单调的正向因子**：五档 A **+1.71/+3.55/+4.37/+5.40/+7.56%**、胜率 **53→87%**，同批 B 口径单调递减 → `sort_by` 默认 **`seal_strength`（降序）**、`seal_lock_ratio` 退化为**标记线（不剔除）**。**「买不进就剔除」这条已废**。`gap≤3%` 门槛已样本外证伪，只作**执行纪律（高开不追）**。
- **温度只能当粗旋钮**（写入时点不稳，读不到退回默认档，不做候选前置依赖）；**候选池规模 + 跌停家数**是更早的退潮指标。**节点/阈值规则必须配「近 20 日触发次数」面板**，绝对阈值要有动态基准。
- **⚠️「节点」一词本项目有三个所指，别猜**：①**节点追踪**＝`/nodes` 页 / `NodeView.vue` / `t_node_event`，其「节点票」是 **`node_stock` 字段（格式 `名称(代码)`）**；②阵眼 `t_anchor`；③WaveRider PRD §5.3/5.4 的「节点日/节点票」（情绪周期转折日）。用户说「节点」时**先确认哪条线**——曾按 ③ 实现整轮后全部回滚。
- 候选池标「来自节点追踪」：**读侧瞬态不落库**（`CandidateStock.nodeTags` 用 `@TableField(exist=false)`，节点 status 会被复算改写，落库＝冻住当时判断）；匹配在 `NodeService.tagCandidates`，三角色 `NODE_STOCK`/`ANCHOR`/`D0_CAND`，**名称必须去空白**（明细「罗 牛 山」vs `d0_candidates`「罗牛山」）；`stockCodeOf` 是解析 `名称(代码)` 的唯一口径。
- 候选池「题材」列取**通达信题材板块**（`t_stock_concept`，用板块简称；`t_concept_board` 补全称/880xxx），**不再用 `t_market_stock.industry`**（东财行业、截断 4 字）。同样**读侧瞬态**：`CandidateStock.tdxThemes` + `TopicHeatService.tagTdxThemes`，一条 SQL 带出「该题材当日涨停家数」并 `ORDER BY code, ztCount DESC` 排好；**热度子查询必须 LEFT JOIN**（内连接会丢掉「该题材当日只有它一只涨停」的票）。一票带 1~11 个题材 → 前端只铺前 3 + `+N`，取不到时回退 `row.topic`。
- **候选池的「警示」＝两个刻意分开的字段，别合并**：`alertFlag`（**瞬态**，当前唯一值 `DUANDAO`＝一字断魂刀）说「**买不进**」——不扣分、不折仓、不改排序；`riskFlag`（**落库**）说「质地有风险」，`build()` 里带它的**仓位折半**。理由：封单锁死恰是本策略最强的正向因子，当风险折半＝与「封单锁死不该剔除、反而靠前」自相矛盾。
  - 判据**只有一处**：`TiantiService.isDuanDao`（首封≤93030 且 0 炸板、连续两日、板数≥2、流通≤35亿、封单≥10亿或封成比≥3、换手<5%）。候选池走 `TiantiService.duanDaoCodes(date,codes)` + 私有 `poolOn()`——**故意不用 `listPool`**（后者会跑 `industryClassify.apply`，判据不看行业）。接线在 `WaveRiderController.tagAlerts`，三处读口。
  - 实测：09-24 全市场 52 只涨停**只命中泰慕士(001234)**、09-23 同样只有它；与「连板生态」页 `/api/tianti` 的 `oneWordKilling` 对账一致。导出 md 给中文「一字断魂刀」、csv 给机器码 `DUANDAO`（与同行 `risk_flag` 给码一致）。
- 候选池前端：`代码/节点/身位`三列已删；**「最高板/身位」标识已全部摘掉**（`posShort` 函数与 `.tag-pos` 样式一并删，名称 `el-tooltip` 里也不留情位行）——用户认定它 13/15 都是「最高板」、无区分度。名称格只挂**节点**标签；「风险」列改名**「警示」**（`alertFlag` 红 + `riskFlag` 琥珀并排）。`effect="plain"` 的题材标签在深色底上是白药丸 → 改 `--el-tag-bg-color/-border-color/-text-color`，并多套一层容器选择器（`.waverider .thm .tag-theme`）压过 `.el-tag--plain.el-tag--primary`。
- **起验证实例必须加 `SPRING_QUARTZ_AUTO_STARTUP=false`**，否则与用户 8080 的 Quartz 双跑、重复写 `t_strategy_run`。**`mvn spring-boot:run "-Dspring-boot.run.arguments=A B"` 传两个参数会炸**（mvn.cmd 按空格再拆，报 `'C:\Program' 不是内部或外部命令`）→ 第二个参数用环境变量传，命令行只管 `--server.port`。
- 回滚成组改动：脚本从原脚本源码截到 `ok = True` 前 `exec` 出 `EDITS`，**逆序**反向替换（new→old），逐条断言 `count==1`，任一未命中即中止。比手工删可靠（本次两个文件字节数精确还原）。
