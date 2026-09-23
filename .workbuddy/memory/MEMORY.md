# emotion_platform 项目长期约定

## 数据库变更：一律走 Flyway
- 迁移目录 `emotion-server/src/main/resources/db/migration/`，命名 `V{n}__{desc}.sql`。**已应用版本严禁改**（validate-on-migrate 校验 checksum，改历史会让所有环境拒启）。`schema.sql` 已切成 V1–V24，仅作历史参考、不再加内容。
- flyway 锁 **6.5.7**（不是父 BOM 的 8.5.13），因为库是 MySQL **5.7.29**；升到 8.0 才回 8.5.13 并加 `flyway-mysql`。6.5.7 的 MySQL 解析器**按分号切语句**，禁用 `CREATE PROCEDURE/TRIGGER/BEGIN...END`（V26 炸过，ERROR 1064）；幂等补列用 `SET @v=(SELECT COUNT(*) FROM information_schema...)` → `SET @ddl=IF(@v=0,'ALTER...','SELECT 0')` → `PREPARE/EXECUTE/DEALLOCATE`。
- 迁移失败会在 `t_flyway_history` 留 `success=0` 脏行，之后启动直接 `Validate failed: Detected failed migration`。修法＝删该行（等同 `flyway repair`）再幂等重放；**脚本没修好就删，只会再插一条失败行**。
- 连库排障：本机无 mysql 客户端，用 `C:/Users/1/.workbuddy/binaries/python/envs/default/Scripts/python.exe` + pymysql（192.168.123.18，root/123456，库 emotion_dashboard）。**跑 python 必须清 `PYTHONHOME= PYTHONPATH=`**，否则 SRE module mismatch。

## 定时任务：进程内 Quartz，排班在库
- 三层：`t_scheduler_job`（人维护：bean/cron/启用/错过策略）→ `QRTZ_*`（**不要手改**）→ `t_scheduler_run`（执行留痕）。
- 加任务＝写实现 `ManagedTask` 的 bean + 往 `t_scheduler_job` 插一行（写成下一个 Flyway 版本，别手工 INSERT），重启或 `POST /api/scheduler/reload` 生效。
- 「这次不用做」返回 `TaskResult.skip(原因)`，**不要用抛异常表达正常分支**。不要配 `spring.quartz.startup-delay`（ApplicationRunner 会先于 Quartz 起来）。任务在后端进程内跑，**到点时后端必须开着**；没跑先看 `t_scheduler_run` 有没有那天的行。

## 行情取数：写入侧与读取侧是两条路
- 落库侧：`MarketDataService.compute()` → `stockPoolWriter`(t_market_stock) / t_premium_tier / t_zt_perf / t_index_close；**`t_market_daily` 的 upsert 原本挂在 `MarketController`**，所以不走 HTTP 的调用（定时任务）必须自己补这一步，否则数据拉了但全局九数那张表是空的。
- 读取侧：`snapshot(date, refresh)` **只查 JVM 内存缓存**（无任何「先读库」分支）；进程重启 / 缓存被 `cache-max-entries` 淘汰 / `refresh=true` 都会重打上游，哪怕库里那天数据是齐的。`t_market_daily` 的读者是打分、NodeService、ReviewController，**snapshot 自己从不读它**。
- 走库的读口：`/market/stocks`、`/premium-tiers`、`/indexes`。**必然打上游**：`/market/breadth`（东财只有实时口径）、`/market/daily-bars`、`/market/score-context` 与 `/d5/high`（逐只打腾讯日 K，无库无缓存，是日志刷屏大头）、`/surveillance/refresh`。

## 日 K 取数：一律走 DailyBarService（落库缓存，带保鲜期）
- 入口唯一 `DailyBarService.bars(symbol,start,end)`；**新代码不要再直接调 `tencent.dailyBars()`**（裸上游，一只票一次往返）。
- 两张表（迁移 V31）：`t_daily_bar`（qfq 四价）+ `t_daily_bar_fetch`（拉取覆盖留痕）。三条硬设计：①命中判定只看留痕**不看行数**（停牌日天然缺行且永久缺）；②qfq 会因除权整体重算历史价，留痕有 30 天保鲜期；③近 3 个自然日一律回源且**不留痕**（免得盘中拉一次把当天终值钉死）。
- 只存四价、不存涨跌幅（派生值落库＝两处真相）。落库时向前多要 `lead-days=20` 天。
- 退路：`market.daily-bar.enabled=false` 全关；`?refresh=1` 强回源；`evict(symbol)` 清留痕。**它是缓存不是档案**。

