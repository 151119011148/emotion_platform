# 情绪周期仪表盘 · 数据库结构字典（schema-doc）

- **数据库**：`emotion_dashboard`（MySQL 8，`utf8mb4 / utf8mb4_unicode_ci`，全部表 `ENGINE=InnoDB`）
- **DDL 事实源**：[emotion-server/src/main/resources/schema.sql](src/main/resources/schema.sql)（幂等，可整文件重放）
- **本文数据来源**：类型/可空/键/索引用 live 库 `information_schema.COLUMNS|TABLES` 核对，字段含义取自 schema.sql 的 `COMMENT`
- **最近核对**：2026-09-11
- **表数量**：18 张

> 约定：下文类型省略 `bigint(20)`/`int(11)` 的显示宽度（MySQL 8 驱动回显，实际即 bigint/int）。
> 「行数」为 InnoDB 统计估算值，仅示量级。

---

## 0. 全局约定（改动前必读）

1. **NULL 表示「没录 / 未评」，绝不写 0 顶替。**
   - 盘面读数：`0 家跌停`、`0% 封板率` 都是会改变阶段判断的真实读数；`NULL` 才是"没取到"。
   - 打分：某子指标/整维 `NULL` = 未评，打分引擎把该维**从分母剔除**（未评 ≠ 0），既不兜 0 也不补惩罚值。
2. **公开数据不绑 `user_id`，私人台账才绑。**
   - 公开（谁复盘都该是同一个事实）：`t_market_stock` / `t_premium_tier` / `t_index_close` / `t_surveillance` / `t_stock` 及打分配置 4 表。
   - 私人：`t_daily_record` / `t_position` / `t_prediction` / `t_cycle` / `t_theme` / `t_leading_stock` / `t_anchor` / `t_node_event` / `t_user`。
   - 人工覆盖值（manual_*）只落在自己的 `t_daily_record` 行上，读时叠加到自动值上，**不污染公开表**。
3. **打分真源是 Java 引擎，不是打分表。** `t_scoring_*` 4 张表只做展示/追溯，引擎运行时不读它们；两者逐字对齐由 `ScoringModelSeedParityTest` 钉死。改引擎常量必须同步种子并跑该测试。
4. schema.sql 的迁移块必须**幂等可重放**，且 `INSERT` 一律保持 `INSERT IGNORE INTO … VALUES (…)` 形状（ParityTest 的解析器要求 anchor 后紧跟 `VALUES`，禁用 `INSERT…SELECT`）。

### 表总览

| 表 | 域 | 绑用户 | 估算行数 | 一句话 |
|---|---|:-:|--:|---|
| t_user | 账号 | — | 2 | 登录账号 |
| t_daily_record | 每日复盘 | ✓ | 138 | **核心**：每日全部读数/打分/阶段/复盘文本 |
| t_market_stock | 公开行情 | — | 339 | 涨停/跌停/炸板三池逐只明细 |
| t_premium_tier | 公开行情 | — | 9 | 各连板档「昨涨停今溢价」 |
| t_index_close | 公开行情 | — | 20 | 五大指数收盘 |
| t_stock | 基础数据 | — | 0 | A股代码名称总表 |
| t_surveillance | 监管 | — | 0 | 异动/严重异动/交易所监管事件 |
| t_cycle | 周期 | ✓ | 0 | 情绪周期（冰点→收尾） |
| t_theme | 周期 | ✓ | 0 | 主线题材 |
| t_leading_stock | 周期 | ✓ | 0 | 题材龙头分工 |
| t_anchor | 周期 | ✓ | 0 | 周期阵眼/总龙（第8维源） |
| t_node_event | 节点理论 | ✓ | 0 | D0/T+1 节点事件 |
| t_position | 交易台账 | ✓ | 0 | 每日持仓与纪律（复盘导入） |
| t_prediction | 交易台账 | ✓ | 0 | 盘前三路径预判与次日对答案 |
| t_scoring_model | 打分配置 | — | 3 | 打分模型登记 |
| t_scoring_dim | 打分配置 | — | 19 | 模型→维度与权重 |
| t_scoring_sub | 打分配置 | — | 71 | 维度→子指标→层 与取数键 |
| t_scoring_rule | 打分配置 | — | 364 | 各子指标阈值档位（展示/追溯） |

---

## 1. 账号

