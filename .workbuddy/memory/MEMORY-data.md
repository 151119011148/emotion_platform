# emotion_platform 取数与数据表边界（从 MEMORY.md 拆出，2026-09-30）

`MEMORY.md` 的伴生文件：那边只留索引与最容易踩的几条，**做取数、改表、上功能前先读本文件**。

## 行情取数：写入侧与读取侧是两条路
- 落库：`MarketDataService.compute()` → `stockPoolWriter`(t_market_stock)/t_premium_tier/t_zt_perf/t_index_close；**`t_market_daily` 的 upsert 挂在 `MarketController`**，不走 HTTP 的调用（定时任务）必须自己补。
- 读取：`snapshot(date,refresh)` **只查 JVM 内存缓存**；重启/淘汰/`refresh=true` 才重打上游。`t_market_daily` 的读者是打分 / NodeService / ReviewController，snapshot 自己从不读它。
- 走库读口：`/market/stocks`、`/premium-tiers`、`/indexes`。**必然打上游**：`/market/breadth`、`/market/daily-bars`、`/market/score-context`、`/d5/high`（逐只打腾讯日K，日志刷屏大头）、`/surveillance/refresh`。

## 日K取数：一律走 DailyBarService（落库缓存 + 保鲜期）
- 入口唯一 `DailyBarService.bars(symbol,start,end)`；**别再直接调 `tencent.dailyBars()`**。
- 两表（V31）：`t_daily_bar`（qfq 四价）+ `t_daily_bar_fetch`（留痕）。①命中只看留痕**不看行数**（停牌日永久缺行）；②qfq 除权整体重算历史价，留痕 30 天保鲜；③**近 3 个自然日一律回源且不留痕**。
- 只存四价不存涨跌幅（派生值落库＝两处真相）；落库时向前多要 `lead-days=20`。退路：`market.daily-bar.enabled=false` / `?refresh=1` / `evict(symbol)`。**它是缓存不是档案**。
- **并发 upsert 会死锁**（实测 4 次 `Deadlock found`：多只票同毫秒各自 `upsertBatch` → `(symbol,trade_date)` 插入意向锁互撞）。`store()` 已 try-catch（代价＝这几只下次仍回源）；留痕与 bar 同一 try，失败时**留痕也不写**，不会造出「留痕说有、行没有」的假覆盖。**同类先例** `SurveillanceService:182` 已串行写；`DailyBarService` 缺这层（修法＝串行写或死锁重试）。
- **qfq：pct 安全，绝对价与跨段拼接不安全**。应对（V32）＝行级 `fq_version`，版本不唯一即整段重拉 + 回源比对重叠日收盘价。

## 数据表能力边界（上功能前先确认）
- **`t_zt_perf` 只有 3 天**（09-14/09-18/09-21）：「昨日涨停股（**含首板**）今日表现」与 `prev_consecutive` 的唯一来源；与 `t_premium_tier`（不含首板、只到 8+ 档）别混。未回填就上线＝**规则永远不命中而非报错**，极难查。
- **`t_stock` 字典易空**：`GET /api/review/stocks/search` 只查它（搜索 SQL 已带 t_market_stock 兜底）。灌数：`POST /api/market/stock-dict/sync` 或 `node scripts/sync_stock_dict.js`；排班周一 08:30 `stock_dict_sync`。
- **展示型「当日涨跌幅」不能只从三池取**（三池＝涨停/炸板/跌停）。补数顺序（`HighEcoMetricsService.backfillAnchorChg`）：①日K `dailyBars(sym,date,date)` **只认 date 精确匹配那根**；②当天仍缺 → 批量 `quotes()`，同样校验 `quoteDate == date`。拿不到留 null，**绝不用相邻交易日顶替**。腾讯日K**个股**当天滞后、**指数**当天有；实时快照 `qt.gtimg.cn` 个股当天有。三池(东财 push2) ≠ 日K(腾讯)。此补数只填展示字段、不进 metrics。
- **区分「未评」与「真的 0」**：VO 原生 int 在未评（`score == null`）时回落 0，前端 `?? '—'` 拦不住 → 判据统一用 `score == null`。HighEcoView 已落 `isRated()/statVal()/nukeText()`。
- **`first_seal_time` 5/6 位混存**（`94536`/`104156`）→ 一律先 `str(v).zfill(6)`；旧解析只认 6 位会静默丢 192/226 条。
- **`t_stock.listed_at` 全空（0/5914）** → WaveRider「剔除次新（30 日内上市）」**静默不生效**（`isNewStock()` 恒 false、不报错）。要用先回填上市日；**空≠剔除**是有意设计，别当 bug 改。
- **通达信（海王星）口径**（V23/V33）：`t_industry`(145) + `t_industry_stock`(5581，二级行业) + `t_concept_board`(269，board_code=简称/board_name=全称/index_code=880xxx) + **`t_stock_concept`(46149，个股→题材)**。用户说「题材」默认指 `t_stock_concept`（`StockConcept` Javadoc 写的「东财 BKxxxx」是错的）。
  **⚠️ 雷**：`ConceptIndexService.rebuild()`（`POST /api/review/concept-index/rebuild`）会**先清空 `t_stock_concept` 再按东财 ~504 概念整盘重建** → 一点就冲掉通达信题材（行为未改，交用户决策）。
- **`t_market_stock.industry` 是东财行业且截断 4 字**（「房地产开」），58 值只 12 个命中通达信字典 → 别直接当「题材」展示。

## 阵眼（t_anchor）顺序：一律起爆日倒序
- `AnchorService.listInPosition/listOverlapping`：`orderByDesc(startDate), orderByDesc(id)`，服务层统一排（面板 / 节点页下拉 / 导出 md 共用）。

## 板块节点（systemType='B'）已下线 —— 2026-09-23
- `t_node_event` 仅 3 条且全 A → **所有节点恒 A**：前端 payload 写死 `'A'`，`NodeView.vue` 无 A/B UI。**别再以为复算分 A/B 两套**：`NodeService/NodeController/NodeVO` 从不读 systemType，分支只在 `NodeSuggestService`（~20 处 `systemB`，代码保留）。`system_type` 列保留＝恒 A 的遗留列。

## 持仓台账记账口径（2026-09-30 定）
- 「今日清仓」按**真实清仓日**记账：持仓与台账页把快照日（早于今天）的行翻成今日清仓时，保存拆两笔——旧日恢复持仓中、今天建清仓行（同代码并入）。要更正历史某天的清仓，去那天的复盘页改。
- t_position 每行是「那天收盘时这只票的状态」快照；清仓行的 trade_date=清仓发生日，生命周期页卖出日=最后一条今日清仓行的 trade_date。
