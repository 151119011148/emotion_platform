# emotion_platform 项目长期约定

## Flyway 迁移
- 目录 `emotion-server/src/main/resources/db/migration/`，`V{n}__{desc}.sql`。**已应用版本严禁改**（checksum 校验）。`schema.sql`＝V1–V24 历史参考，不再加内容。
- **flyway 锁 6.5.7**（非父 BOM 8.5.13），因库 MySQL 5.7.29。解析器**按分号切语句** → 禁用 `CREATE PROCEDURE/TRIGGER/BEGIN...END`（V26 炸 ERROR 1064）；幂等补列用 `SET @v=(SELECT COUNT(*) FROM information_schema...)` → `SET @ddl=IF(@v=0,'ALTER...','SELECT 0')` → `PREPARE/EXECUTE/DEALLOCATE`。
- 迁移失败留 `t_flyway_history` 的 `success=0` 脏行 → 启动报 `Validate failed: Detected failed migration`。修法＝删该行再幂等重放；**脚本没修好就删＝再插一条失败行**。
- 连库排障：`C:/Users/1/.workbuddy/binaries/python/envs/default/Scripts/python.exe` + pymysql（192.168.123.18，root/123456，emotion_dashboard）。**跑 python 必须清 `PYTHONHOME= PYTHONPATH=`**（否则 SRE module mismatch）。
- 已占版本：V28 / V31 日K缓存 / V32 qfq版本 / V33 tdx行业概念 / V34 空间破局 / V35 WaveRider 7表+模板 / V36 排班 / V37 / **V38 position_exit_price / V39 node_event_space_break_comment / V40 node_event_candidate_pool / V41+V42 都是 review_objective_scheduler_job**（41＝头部写 V41 的变体，42＝改名并改头部的变体，两者内容只差一行注释）。**V29/V30 是跳过的空号，不可回填**（out-of-order 报错）；**改版本号后必须清 `target/classes/db/migration/` 旧文件**（否则新旧同名文件都在 → 撞号）。
- **⚠️ 多人各自发版 → 版本漂移是常态，发版前必先验版本号对齐**（2026-09-29/30 连撞三次：本地两个 V38 → `Found more than one migration with version 38`；库已到 42 而本地 38 缺失 + 41 内容不同 → `Detected applied migration not resolved locally: 38` + `checksum mismatch for 41`）。Spring 起不来 → nginx 502，比 checksum 问题更致命。排查＝`SELECT installed_rank,version,description,checksum,success,installed_on FROM t_flyway_history ORDER BY installed_rank DESC LIMIT 10`（表是自定义的 `t_flyway_history`，不是 `flyway_schema_history`）对比本地 `db/migration/`。
- **正解＝按 checksum 反查归属，把本地文件改名到库里记录的版本号**（库里同一迁移被应用两次就各留一份文件，只差一行注释也要留）。**别用 `ignore-missing-migrations` 掩盖**：远端 `application-local.yml` 会被别人下次发版整份覆盖（实测隔天就没了），顶不住。补号时**逐字节复制**原文件才对得上 checksum。
- **checksum 可以自己算**（`scripts/flyway_checksum.py`）：Flyway 6.5.7 的算法＝逐行（\n/\r\n/\r 都算换行）去掉行尾换行符后按 UTF-8 喂 CRC32，**不把分隔符喂回去**，最后取有符号 32 位。用它对齐库里的 checksum 就能确定「本地哪个文件 = 库里哪个版本」，比猜快且不会错（2026-09-30 靠它一次修对 38/41/42）。

## 定时任务：进程内 Quartz，排班在库
- 三层：`t_scheduler_job`（人维护）→ `QRTZ_*`（**勿手改**）→ `t_scheduler_run`（留痕）。加任务＝`ManagedTask` bean + `t_scheduler_job` 插一行，重启或 `POST /api/scheduler/reload` 生效。
- 「这次不用做」返回 `TaskResult.skip(原因)`，别抛异常表达正常分支。不配 `spring.quartz.startup-delay`。**到点时后端必须开着**；没跑先看 `t_scheduler_run`。

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

## 前端深色主题
- 全站深色（body `#0f1419` / 卡片 `#1a2332` / hover `#22303f` / 边框 `#2d3748`）。**teleport 到 body 的浮层 scoped 够不到** → 写 `App.vue` 非 scoped `<style>`。
- 正解＝整块换语义变量（`--el-text-color-*`/`--el-border-color-*`/`--el-fill-color-*`/`--el-bg-color-overlay`/`--el-dialog-bg-color`）+ 覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`；硬编码角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）单独压。
- 交易日格子语义 class `day-non-trading`（划掉=休市）/ `day-future`（虚线圈=未到）由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**。
- `el-table` 有两处不走表格自有变量：①**展开行** `--el-table-expanded-cell-bg-color`（已设 `#16202e`，并压 `:hover` 否则闪烁）；②**`stripe` 斑马纹**走 `--el-fill-color-lighter`（默认白，已在 `App.vue` `.el-table` 补 `#1f2b3b`）。实害＝浅色文字掉进白底直接不可读。
- **全站输入框 / 告警条底色仍纯白**（`--el-fill-color-blank`、`--el-color-*-light-9` 未覆盖；`/review`、`/mainline`、`/waverider` 一致）→ 既有全站状态，改它＝全站换观感，需单独决策。