### t_user — 用户
| 列 | 类型 | 空 | 键/约束 | 含义 |
|---|---|:-:|---|---|
| id | bigint | 否 | PK 自增 | |
| username | varchar(50) | 否 | UNIQUE / idx_username | 登录名 |
| password | varchar(200) | 否 | | 密文密码 |
| nickname | varchar(50) | 是 | | 昵称 |
| created_at | datetime | 是 | | 创建时间 |

---

## 2. 每日复盘核心

### t_daily_record — 每日复盘记录（核心，76 列）
- **唯一键** `uk_user_date (user_id, trade_date)`；索引 `idx_trade_date(trade_date)`、`idx_user_id(user_id)`
- 一个用户一个交易日至多一行。下列除 id/user_id/trade_date 外**全部可空**。

**身份**

| 列 | 类型 | 含义 |
|---|---|---|
| id | bigint PK | |
| user_id | bigint | 用户 |
| trade_date | date | 交易日 |

**盘面客观读数（行情拉取 / md 导入）**

| 列 | 类型 | 含义 |
|---|---|---|
| max_consecutive_limit | int | 连板高度 H |
| limit_up_count | int | 涨停家数 |
| limit_down_count | int | 跌停家数（≥10 触发强制退潮） |
| up_count | int | 上涨家数（手填/md/后端取数；只展示对照，不参与打分） |
| down_count | int | 下跌家数（同上；广度 red_ratio 的源） |
| yesterday_limit_premium | decimal(5,2) | 昨日涨停今日溢价%(含首板)，仅展示 |
| premium_weighted | decimal(7,2) | 非首板三档加权合成溢价%，旧 score_premium 源，仅追溯 |
| broken_board_rate | decimal(5,2) | 炸板率%（次数口径：打开次数÷触板总次数） |
| sealed_home_rate | decimal(5,2) | 家数封板率%=涨停÷(涨停+炸板)，与炸板率非互补 |
| reseal_rate | decimal(5,2) | 回封率%=封住前曾打开的涨停÷(那些+炸板) |
| big_loss_count | int | 大面数 |
| total_volume | decimal(10,2) | 两市成交额(亿) |
| surv_count | int | 第9维进分家数（仅严重异动/交易所监管；0=拉过没有，NULL=没拉过） |
| surv_premium | decimal(7,2) | 进分监管股当日算术平均涨幅%，第9维原始值 |

**旧 9 维打分（-1~3，历史留痕，新引擎不再写）**

| 列 | 类型 | 含义 |
|---|---|---|
| score_height | tinyint | 连板高度(-1~3)，-1=真断龙（4板以上掉回首板） |
| score_premium | tinyint | 溢价(-1~3)，低/中/高动态归组加权 |
| score_breadth | tinyint | 涨跌停比(-1~3)，-1=跌停成片(≥2倍且≥20家) |
| score_broken | tinyint | 炸板维(-1~3)=炸板率/封板率/回封率三子项均 |
| score_loss | tinyint | 大面数(-1~3)，-1=>25 家 |
| score_volume | tinyint | 量能(0-3)，0 已到底(背离) |
| score_theme | tinyint | 主线明确度(0-3)，人工三档 |
| anchor_score | tinyint | 第8维阵眼当日反馈(0-3)，多只取最差 |
| anchor_note | varchar(300) | 阵眼打分依据中文串 |
| surv_note | varchar(500) | 监管股打分依据（含在列第几日） |
| broken_note | varchar(300) | 第4维三子项算式与出分 |

**旧口径子项人工覆盖（NULL=用自动值，清空即回退）**

| 列 | 类型 | 含义 |
|---|---|---|
| manual_sealed_home_rate | decimal(5,2) | 第4维手改·家数封板率% |
| manual_reseal_rate | decimal(5,2) | 第4维手改·回封率% |
| manual_premium_low_pct | decimal(7,2) | 第2维手改·低位组今日均涨幅% |
| manual_premium_mid_pct | decimal(7,2) | 第2维手改·中位组今日均涨幅% |
| manual_premium_high_pct | decimal(7,2) | 第2维手改·高位组今日均涨幅% |
| manual_anchor_score | tinyint | 第8维手改·阵眼反馈分(0~3) |
| manual_surv_count | int | 第9维手改·进分家数 |
| manual_surv_premium | decimal(7,2) | 第9维手改·进分组合均涨幅% |