## 日 K 复权（qfq）：涨跌幅安全，绝对价 / 跨段拼接不安全
- 第 6 段留空＝不复权(`day`)，`qfq`＝前复权；响应带 `version` 与 `prec`。
- **pct 安全**（相邻两日共享复权因子，比值里抵消）。**两件会错**：①拿缓存 qfq 价当"当时真实股价"做绝对价比较；②不同时间拉的段拼在一起→衔接处假跳变。
- 应对（V32）：行级 `fq_version`，读一段版本不唯一即整段重拉；回源比对重叠日收盘价。检测只能挂回源路径（命中路径不打上游，发现不了）。

## t_zt_perf 只有 3 天 —— 任何依赖它的新功能先确认
- 实测仅 09-14 / 09-18 / 09-21 三天行（`t_market_stock` 从 08-25 起齐）。它是「昨日涨停股（**含首板**）今日表现」与 `prev_consecutive` 的唯一来源，也是 v1.1 分歧日判据的基础。
- 能力边界：**晋级率可跨日自连接算**，但「昨日高位股(≥3板)今日平均涨幅」只有它能给。与 `t_premium_tier`（不含首板、只到 8+ 档）别混用。
- 未回填就上线的症状是「规则永远不命中」而非报错，极难查。

## 展示型「当日涨跌幅」不能只从三池取
- `t_market_stock` 只有涨停/炸板/跌停三池，**普通涨跌的票不在里面**；只查三池会大面积出现「—」，像数据丢了。
- 补数顺序（`HighEcoMetricsService.backfillAnchorChg`）：① 日 K `dailyBars(sym,date,date)`，**只认 date 精确匹配那一根**；② 仍缺且是当天 → 批量 `quotes()`，同样校验 `quoteDate == date`。拿不到就留 null，**绝不用相邻交易日顶替**。
- 腾讯日 K **个股**当天滞后（实测 9/21 16:00 拉个股最新只到 9/18），**指数**当天就有；实时快照 `qt.gtimg.cn` 个股当天有。两个上游别混：三池(东财 push2) ≠ 日 K(腾讯)。
- 这种补数只填展示字段、不进 metrics，不影响评分。

