# D5 高位生态（阵眼·抱团·监管）融合维度 实施方案

> 范围：emotion-server 后端（Vue 前端 emotion-web 为独立仓库，不在本次工作区，**不在本方案范围**）。
> 依据：`D5 · 高位生态（阵眼 · 抱团 · 监管）· 融合版 PRD` v2.0 + v2.1 阵眼人工化补丁。

## 一、仓库调研结论

### 1. 打分引擎现状
- [BoardScoreCalculator.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/util/BoardScoreCalculator.java)：纯函数引擎，配置树 `ScoringTree`（dim→sub→leaf）由 [ScoringModelStore.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/ScoringModelStore.java) 从 `t_scoring_dim/sub/rule` 装配，`builtinTree()` 为无库兜底；二者由 [ScoringModelSeedParityTest.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/test/java/com/emotion/util/ScoringModelSeedParityTest.java) 逐字段钉死。
- 现行五维 `five_dim_v2`：market 0.25 / theme_main 0.20 / board 0.25 / first 0.15 / **anchor 0.15（=龙头分工五键 dragon_zong_long…，PrdMetricsService 算好的 0-100 分走 MANUAL 直读）**。
- 维分落库在 [DailyRecordService.applyFiveDimScore](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/DailyRecordService.java#L235-L271)，按固定 dimKey 写 5 列 `score_market/score_theme_main/score_board/score_first/score_anchor`。
- 结构信号 `detectSignals`（5 个）与强制退潮 `detectForcedEbb`（4 条）已在引擎内，是 D5 交叉信号/风控的现成挂载点。
- D2 有 `dragon_misalign` ×0.9 后处理（`applyThemeMainGuards`），D3 有同名维分闸门 ×0.9（`applyBoardCalibration`）——PRD「去重原则」要求两处扣分移除、信号保留。

### 2. 阵眼现状（v2.1 补丁的大部分前提已具备）
- [t_anchor](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/resources/schema.sql#L295-L310) **已是人工配置 + start_date/end_date（NULL=长期）+ user_id**，[AnchorService.listInPosition](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/AnchorService.java#L39-L46) 已有在位判定，CRUD 接口 `/api/anchors` 齐备。
- 差异仅在 role：现为 `CYCLE/LEADER` 两值；PRD 要 `总龙头/分支龙/补涨龙/反包龙`（权 0.5/0.2/0.2/0.1）。
- 旧第 8 维 [AnchorMetricsService](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/AnchorMetricsService.java)（日 K 跨度、多只取最差）仍服务旧温度链路与阵眼卡片，**保留不动**。

### 3. 异动监管现状
- [t_surveillance](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/resources/schema.sql#L313-L327) 只存事件；[SurveillanceService](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/SurveillanceService.java) 查时按交易日历现推监管期；[SurveillanceKind](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/market/SurveillanceKind.java)：**ZD=3 日（PRD 要 5 日）、SEVERE/EXCH=10 日（一致）**；ZD 只展示不进分。
- `listOn(date, scope)` 一次给齐在列名单 + 当日涨跌（`SurvivalMember.pct`、events、scored 旗标）；`vo()` 已算 SEVERE/EXCH 溢价均值。当前供旧第 9 维使用，**不进五维树**。
- 上游预算约束：`listOn` 有网络开销（1 次日历日 K + 在列各股日 K/报价），`ScoreContextService.forDate` 当前每日已调一次。

### 4. 抱团取数现状
- [LadderMetricsService](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/LadderMetricsService.java) 已有：当日 ZT/ZB 池、昨 ZT 池、按板高分组、跨日 code 匹配晋级率、四层划界（`hsplit/layerIndex`）、封板率；`t_premium_tier` 分档溢价经 PremiumTierStore 读入 ScoreInputs。
- [MarketStock](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/entity/MarketStock.java) 具备 D5 所需全部字段：consecutive / sealAmount / amount / firstSealTime / breakCount / bigLoss / pool(ZT/DT/ZB) / industry / changePct。
- [PrdMetricsService.Snapshot](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/PrdMetricsService.java#L91-L121) 已产出 mainIndustry（日内核心）、maxBoard=H、zongLong 及五态所需的昨池数据。

### 5. 测试约束
- Golden 基线 [RecalcGoldenTest](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/test/java/com/emotion/service/RecalcGoldenTest.java) **只走旧 9 维 TemperatureCalculator，不覆盖五维引擎**——调五维权重/结构不动 golden。
- 需同步改：BoardScoreCalculatorTest（anchor 维结构、misalign 乘数、forced ebb）、DailyRecordServiceFiveDimTest（scoreAnchor/dragon 键）、SurveillanceServiceTest（ZD 窗口）、PrdMetricsServiceTest（若停发 dragon_* 键）、ScoringModelSeedParityTest（随 builtinTree 自动对齐，但需跑通）。

## 二、关键设计决策

1. **原地演进 five_dim_v2，不新建模型**（沿用 2026-09-11/12 的 UPDATE 迁移惯例）。D5：dimKey `anchor`→`high`，label「高位生态」，record_column 新列 `score_high`，权重 **market 0.22 / theme_main 0.18 / board 0.22 / first 0.13 / high 0.25**。旧 `score_anchor` 列冻结留痕。
2. **阵眼身份只认人工配置**（PRD v2.1）：无在位阵眼 → 子项1 整支未评（剔出分母，不兜 0），不再自动取最高板。role 扩展 `ZONG/FENZHI/BUZHANG/FANBAO`；存量 `CYCLE/LEADER` 一律按 ZONG（权 0.5）参与，旧第 8 维语义不变。不新增 status 列（end_date 已覆盖停用），即采用 PRD 方案 A 的最小版。
3. **ZD 窗口 3→5 个交易日**（仅改枚举常量与注释，无需回补数据；ZD 仍只展示不进分）。
4. **监管取数全程只调一次 `listOn`**：D5 压制/反馈与旧第 9 维共享同一份在列名单（重构 ScoreContextService 内部分装），不增加上游请求。
5. **叶子分数在取数层预算好**（与 dragon_* 同模式）：阵眼四子项、梯队断层等复合判据产出 0-100 走 MANUAL 叶；原始比率（家数比/封单比/占比/家数）走 BAND_LADDER 叶；监管反馈五态走新 STRATEGY `d5_feedback`。
6. **高位口径**：`highThreshold = H>=5 ? 5 : max(3,H-1)`（PRD）；高位家数占比分母取**连板（≥2板）家数**（PRD 未写明，取该口径使 20-40% 健康档有现实意义，落库 note 注明）。
7. 交叉信号与强制风控用 metrics 旗标键（`d5_sig_*` / `d5_force_*`）由取数层算、引擎翻译成中文标签——与现有 `dragon_misalign` 控制量同模式，引擎保持纯函数。
8. `t_d5_daily` 快照表**不建**（PRD 标「可选」；维分已随 t_daily_record 落库）。`t_anchor` 累计涨幅等生命周期展示优先用池内行，复杂日 K 指标复用既有 `/api/anchors` 返回，D5 接口不重复拉日 K。
9. 旧 `dragon_*` 五键 metrics 停发（快照内部 zongLongAction 等保留给主线生命周期/轮动信号用）；`manual_anchor_supervision_discount` 维持现状（本就无叶子消费），PRD 去重表中「阵眼监管折扣」自然失效。

## 三、文件与模块

### schema（[schema.sql](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/resources/schema.sql)）
- 新种子段（新库直取 v3 结构）：改 `@fid2` 五条 dim 权重/末行换 `high`；删 anchor 的 dragon sub/rule 种子，插 high 维 4 子项树；模型 name 更新为「五维双层情绪模型 v3(D5融合)」（model_key 仍 five_dim_v2，active=1）。
- 存量迁移段（幂等）：`t_daily_record` 加 `score_high DECIMAL(6,2)`；UPDATE 四个 dim 权重；UPDATE anchor 行 dim_key='high'/label/record_column/weight=0.25；DELETE+INSERT high 维全部 sub/rule；`t_anchor.role` 注释 MODIFY 扩四值。

### 引擎（util）
- [BoardScoreCalculator.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/util/BoardScoreCalculator.java)：`builtinTree()` 换权 + `highEcoDim()`；新增 STRATEGY `d5_feedback`；`detectSignals` 增 7 个 D5 信号；`detectForcedEbb` 增 2 条；删 D2 misalign ×0.9、删 D3 misalign 闸门（保留错位信号输出）。
- 纯判据类新增 `com.emotion.util.HighEcoMetrics`（或放 market 包）：五态行为、各档打分、信号/风控判定，**静态纯函数**供单测。

### 取数（service/market）
- 新增 `HighEcoMetricsService`：DB 读当天三池 + 昨 ZT/ZB、t_anchor 在位阵眼、PremiumTier、Surveillance 名单，输出 metrics 键 + 结构化 HighEcoVO 原料；`aggregate(...)` 纯函数化。
- 改 [ScoreContextService.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/ScoreContextService.java)：监管名单取一次共享；接入 HighEcoMetricsService；保留 PrdMetrics Snapshot 复用 mainIndustry/H。
- 改 [SurveillanceKind.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/market/SurveillanceKind.java)：ZD=5 及注释。
- 改 [AnchorService.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/service/AnchorService.java)：role 白名单加 ZONG/FENZHI/BUZHANG/FANBAO，CYCLE/LEADER 保留兼容；新增 role→权重映射（供取数层）。

### 落库 / 接口
- [DailyRecord.java](file:///Users/gaoying/Desktop/study/股市/code/emotion-server/src/main/java/com/emotion/entity/DailyRecord.java) + DailyRecordService：写 `score_high`（score_anchor 不再写新值，留旧值）。
- 新增 `vo/HighEcoVO.java`（对齐 PRD 第十节 JSON：date/H/score/level/anchor/coalition/pressure/feedback/monitor_pool/signals）与 `controller/HighEcoController.java`：`GET /api/d5/high?date=`（登录态，阵眼按用户）。

### 测试
- 新增 `HighEcoMetricsServiceTest`（fixture 喂池/阵眼/名单：五态、各档、六信号、两风控、多阵眼加权、无阵眼=未评）。
- 改 BoardScoreCalculatorTest / DailyRecordServiceFiveDimTest / SurveillanceServiceTest / PrdMetricsServiceTest / ScoreContextServiceTest。

## 四、D5 指标落地口径（取数层 → 引擎叶子）

子项1 阵眼个体 0.35（多阵眼按 role 权 0.5/0.2/0.2/0.1 加权；仅算今日有行情归属的在位阵眼）：
- 行为 0.40（MANUAL `d5a_action`）：昨ZT且今 consecutive+1=晋级100；昨非ZT今ZT=反包80；今ZB或(非ZT非DT)=断板20；今DT或big_loss=核按钮0；其余抗跌60。
- 高度 0.25（`d5a_height`）：今板（断板取昨板）vs H：=H 100 / H-1 80 / H-2 60 / 其余30。
- 封板 0.20（`d5a_seal`）：ZT 且 break_count=0 且 first_seal_time≤093000=一字100；ZT 开板回封=80；ZT 其余=80；ZB=40；DT/核按钮=0。
- 一致性 0.15（`d5a_consist`）：阵眼 industry==mainIndustry=100，否则60。

子项2 抱团资金 0.30：结构 0.60（高位家数占比 `d5c_ratio` 30%、封单集中度 `d5c_seal` 25%、空间板唯一数 `d5c_top` 25%、梯队 `d5c_tier` MANUAL 20%）；强度 0.40（高位溢价 `d5c_prem` 50%、高位晋级率 `d5c_jr` 50%）。档位逐字按 PRD 四章子表。

子项3 监管压制 0.20：家数 `d5p_count`（0→100/≤2→80/≤5→55/≤10→30/>10→10）、高位监管占比 `d5p_high_ratio`（0%→100/≤30%→70/≤60%→40/>60%→15）、板块扩散 `d5p_spread`（0→100/1→85/≤3→50/>3→20）。窗口无事件=整支未评（不是 100）。

子项4 监管反馈 0.15：STRATEGY `d5_feedback`，读 `d5f_nuke`/`d5f_avg`：nuke≥1→0；均涨>5→70；>0→85；>-7→50；其余25；全缺价=未评。

信号（写入 signalFlags）：抱团瓦解前兆 / 死亡结构-抱团崩塌 / 监管无效-情绪亢奋 / 监管生效-退潮加速 / 龙头与主线错位 / 板块级监管压制 / 龙头易主（含阵眼走弱、阵眼失效三档）。
强制退潮新增：①空间板处于 SEVERE/EXCH 且当日断板/核按钮；②监管股核按钮≥1 且空间板唯一。

## 五、实施步骤（依赖序）

1. ZD=5 + 注释，改 SurveillanceServiceTest 窗口断言。
2. schema.sql：score_high 列、role 扩展、high 维新种子 + 存量迁移段。
3. AnchorService role 白单/权重；Anchor 实体注释。
4. HighEco 纯判据类（行为/档位/信号/风控）+ 单测。
5. HighEcoMetricsService（DB 聚合 + 纯 aggregate）+ 单测。
6. BoardScoreCalculator：新树/新 STRATEGY/信号/风控/删两处 misalign 乘数；改其单测。
7. ScoreContextService：名单一次取齐 + 接入 D5 metrics（含 Prd Snapshot 复用）；改其单测。
8. DailyRecord(scoreHigh) + applyFiveDimScore + FiveDim 测试；停发 dragon_* 并改 PrdMetricsServiceTest。
9. HighEcoVO + `GET /api/d5/high`。
10. 跑 ScoringModelSeedParityTest 钉种子一致；`mvn test` 全量；手工对 9/11 瑞尔特案例验数。

## 六、验证

- `mvn test` 全绿（golden 不受影响；若 five-dim 相关断言改动，逐一在提交说明列出原因）。
- Parity 测试通过 = builtinTree 与 schema @fid2 种子逐字段一致。
- 手测口径：9/11 瑞尔特（4板/家居/SEVERE/晋级/主线元件）D5 子项1≈94、错位信号在；构造死亡结构/监管无效各一个 fixture 断言信号与强制退潮。
- 接口：`/api/d5/high?date=2026-09-11` 返回结构对齐 PRD；未刷监管日期压制子项=未评而非 100。

## 七、风险与处理

- **权重变更改变历史分数**：五维分数是现算现存，历史行不变，直到触发 recalc-all；迁移说明里提示重算影响（旧 score_anchor 列留痕）。
- **监管名单网络开销**：共享 listOn，D5 不新增请求；名单取数异常时压制/反馈整支未评并写 note，不兜 0。
- **misalign 扣分移除影响 D2/D3 既有测试与分数**：属 PRD 明确去重，信号保留可观测；相关单测改为断言「不乘数、有信号」。
- **无在位人工阵眼时 D5 35% 缺席**：引擎按已评权重归一，维分仍可出（其余三子项 ≥0.65 权）；接口 note 提示去登记阵眼。
- **高位监管股行业缺失**（断板后不在池）：用近 10 日池内最近 industry 回填，取不到不计入扩散分组并在 note 标注。