**五维 v2 得分（five_dim_v2，0-100，NULL=整维剔分母）**

| 列 | 类型 | 含义 |
|---|---|---|
| score_market | decimal(6,2) | D1 大盘生态 |
| score_theme_main | decimal(6,2) | D2 主线明确度 |
| score_board | decimal(6,2) | D3 连板生态（含中位吹哨×0.8 后） |
| score_first | decimal(6,2) | D4 首板生态 |
| score_anchor | decimal(6,2) | D5 阵眼（勿与旧 anchor_score 混淆） |

**结构信号 / 强制退潮**

| 列 | 类型 | 含义 |
|---|---|---|
| signal_flags | varchar(200) | 结构信号标签，逗号分隔（中位吹哨/高位抱团/抱团瓦解前兆/高低切/全面退潮） |
| forced_ebb | tinyint | 1=命中硬条件，阶段直接判「退潮(强制)」，无视总分 |
| forced_ebb_reason | varchar(300) | 命中原因中文串 |

**五维手填输入（自动取数覆盖不到的子指标，NULL=未填=未评）**

| 列 | 类型 | 含义 |
|---|---|---|
| manual_sector_limit_up_count | int | 主线板块涨停数 |
| manual_sector_premium_pct | decimal(7,2) | 主线板块昨涨停今均溢价% |
| manual_ladder_complete_score | decimal(6,2) | 板块梯队完整性直接给分(0-100) |
| manual_theme_persistence_days | smallint | 主线连续活跃天数(≥3=持续，首日=新启动) |
| manual_top_high_turnover_pct | decimal(6,2) | 极高位龙头当日换手%（H≥7 爆量断板判据） |
| manual_first_premium_pct | decimal(7,2) | 首板次日均溢价%（board=1 未纳入取数时兜底） |
| manual_first_sealed_rate | decimal(5,2) | 首板封住/(封住+炸)%，D4 封板率 |
| manual_top_high_break | tinyint | 极高位是否爆量断板未回封(1是)，强制退潮条件4闸门 |
| manual_anchor_supervision_discount | decimal(3,2) | 阵眼监管折扣乘数(0-1)，空=不打折 |
| manual_amount_gather_pct | decimal(6,2) | v2 成交额聚集度%=主线成交额/两市 |

**汇总 / 阶段**

| 列 | 类型 | 含义 |
|---|---|---|
| total_score | int | 总分（已评维原始分之和） |
| temperature | decimal(5,1) | 情绪温度(-33.3~100) |
| prev_temperature | decimal(5,1) | 昨日温度 |
| scored_dims | tinyint | 参与打分维数(0-9)，<5 不出阶段 |
| stage | varchar(20) | 阶段（七主阶段之一；强制退潮时为「退潮(强制)」） |
| stage_seq | tinyint | 该阶段第几个回合（按回合递增不按天） |
| stage_phase | varchar(6) | 空=延续；反弹=退潮/分歧次日回升未回发酵线(55) |
| stage_direction | varchar(10) | 方向：上升/下降/横盘 |
| stage_overridden | tinyint | 是否手动覆盖阶段 |

**主线龙头 / 仓位 / 复盘文本**

| 列 | 类型 | 含义 |
|---|---|---|
| main_theme | varchar(100) | 当前主线题材 |
| leading_stock | varchar(50) | 总龙头 |
| leading_stock_status | varchar(20) | 龙头状态 |
| mid_cap_stock | varchar(50) | 中军 |
| my_position_pct | decimal(5,2) | 我的实际仓位%（私人，不参与打分） |
| rotation_note | text | 轮动观察 |
| review_note | text | 对答案 |
| tomorrow_plan | text | 明日计划 |
| review_md | mediumtext | 整篇复盘原文（含 meta 围栏），md 导入器写入 |
| doc_notes | text | 【已停用】各节判断 JSON，仅留历史值，无读写方 |
| compare_note | varchar(300) | 手记与系统读数对照，人工填，不参与打分 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间（ON UPDATE CURRENT_TIMESTAMP） |

---

## 3. 公开行情与基础数据（不绑用户）