## 前端深色主题：统一在 App.vue 全局段压 Element 变量
- 全站深色（body #0f1419 / 卡片 #1a2332 / hover #22303f / 边框 #2d3748），Element 走默认浅色。**teleport 到 body 的浮层（date-picker 面板、下拉、dialog）scoped 样式根本够不到**，只能写在 App.vue 的非 scoped `<style>` 里。
- 正解是整块换语义变量（`--el-text-color-*` / `--el-border-color-*` / `--el-fill-color-*` / `--el-bg-color-overlay` / `--el-dialog-bg-color`），浮层选择器要覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`；硬编码角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）单独压。
- 交易日面板格子语义 class：`day-non-trading`（划掉=休市/周末）、`day-future`（虚线圈=未到），由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**（没有全局注入点）。
- `el-table` 展开行走独立变量 `--el-table-expanded-cell-bg-color`（默认 `var(--el-fill-color-blank)`＝纯白），压表格变量时**必须单独补这一条**（已设 #16202e），并连同 Element 自带的 `.el-table__expanded-cell:hover` 一起压，否则划过闪烁。

## 一个容易踩的空表：t_stock（选股搜索字典）
- 选股远程搜索（`GET /api/review/stocks/search`）只查 t_stock；新库第一次灌之前它是空的，表现为「输什么名都搜不到」。
- 灌数据两条路：`POST /api/market/stock-dict/sync`（StockDictService）或 `node scripts/sync_stock_dict.js`；排班是每周一 08:30 的 `stock_dict_sync`（V27）。搜索 SQL 已带 t_market_stock 兜底。

## 阵眼（t_anchor）列表顺序：一律按起爆日倒序
- `AnchorService.listInPosition` / `listOverlapping` 用 `orderByDesc(startDate), orderByDesc(id)`；面板、节点页「锚定龙头」下拉、导出 md 共同消费，统一在服务层排。打分与顺序无关，改顺序不影响任何分数。

## 本机构建验证
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`，JDK `jdk1.8.0_251`。`cd emotion-server && mvn -DskipTests compile && mvn test`（既有 407 用例全离线不连库）。
- 本机 Bash 的 PATH 是坏的（`ls/head/find/grep` 都没有，只有内置命令）；PowerShell 输出不回显 → 跑命令用绝对路径 node/python。
- 前端校验**别用** `node -e "import('vite').build()"`（挂十几分钟无输出）；用 `%TEMP%/check_sfc.js`（`@vue/compiler-sfc` 的 parse + compileTemplate，秒级出结果）。
- .vue 校验正确判据：`compileScript().bindings` 与模板产物里的 `_ctx.X` 取差集，**差集为空**才算模板无未定义引用。非 inline 模式下产物统一编成 `_ctx.X`，扫 `$setup.` 会得到「引用 0 个」的假通过。
- **Edit 工具同一条消息里对同一个文件连发两次会互相覆盖**，改多处的同一文件必须串行改完再复核（或改用 Write 落脚本一次改完）。脚本一次改多处时，**每个 `old` 都要断言 `count == 1`，并用 `new` 自身做「是否已插入过」的护栏**，否则重跑会重复插入。
- **行尾（重大易错点）：必须按原始字节判 `${CRLF/LF}`，不能用默认文本读。**`open(p, encoding='utf-8').read()` 是 universal newlines，会把 `\r\n` 翻译成 `\n`，**于是任何文件数出来都是 CRLF=0、看着像 LF**——09-23 我就这样把两个真实 CRLF 的 `.vue` 误判成 LF，脚本里全用 `'\n'` 替换导致一次替换都没命中。正确判法：`open(p,'rb').read()` 数 `b.count(b'\r\n')` 与 `b.count(b'\n')`，相等且 >0 即 CRLF；写回用 `open(p,'wb')` + `newline=''`。
- **仓库行尾是混的，别按扩展名猜**（实测：`.vue` 21 CRLF / 4 LF，`.java` 208 / 46，`.sql` 3 / 31，`.js` 9 / 2）。所以落脚本的姿势是：先从原始字节探测出真实 eol，再 `old.replace('\n', eol)`，改完按统一 eol 归一化。
- **Edit 工具自身会跨行尾归一化**（在真实 CRLF 的 `.vue` 上直接 Edit 是能成功的），所以单点小改可以照常用 Edit；但脚本批量替换若不换算 eol 会**整批静默不命中**。

## 板块节点（systemType='B'）已下线 —— 2026-09-23
- 用户理由「大部分都失效」。查库确认 `t_node_event` 仅 3 条且**全是 A**，**B 类历史 0 条**，所以摘功能零数据成本。
- 此后**所有节点恒为 `systemType='A'`**：前端两处 payload 都写死 `'A'`；`NodeView.vue` 已无 A/B 任何 UI（tag / 筛选器 / 表格类型列 / 弹窗 radio / 天梯双方案卡全部删除，planA+planB 合并成一个 `plan`，createTwoPlans → createPlan）。
- **别再以为复算分 A/B 两套**：`NodeService / NodeController / NodeVO` 从来不读 systemType，只有 `NodeEvent` 实体与 `NodeSuggestVO` 带这个字段；真正的分支只在 `NodeSuggestService`（~20 处 `systemB`），现已加「已下线」Javadoc 标记但**代码保留**，因为 V34 破局改造要动同一个类，整体删除排在之后。
- `t_node_event.system_type` 列保留不删（V1 迁移不可改），语义退化为「恒 A 的遗留列」。
- 腾出来的三个位置留给破局的 `node_type`：卡片标签区、历史表「类型」列、筛选器区第一个下拉槽。

## 写文件：多行 / 含反斜杠的脚本一律用 Write 落文件，别用内联 `-e` / `-c`
- 本机 Git Bash 转义链会吞字符：`node -e` 里的 `\n` 会变成**字面 `/n`**（09-22 把 `daily-bar:/n` 写进 application.yml，启动直接 `ScannerException` 拒启）；`python -c` 里的 `\\` 也会被吃（`replace('\\\\','/')` → `SyntaxError: unterminated string literal`）。
- 正确姿势：Write 一份 .js/.py 到临时目录，再用绝对路径执行（脚本里还能顺手处理 BOM / CRLF）。
- 校验手段：yml 用 `python -c "import yaml;yaml.safe_load(...)"`（已装 pyyaml）；.vue 用 `%TEMP%/check_sfc.js`；Java 用 `mvn test`。