## el-table 列宽：弹性列会按 min-width 比例"吃撑"（2026-09-30 实测）
- 源码 `element-plus/es/components/table/src/table-layout.mjs`：没写 `width` 只写 `min-width` 的列＝flexColumn，
  `realWidth = minWidth + floor(minWidth × (bodyWidth − bodyMinWidth) / ΣminWidth)`，第一个弹性列再吃取整差额。
  **容器越宽，弹性列被放得越大**——宽屏下 min 150/240 的两列实测被撑到 340/544px，而内容只占 130~200px。
- `bodyWidth = table 根元素的 clientWidth` → **修法＝给表格包一层 `max-width` 的 div**（`WaveRiderView.vue` 的 `.tbl{max-width:1400px}`），
  富余留在表右侧，列不再膨胀；窗口窄于该值时自动 100%。**别靠调 min-width 解决**，比例分配下改小 min 只是把它压到窄屏才生效。
- 最小可容宽度＝Σ(固定列 width) + Σ(弹性列 min-width)，小于它才出横向滚动条。

## 阵眼（t_anchor）顺序：一律起爆日倒序
- `AnchorService.listInPosition/listOverlapping`：`orderByDesc(startDate), orderByDesc(id)`，服务层统一排（面板 / 节点页下拉 / 导出 md 共用）。

## 板块节点（systemType='B'）已下线 —— 2026-09-23
- `t_node_event` 仅 3 条且全 A → **所有节点恒 A**：前端 payload 写死 `'A'`，`NodeView.vue` 无 A/B UI。**别再以为复算分 A/B 两套**：`NodeService/NodeController/NodeVO` 从不读 systemType，分支只在 `NodeSuggestService`（~20 处 `systemB`，代码保留）。`system_type` 列保留＝恒 A 的遗留列。

## 本机构建验证 —— 详见同目录 `MEMORY-build-verify.md`
- Maven `C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd` + JDK `jdk1.8.0_251`；`mvn -DskipTests compile && mvn test`（全离线不连库，**2026-09-29 实测 514 用例全绿**；`-q` 会吞 surefire 汇总）。
- **改文件先按原始字节判行尾**（`open(p,'rb').read()` 数 `\r\n`）：仓库行尾是混的。**Edit 同一文件连发两次会互相覆盖** → 多处改动落脚本、每条 `old` 断言 `count == 1`。既有 Java 是 CRLF+中文，Read/Edit 当二进制拒掉 → **改既有 Java 必须落脚本**。多行/含反斜杠的脚本一律 Write 落文件。
- 校验：yml 用 `yaml.safe_load`，Java 用 `mvn test`，.vue 用 **`@vue/compiler-sfc` 的 parse+compileScript+compileTemplate**（仓库里没有 `check_vue_sfc.js`，别找）。
- **样式/视觉必须真渲染验证**：Edge `--headless=new --screenshot` + **必须用 `file:///` 绝对 URL**（相对路径不出图）。只过编译＝没验；别肉眼判缩略图颜色。

## 发版到阿里云 47.117.110.143 —— 用 `scripts/deploy_alinux.py`（paramiko 顶替缺失的 expect）
- 用法 `PYTHONIOENCODING=utf-8 PYTHONHOME= PYTHONPATH= EMOTION_SSH_PASS=密码 python scripts/deploy_alinux.py --host 47.117.110.143 --user root [--skip-frontend|--skip-backend]`。jar 版本号**自动 glob**（当前 `1.0.0`，不是 SNAPSHOT）。DB 密码＝服务器 MySQL root 密码（与 SSH 同）；远端 `application-local.yml` 只覆盖了 password 与 jwt.secret。
- 布局：jar `/opt/emotion/app/emotion-server.jar`、静默页 `/var/www/emotion-web`、服务 `emotion-server.service`（Restart=always，**启动失败会无限重启刷日志**）、nginx 反代 8080。
- **不做两件事**：不跑 `init_full.sql`（每表 `DROP TABLE IF EXISTS`）、不覆盖远端 yml 的 JWT（重生成会让全员掉线）。**服务器上没有源码目录、没有历史 jar 备份** → 覆盖前先备份，否则拿不回来（本次吃过：V39/V40 源文件永久丢失）。
- **探活别只用 8 秒**：Spring Boot 约 20 秒才 `Started EmotionApplication`，期间 `/api` 一律 502（首页 200 是 nginx 静态页，不代表后端活着）。要轮询到 `Started EmotionApplication` 或 `curl 127.0.0.1:8080/api/auth/login` 返回业务码。
- 端到端验证后端改动（不落库）：`POST /api/auth/login`（gaofeng/123456）拿 token → `GET /api/waverider/strategies` → `POST /api/waverider/run {"strategyId":1,"tradeDate":"...","dryRun":true}`；确认无误再 `dryRun:false` 真跑一次（**历史候选行不带新字段，只有重跑产出的行才有**）。