### t_market_stock — 每日盘面个股明细（涨停/跌停/炸板三池）
- 唯一键 `uk_date_code_pool (trade_date, code, pool)`；索引 `idx_date_pool(trade_date,pool)`、`idx_date_bigloss(trade_date,big_loss)`
- 存的就是参与聚合计数的那批行，名单与卡面数字永远自洽。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| trade_date | date·MUL | 否 | 交易日 |
| code | varchar(6) | 否 | 6 位代码 |
| name | varchar(20) | 否 | 证券简称 |
| pool | varchar(4) | 否 | ZT=涨停 / DT=跌停 / ZB=炸板 |
| market | tinyint | 是 | 东财标识：1=沪 0=深(含北) |
| industry | varchar(20) | 是 | 行业板块(上游 hybk) |
| consecutive | int | 是 | 连板数 lbc（涨停池） |
| break_count | int | 是 | 日内开板次数 zbc（回封标记源） |
| change_pct | decimal(6,2) | 是 | 当日涨跌幅% |
| close_price | decimal(12,3) | 是 | 收盘价 |
| limit_price | decimal(12,3) | 是 | 涨停/跌停价 |
| pullback_pct | decimal(6,2) | 是 | 自涨停回撤%（炸板池） |
| big_loss | tinyint | 是 | 大面：回撤>7% 且收盘绿 |
| seal_amount | decimal(18,2) | 是 | 封单额(元)=东财 fund，收盘封单资金 |
| first_seal_time | int | 是 | 首次封板时间 HHMMSS(fbt)，判一字/T字 |
| last_seal_time | int | 是 | 最后封板时间 HHMMSS(lbt) |
| created_at | datetime | 是 | |

### t_premium_tier — 连板档「昨日涨停今日溢价」
- 唯一键 `uk_date_board (trade_date, board)`；索引 `idx_date_group(trade_date,group_key)`
- 首板不计入，2..8+ 逐档存；打分只用 LOW/MID/HIGH 三组。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| trade_date | date·MUL | 否 | 当日（衡量「昨日涨停池」今天赚不赚钱） |
| board | smallint | 否 | 昨日连板数 2..8，8 表示 8 及以上 |
| group_key | varchar(4) | 否 | LOW/MID/HIGH；**只写不读**，分组在读取时按 board 现算 |
| stock_count | int | 否 | 该档家数（取自昨日涨停池） |
| matched | int | 否 | 真正取到当日涨跌的家数（<stock_count 表示有股没拉到） |
| avg_pct | decimal(7,2) | 是 | 该档算术平均涨幅% |
| max_pct | decimal(7,2) | 是 | 最大涨幅% |
| min_pct | decimal(7,2) | 是 | 最小涨幅% |
| created_at | datetime | 是 | |

### t_index_close — 五大指数收盘
- 唯一键 `uk_date_index (trade_date, index_code)`；索引 `idx_index_date(index_code,trade_date)`
- 打分只用核心三指（上证 000001 / 深证 399001 / 创业板 399006）；科创50/北证50 仅展示不进分。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| trade_date | date·MUL | 否 | 交易日 |
| index_code | varchar(9)·MUL | 否 | 不带市场前缀的 6-9 位码（000001/399001/…） |
| index_name | varchar(20) | 否 | 指数名 |
| close_price | decimal(12,2) | 是 | 收盘点位 |
| change_pct | decimal(6,2) | 是 | 当日涨跌幅% |
| created_at | datetime | 是 | |

### t_stock — A股代码名称总表
- 唯一键 `uk_code(code)`；索引 `idx_name(name)`

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| code | varchar(6) UNI | 否 | 6 位股票代码 |
| name | varchar(20)·MUL | 否 | 证券简称 |
| market | tinyint | 否 | 1=沪 0=深(含北) |
| board | varchar(10) | 否 | 沪主板/深主板/创业板/科创板/北交所 |
| updated_at | datetime | 是 | ON UPDATE CURRENT_TIMESTAMP |

---

## 4. 监管（公开数据）

### t_surveillance — 异动监管事件
- 唯一键 `uk_code_art (stock_code, art_code)`；索引 `idx_code_date(stock_code,ann_date)`、`idx_kind_date(kind,ann_date)`
- 只存事件，监管期在查询时按交易日历现推（改窗口长度不必回补数据）。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| stock_code | varchar(6)·MUL | 否 | 股票代码 |
| stock_name | varchar(20) | 否 | 简称 |
| ann_date | date | 否 | 公告日 D0，监管期从这天起算(含当日) |
| kind | varchar(8)·MUL | 否 | ZD=异常波动 / SEVERE=严重异常波动 / EXCH=交易所监管(函/警示/处分) |
| title | varchar(200) | 否 | 公告标题 |
| column_code | varchar(24) | 是 | 上游类目码（异动固定 001002004007） |
| art_code | varchar(32) | 否 | 上游公告 ID（幂等键） |
| created_at | datetime | 是 | |