## WaveRider（连板周期选股策略引擎）
- PRD **v1.2** 在 `prd/短线连板周期选股策略引擎/短线连板周期选股策略引擎-PRD.html`，回测脚本与样本明细在同目录 `回测脚本/`。定位是 emotion_platform 的策略层模块，**尚未写任何代码**。
- 迁移预留 **V29**（7 张表：t_strategy / t_strategy_version / t_strategy_template / t_strategy_run / t_node_detect / t_candidate_stock / t_candidate_t1）+ **V30**（排班）。V28 已被 `position_quantity` 占用，V31 已被日 K 缓存表占用。
- 两条硬经验（做别的功能也适用）：①**节点/阈值类规则必须配「近 20 日触发次数」面板**，否则参数变成死分支没人知道（v1.0 三条规则就是这么失效的）；②**写死的绝对阈值几乎必然过期**，凡涉及市场量级的都要有「动态基准（近 N 日分位数）」模式。
- **情绪温度（`t_daily_record.temperature`）只能当择时/仓位旋钮，不能当选股因子**：实测它能解释的收益方差上限是**日间 11%**，日内个股离散占 89%。温度与它的原料「涨停家数」相关 0.674，而涨停家数单独用相关性(+0.396)反而高于温度(+0.280)。温度含人工填报子指标，实测 19 个交易日仅 14 天有值 → 不能作为候选池产出的前置条件。
- **个股因子实测 IC**（226 样本）：封单/成交额 **+0.308**（最强）＞ 成交额 **−0.285**（**负向**，越小越好）＞ 封单额 +0.222 ＞ 首次封板 +0.196 ＞ 情绪温度 +0.131 ＞ **连板数 +0.100（最弱）** ＞ 宽度分 b +0.001。→ `filter_min_amount=1亿` 方向是反的（已改 0.5 亿 + `MARK`）；连板数几乎无区分度。
- **第四类节点「空间破局 space_break」** 专项 PRD 在同目录 `空间破局节点-PRD.html`（v0.1 草案）。节点体系从 3 类扩到 5 个 type：`start 1.0 > space_break_next 0.9 > switch 0.8 > diverge 0.6 > space_break 0`。
  - **命名口径：全面禁用「普通 / 常规 / 其他节点」这类反义定义**（只说它不是什么，且每加一类 node_type 词义就变一次）。按**两个正交轴**描述：①**类型轴**＝5 个具体 node_type，策略未识别即 `null`，UI 显示「未识别」；②**来源轴**（正交、不参与分类）＝`人工节点`→`t_node_event` / `策略节点`→`t_node_detect`，两表互不写入。统称用方向不用否定：**周期节点 phase node**（启动/分歧/切换，横向＝周期第几拍）对 **空间节点 space node**（破局日/破局次日，纵向＝天花板在哪）。已写进 PRD §2 术语（第 10、11 条）与 §2 命名约定 callout。
  - **破局日 = 空间板断板日 = 观察日（0 候选，四类节点里唯一不产票的）**；**破局次日 = 修复日 = 唯一出手日**。别再按「突破前高那天」理解，那是错的定义。
  - 破的是**高度**，不是题材也不是情绪：切换日可高度不变（横移）、分歧日可高度还在、启动日可高度很低，三者都覆盖不了「天花板在哪」。
  - 断板形态**按三池归属判定即可**（DT=A杀否决 / ZB=炸板断板 / 三池均无=温和断板），不必依赖日 K。
  - 迁移版本用 **V34**（V29 策略主表、V30 排班、V31 日K缓存、V32 qfq版本、**V33 tdx行业概念数据**均已占）。

## 展示口径：区分「未评」与「真的 0」
- 后端子项 VO 里的**原生 int**（如 `PressureBlock.survCount/survNuke`）在未评（`score == null`，如监管事件窗为空）时回落成 0，前端 `xx ?? '—'` 拦不住，页面会把「不知道」印成「0 家」「0 只」。
- 判据统一用 `score == null` ＝整支未评；`survAvailable=true` 且确实 0 家时 score 仍有值。HighEcoView 已落成 `isRated()/statVal()/nukeText()` 三个 helper。