## WaveRider（连板周期选股引擎）—— 完整判据见同目录 `MEMORY-waverider.md`
- PRD v1.4 + 预览页 + 回测脚本在 `prd/短线连板周期选股策略引擎/`（预览页是 `06→07→08` 构建产物，**勿手改**）；实现＝V35/V36/V37 + `/api/waverider` + `views/WaveRiderView.vue`。
- **先说清买点再谈收益**：A=`close(T+1)/close(T)−1`（真实买点）｜B=`close(T+1)/open(T+1)−1`；同批 226 样本 A=+3.36% / B=−0.20%，差全在隔夜跳空。两口径**必须并排看**。
- **封单强度（封单额÷成交额）＝打板口径下唯一跨档单调的正向因子**：五档 A +1.71/+3.55/+4.37/+5.40/+7.56%、胜率 53→87%，B 口径单调递减 → `sort_by` 默认 `seal_strength` 降序，`seal_lock_ratio` 退化为标记线。**「买不进就剔除」已废**；`gap≤3%` 已样本外证伪，只作执行纪律。
- **⚠️「节点」一词有三个所指，别猜**：①**节点追踪** `/nodes` / `NodeView.vue` / `t_node_event`（其「节点票」是 `node_stock` 字段，格式 `名称(代码)`）；②阵眼 `t_anchor`；③PRD §5.3/5.4 的情绪周期转折日。曾按 ③ 实现整轮后全部回滚。
- 候选池「来自节点追踪」与「题材」都是**读侧瞬态不落库**（`nodeTags`/`tdxThemes`，`@TableField(exist=false)`）：节点三角色 `NODE_STOCK/ANCHOR/D0_CAND`（**名称必须去空白**，`stockCodeOf` 是解析 `名称(代码)` 的唯一口径）；题材取 `t_stock_concept`，热度子查询**必须 LEFT JOIN**（内连接会丢「该题材当日只有它一只涨停」的票），前端只铺前 3 + `+N`。
- **「警示」＝两个刻意分开的字段，别合并**：`alertFlag`（瞬态，`DUANDAO`＝一字断魂刀，说「买不进」→ 不扣分/不折仓/不改排序）vs `riskFlag`（落库，说「质地有风险」→ `build()` 里仓位折半）。理由：封单锁死恰是最强正向因子，当风险折半＝自相矛盾。判据唯一出处 `TiantiService.isDuanDao`；候选池走 `duanDaoCodes` + 私有 `poolOn()`（**故意不用 `listPool`**）。导出 md 给中文、csv 给机器码。
- 候选池前端：`代码/节点/身位` 三列已删，**「最高板/身位」标识全部摘掉**（用户认定 13/15 都是最高板、无区分度）；名称格只挂节点标签；`effect="plain"` 标签在深底是白药丸 → 改 `--el-tag-*` 并多套一层容器选择器压权重。
- **得分明细（2026-09-29 落地）**：后端 `WaveRiderEngine.scoreDetail()` 把四项分量写进 `filterDetailJson.score_breakdown`（`terms[{key,weight,raw,value,原始值…}]` + `max_board` + `ceiling` + `score`），前端**只展示不重算**（重算＝第二份真相）；老行没有这段 → 降级只显示总分 + 虚线空槽，重跑当日补齐。
- **起验证实例必须加 `SPRING_QUARTZ_AUTO_STARTUP=false`**（否则与 8080 的 Quartz 双跑、重复写 `t_strategy_run`）。`mvn spring-boot:run "-Dspring-boot.run.arguments=A B"` **传两个参数会炸** → 第二个用环境变量传。
- 回滚成组改动：脚本从原脚本源码截到 `ok = True` 前 `exec` 出 `EDITS`，**逆序**反向替换（new→old），逐条断言 `count==1`，任一未命中即中止。

## 持仓台账记账口径（2026-09-30 定）
- 「今日清仓」按**真实清仓日**记账：持仓与台账页把快照日（早于今天）的行翻成今日清仓时，保存拆两笔——旧日恢复持仓中、今天建清仓行（同代码并入）。要更正历史某天的清仓，去那天的复盘页改。
- t_position 每行是「那天收盘时这只票的状态」快照；清仓行的 trade_date=清仓发生日，生命周期页卖出日=最后一条今日清仓行的 trade_date。