> 打分口径：仅 SEVERE / EXCH 进第9维；例行 ZD 只展示不进分。

---

## 5. 周期 / 题材 / 龙头 / 阵眼 / 节点（绑用户）

### t_cycle — 情绪周期
索引 `idx_user_id`、`idx_status`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| start_date | date | 否 | 周期起始日（冰点日） |
| end_date | date | 是 | 结束日（空=进行中） |
| max_temperature | decimal(5,1) | 是 | 本轮最高温度 |
| max_height | int | 是 | 本轮最高连板 |
| leading_stock | varchar(50) | 是 | 本轮总龙头 |
| main_theme | varchar(100) | 是 | 本轮主线 |
| status | varchar(10)·MUL | 是 | ONGOING / COMPLETED |
| created_at | datetime | 是 | |

### t_theme — 主线题材
索引 `idx_user_id`、`idx_cycle_id`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| cycle_id | bigint·MUL | 是 | 所属周期 |
| name | varchar(100) | 否 | 题材名称 |
| start_date | date | 是 | 启动日 |
| status | varchar(10) | 是 | 萌芽/确认/扩散/亢奋/退潮 |
| strength | int | 是 | 强度(0-100) |
| catalyst_hardness | tinyint | 是 | 催化硬度 1-5（5=政策/产业，1=Pure 题材），v2 迁移列 |
| created_at | datetime | 是 | |

### t_leading_stock — 题材龙头
索引 `idx_user_id`、`idx_theme_id`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| theme_id | bigint·MUL | 是 | 所属题材 |
| name | varchar(50) | 否 | 股票名称 |
| role | varchar(20) | 是 | 总龙头/中军/跟风/卡位/反包龙 |
| max_consecutive | int | 是 | 最高连板数 |
| start_date | date | 是 | 启动日 |
| status | varchar(20) | 是 | 当前状态 |
| created_at | datetime | 是 | |

### t_anchor — 周期阵眼 / 总龙（第 8 维来源）
唯一键 `uk_user_code_start (user_id, stock_code, start_date)`；索引 `idx_user_span(user_id,start_date,end_date)`。
跨度只存起止日，最高连板/回撤一律从日 K 现算，不手填。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint | 否 | 用户 |
| stock_code | varchar(6) | 否 | 6 位代码，名称由 t_stock 反查校验 |
| stock_name | varchar(20) | 否 | 简称 |
| role | varchar(10) | 否 | CYCLE=周期阵眼 / LEADER=周期总龙（默认 CYCLE） |
| cycle_tag | varchar(20) | 是 | 同轮归组标签，仅显示分组 |
| start_date | date | 否 | 跨度起点（起爆日或人工锚点日） |
| end_date | date | 是 | 跨度终点，NULL=仍在位 |
| note | varchar(200) | 是 | 备注 |
| created_at | datetime | 是 | |
| updated_at | datetime | 是 | ON UPDATE CURRENT_TIMESTAMP |

### t_node_event — 节点理论事件
索引 `idx_user_id`、`idx_status`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| cycle_id | bigint | 是 | 所属周期 |
| system_type | varchar(10) | 是 | A=市场总节点 / B=板块节点 |
| anchor_stock | varchar(50) | 是 | 锚定龙头 |
| anchor_max_board | int | 是 | 锚定龙头最高板数 |
| d0_date | date | 是 | D0 日期 |
| d0_candidates | text | 是 | D0 候选票(JSON) |
| t1_date | date | 是 | T+1 验证日 |
| t1_anchor_repack | tinyint | 是 | T+1 老龙是否反包 |
| t1_promotion_count | int | 是 | T+1 晋级数量 |
| t1_promotion_rate | decimal(5,2) | 是 | T+1 晋级率% |
| node_valid | tinyint | 是 | 节点是否有效 |
| node_stock | varchar(50) | 是 | 确认的节点票 |
| node_stock_max_board | int | 是 | 节点票最高板数 |
| filter_passed | tinyint | 是 | 前置过滤器是否通过 |
| filter_detail | text | 是 | 过滤器明细(JSON) |
| status | varchar(20)·MUL | 是 | 待验证/有效/失效 |
| status_note | varchar(300) | 是 | 状态来路一句话 |
| note | text | 是 | 备注 |
| created_at | datetime | 是 | |

---

## 6. 交易台账（绑用户，复盘 md 导入）

### t_position — 每日持仓与纪律台账
唯一键 `uk_user_date_code (user_id, trade_date, stock_code)`；索引 `idx_user_code_date`、`idx_user_discipline`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| trade_date | date | 否 | 交易日 |
| stock_code | varchar(6) | 否 | 6 位代码，导入经 t_stock 校验 |
| stock_name | varchar(20) | 否 | 以 t_stock 为准，不采信 md 里写的名字 |
| cost_price | decimal(12,3) | 是 | 成本价 |
| current_price | decimal(12,3) | 是 | 现价 |
| float_pct | decimal(7,2) | 是 | 浮动盈亏%，手记原值，不由成本/现价反推 |
| action | varchar(60) | 是 | 今日实际动作 |
| planned_action | varchar(60) | 是 | 按纪律应做的动作 |
| discipline | varchar(8) | 是 | 遵守/违约/待执行 |
| created_at | datetime | 是 | |

### t_prediction — 预判留痕与对答案
唯一键 `uk_user_date_kind_name (user_id, trade_date, kind, name)`；索引 `idx_user_kind_date`。
PLAN 与 ANSWER 不互相拷贝；命中率由次日 ANSWER 按名称 join 前一日 PLAN 现算。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| user_id | bigint·MUL | 否 | 用户 |
| trade_date | date | 否 | PLAN=下单那天；ANSWER=回写兑现那天 |
| kind | varchar(6) | 否 | PLAN=盘前三路径预判 / ANSWER=次日对答案 |
| name | varchar(40) | 否 | 路径名，跨日对齐只认名称（须每天复用） |
| prob | tinyint | 是 | PLAN 发生概率 0-100 |
| condition_text | varchar(300) | 是 | PLAN 触发条件原文 |
| result | varchar(8) | 是 | ANSWER：命中/落空/部分/违约 |
| result_note | varchar(300) | 是 | ANSWER 一句话依据 |
| created_at | datetime | 是 | |

---

## 7. 打分模型配置（平台全局，不绑用户；仅展示/追溯）

> 这 4 张表是 Java 引擎内置打分树（`BoardScoreCalculator` / `TemperatureCalculator`）的「镜像注册表」。
> 引擎打分**不读库**；表与引擎逐字一致由 `ScoringModelSeedParityTest` 强制。改任一方必须同步另一方。

### t_scoring_model — 打分模型登记（3 行）
唯一键 `uk_model_key(model_key)`；索引 `idx_active(active)`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| model_key | varchar(40) UNI | 否 | ultra_short(旧9维) / five_dim(中代) / **five_dim_v2(现行)** |
| name | varchar(50) | 否 | 模型名 |
| max_score | decimal(7,2) | 是 | 满分基准（v2=100） |
| active | tinyint | 否 | 1=启用（全局应仅 1 行=1，多了会 warn） |
| note | varchar(200) | 是 | 备注（含口径代际说明） |
| created_at / updated_at | datetime | 是 | |

### t_scoring_dim — 模型维度（19 行）
唯一键 `uk_model_dim (model_id, dim_key)`；索引 `idx_model_no(model_id,dim_no)`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| model_id | bigint·MUL | 否 | → t_scoring_model.id |
| dim_key | varchar(20) | 否 | 维度键：market/theme_main/board/first/anchor（及旧9维键） |
| dim_no | smallint | 否 | 维号 1..9 |
| label | varchar(30) | 否 | 维度中文名 |
| weight | decimal(4,2) | 否 | 维度权重 |
| sort_no | smallint | 是 | 排序号（可空） |
| record_column | varchar(30) | 是 | 映射 t_daily_record 的落库列（如 score_market） |
| rule_engine | varchar(20) | 否 | WEIGHTED_SUB_BANDS / SUBITEM_AVERAGE / WORST_OF_MANY / MANUAL_PASSTHROUGH / THRESHOLD_BAND 等 |
| note | varchar(200) | 是 | |
| created_at / updated_at | datetime | 是 | |

### t_scoring_sub — 子指标/层登记（71 行）
唯一键 `uk_model_dim_sub (model_id, dim_key, sub_key)`；索引 `idx_model_dim_parent(model_id,dim_key,parent_sub_key)`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| model_id | bigint·MUL | 否 | → t_scoring_model.id |
| dim_key | varchar(20) | 否 | 所属维度 |
| sub_key | varchar(30) | 否 | 子指标键（index_env/turnover_ratio/promo_low…） |
| parent_sub_key | varchar(30) | 否 | 父级子键，`'-'`=一级（MySQL 唯一键不允许 NULL，故用 '-'） |
| label | varchar(30) | 否 | 中文名 |
| weight | decimal(5,4) | 否 | 权重(0-1)，同一父级下子项之和=1 |
| scoring_kind | varchar(22) | 否 | WEIGHTED_SUM / BAND_LADDER / LAYER_WEIGHTED_BAND / STRATEGY / MANUAL |
| source_key | varchar(40) | 是 | BAND_LADDER/MANUAL→metrics 键；STRATEGY→策略名(index_env/limit_combo/board_anchor)；聚合类→NULL |
| sort_no | smallint | 是 | 排序 |
| note | varchar(200) | 是 | |
| created_at / updated_at | datetime | 是 | |

### t_scoring_rule — 阈值档位（约 364 行）
唯一键 `uk_dim_rule (model_id, dim_key, sub_key, rule_no)`；索引 `idx_model_dim(model_id,dim_key)`。

| 列 | 类型 | 空 | 含义 |
|---|---|:-:|---|
| id | bigint PK | 否 | |
| model_id | bigint·MUL | 否 | → t_scoring_model.id |
| dim_key | varchar(20) | 否 | 维度键（不存 dim_id，避免冗余漂移） |
| sub_key | varchar(30) | 否 | 子指标键（NOT NULL；MySQL 唯一键中 NULL 不去重，故用占位） |
| rule_no | smallint | 否 | 命中顺序（if-ladder 从上到下） |
| operator | varchar(12) | 否 | GTE/GT/LTE/LT/EQ/BETWEEN/ELSE；及 GUARD(前置，如中位吹哨×0.8)/GROUP(子档)/AGG(合成)/COMPOUND(策略展示) |
| threshold_low | decimal(10,2) | 是 | 阈值下限（BETWEEN/GROUP 用） |
| threshold_high | decimal(10,2) | 是 | 阈值上限 |
| score | tinyint | 是 | 命中分；GUARD/GROUP/AGG 行为 NULL |
| formula | varchar(160) | 是 | 人话判据/公式（含 OR、区间），引擎不读，仅供展示追溯 |
| note | varchar(200) | 是 | |
| created_at / updated_at | datetime | 是 | |

**最近变更（2026-09-11）**：five_dim 与 five_dim_v2 两模型的 `market / index_env` 新增
`rule_no=4  operator=COMPOUND  score=35  formula='三指全绿但均未破-1%(弱跌日)'`，
原 ELSE 60 顺延为 `rule_no=5`。对应引擎常量 `BoardScoreCalculator.INDEX_ENV_ALL_SOFT_DOWN=35`。

---

## 8. 主要表关系（逻辑，非物理外键）

- `t_user 1—N t_daily_record`（user_id）；每日记录按 `(user_id, trade_date)` 唯一。
- `t_user 1—N t_cycle 1—N t_theme 1—N t_leading_stock`（cycle_id / theme_id 软关联）。
- `t_cycle 1—N t_node_event`（cycle_id）；`t_cycle 1—N t_anchor`（靠 user_id + 起止日跨度，无 cycle_id）。
- `t_market_stock.code` / `t_anchor.stock_code` / `t_position.stock_code` / `t_surveillance.stock_code`
  逻辑上对应 `t_stock.code`（导入时校验，不建物理 FK）。
- 打分链路：`t_scoring_model 1—N t_scoring_dim 1—N t_scoring_sub`，叶子档位在 `t_scoring_rule`；
  打分结果落 `t_daily_record.score_*`，原始公开事实在 t_market_stock / t_premium_tier / t_index_close / t_surveillance。
- 全库**无物理外键约束**，关联靠应用层与唯一键保证，便于公开数据整批幂等重灌。
