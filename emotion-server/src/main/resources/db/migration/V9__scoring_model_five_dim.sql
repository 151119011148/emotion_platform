-- Flyway migration V9: scoring model five dim
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 798-1018 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- =====================================================================================
-- ============ 五维双层模型 five_dim：新增中间层子指标表 t_scoring_sub + 全量种子 ============
-- =====================================================================================
-- 用户决定：直接替换 9 维引擎、真正数据驱动(权重+单指标 0-100 阈值从表里读出来打分)、尽量自动取数。
-- 与旧 ultra_short 的关系：five_dim 置 active=1，ultra_short active=0(旧种子/列保留不删，仅作历史留痕)。
-- t_scoring_rule.score 是 TINYINT(有符号 -128..127)，0~100 天然放得下，无需改列类型。
-- 引擎把 t_scoring_sub 装配成树(dim→sub→layer/grandchild)注入 ScoreInputs，按 scoring_kind 求值：
--   WEIGHTED_SUM         = Σ child.weight × eval(child)，未评 child 剔出分母(不兜 0)。
--   BAND_LADDER          = 读 metrics[source_key] 走本 sub 的 t_scoring_rule 阶梯(有序命中)。
--   LAYER_WEIGHTED_BAND  = 四层各 BAND_LADDER 出分再按层权重加权。
--   STRATEGY             = 命名算法在 Java(source_key 即策略名)，规则表里的 COMPOUND 行仅供展示/追溯，
--                          由 ScoringModelSeedParityTest 钉住"表登记分 == Java 常量"。
--   MANUAL               = 直接取 metrics[source_key] 夹到 0-100(定性子指标人工给分)。
-- 权重一律 0-1 小数。引擎用「已评子权重之和」归一化(Σw×分/Σw)，故权重和是否恰好=1 不影响维分落在 0-100：
--   顶层 5 维权重和=1(0.25/0.20/0.25/0.15/0.15)；连板四层 promo/premium/bigloss 内和=1(0.15/0.25/0.20/0.40)；
--   连板维 5 子按用户 spec 是 25/20/20/15/10(和=0.90)、首板 5 子 25/15/25/25/10(和=100)——连板的 0.90 靠归一化兜住，
--   全评时维分仍满 100，缺子时自动按剩余权重放大，不额外补 0。改任一层权重后请跑 ScoringModelSeedParityTest。

CREATE TABLE IF NOT EXISTS t_scoring_sub (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL COMMENT '所属模型，逻辑外键(t_scoring_model.id)',
    dim_key VARCHAR(20) NOT NULL COMMENT '挂在五维里的哪一维',
    sub_key VARCHAR(30) NOT NULL COMMENT '子指标键；连板维的四层用 promo_low/promo_mid/... 这类独立键',
    parent_sub_key VARCHAR(30) NOT NULL DEFAULT '-' COMMENT "'-'=维度直属的一级子指标；非-=挂在某复合子指标下的层或子叶(如连板·晋级·低)。刻意 NOT NULL：与 sub_key 同一幂等理由(MySQL UK 不约束 NULL)",
    label VARCHAR(30) NOT NULL,
    weight DECIMAL(5,4) NOT NULL COMMENT '在本父节点内的权重(0-1)。维内各子权重和=1；复合子下四层权重和=1；阵眼单叶=1',
    scoring_kind VARCHAR(22) NOT NULL COMMENT 'WEIGHTED_SUM / BAND_LADDER / LAYER_WEIGHTED_BAND / STRATEGY / MANUAL。算法体在 Java，本列标它走哪条求值',
    source_key VARCHAR(40) DEFAULT NULL COMMENT 'BAND_LADDER/MANUAL：去 ScoreInputs.metrics 取原始读数的键；STRATEGY：策略名(index_env/limit_combo/board_anchor)；WEIGHTED_SUM/LAYER_WEIGHTED_BAND：NULL(靠 children)',
    sort_no SMALLINT DEFAULT NULL COMMENT '展示序',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_dim_sub (model_id, dim_key, sub_key),
    INDEX idx_model_dim_parent (model_id, dim_key, parent_sub_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分模型子指标/四层登记：维→子→层 的两/三层权重与取数键(平台全局配置,不绑用户)';

-- ---- 模型：five_dim 生效，ultra_short 退役(active 全平台只应有一行 1) ----
INSERT IGNORE INTO t_scoring_model (model_key, name, max_score, active, note) VALUES
    ('five_dim', '五维双层情绪模型', 100.00, 1, '0-100 直加权：总分=Σ(维分×维权)。≥85高潮/60-84发酵/40-59混沌/<40退潮；命中强制退潮直接退潮(强制)');

-- 把旧的超短模型下线。这是"直接替换"的落地开关；重放无害(幂等)。
UPDATE t_scoring_model SET active = 0 WHERE model_key = 'ultra_short' AND active = 1;

SET @fid := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim');

-- ---- 五维(权重和=1.00) ----
INSERT IGNORE INTO t_scoring_dim (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note) VALUES
    (@fid,'market',    1,'大盘生态',  0.25, 1,'score_market',    'WEIGHTED_SUM','指数环境35/量能25/广度20/涨跌停20'),
    (@fid,'theme_main',2,'主线明确度',0.20, 2,'score_theme_main','WEIGHTED_SUM','板块涨停30/梯队完整30/板块溢价25/持续性15'),
    (@fid,'board',     3,'连板生态',  0.25, 3,'score_board',     'WEIGHTED_SUM','晋级25/溢价20/大面20/炸板质量15/数量高度10；命中中位吹哨整维×0.8'),
    (@fid,'first',     4,'首板生态',  0.15, 4,'score_first',     'WEIGHTED_SUM','首板数25/首板封板率15/首板溢价25/1进2晋级25/1进2大面10'),
    (@fid,'anchor',    5,'阵眼',      0.15, 5,'score_anchor',    'WEIGHTED_SUM','空间板/核心龙 100%(状态分×监管折扣)');

-- ---- 子指标 / 四层（parent_sub_key='-' 直属维；非 '-' 是挂在复合子下的层/叶）----
-- 大盘生态
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'market','index_env',   '-','指数环境',0.35,'STRATEGY','index_env',1,'三指涨跌幅及协同性'),
    (@fid,'market','turnover',    '-','量能',    0.25,'BAND_LADDER','turnover_ratio',2,'成交额/20日均值'),
    (@fid,'market','breadth',     '-','广度',    0.20,'BAND_LADDER','red_ratio',3,'红盘率=上涨家数/(涨+跌)'),
    (@fid,'market','limit_combo', '-','涨跌停',  0.20,'STRATEGY','limit_combo',4,'涨停/跌停两操作数组合');
-- 主线明确度
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'theme_main','sector_limit_up','-','板块涨停数',0.30,'BAND_LADDER','sector_limit_up_count',1,'第一主线涨停数(人工,industry≠题材)'),
    (@fid,'theme_main','ladder_complete','-','梯队完整性',0.30,'MANUAL','ladder_complete_score',2,'有无断层(人工直接给 0-100)'),
    (@fid,'theme_main','sector_premium', '-','板块溢价',  0.25,'BAND_LADDER','sector_premium_pct',3,'主线昨日涨停今均溢价(人工)'),
    (@fid,'theme_main','persistence',    '-','持续性',    0.15,'BAND_LADDER','persistence_days',4,'连续活跃天数(人工)');
-- 连板生态：五个一级子
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'board','promo',         '-','晋级结构',0.25,'LAYER_WEIGHTED_BAND',NULL,1,'四层晋级率各出分再按 0.15/0.25/0.20/0.40 加权'),
    (@fid,'board','premium',       '-','溢价结构',0.20,'LAYER_WEIGHTED_BAND',NULL,2,'四层昨日连板今溢价'),
    (@fid,'board','bigloss',       '-','大面结构',0.20,'LAYER_WEIGHTED_BAND',NULL,3,'四层大面家数'),
    (@fid,'board','broken_quality', '-','炸板质量',0.15,'WEIGHTED_SUM',NULL,4,'0.6×封板率分+0.4×回封率分'),
    (@fid,'board','count_height',  '-','数量高度',0.10,'BAND_LADDER','board_total_count',5,'连板总家数(最高板H并入)');
-- 连板生态：三复合子 × 四层（每层权重和=1）
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'board','promo_low',     'promo','低位晋级',0.15,'BAND_LADDER','jr_low',1,'2板(固定)'),
    (@fid,'board','promo_mid',     'promo','中位晋级',0.25,'BAND_LADDER','jr_mid',2,'3-4板 吹哨锚点'),
    (@fid,'board','promo_midhigh', 'promo','中高位晋级',0.20,'BAND_LADDER','jr_midhigh',3,'5~⌈H/2⌉'),
    (@fid,'board','promo_top',     'promo','极高位晋级',0.40,'BAND_LADDER','jr_top',4,'⌈H/2⌉+1~H'),
    (@fid,'board','premium_low',     'premium','低位溢价',0.15,'BAND_LADDER','prem_low',1,''),
    (@fid,'board','premium_mid',     'premium','中位溢价',0.25,'BAND_LADDER','prem_mid',2,''),
    (@fid,'board','premium_midhigh', 'premium','中高位溢价',0.20,'BAND_LADDER','prem_midhigh',3,''),
    (@fid,'board','premium_top',     'premium','极高位溢价',0.40,'BAND_LADDER','prem_top',4,''),
    (@fid,'board','bigloss_low',     'bigloss','低位大面',0.15,'BAND_LADDER','big_low',1,''),
    (@fid,'board','bigloss_mid',     'bigloss','中位大面',0.25,'BAND_LADDER','big_mid',2,''),
    (@fid,'board','bigloss_midhigh', 'bigloss','中高位大面',0.20,'BAND_LADDER','big_midhigh',3,''),
    (@fid,'board','bigloss_top',     'bigloss','极高位大面',0.40,'BAND_LADDER','big_top',4,''),
    (@fid,'board','bq_sealed',  'broken_quality','家数封板率',0.60,'BAND_LADDER','sealed_home_rate',1,''),
    (@fid,'board','bq_reseal',  'broken_quality','回封率',    0.40,'BAND_LADDER','reseal_rate',2,'');
-- 首板生态
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'first','first_count',   '-','首板数量',  0.25,'BAND_LADDER','first_count',1,''),
    (@fid,'first','first_sealed',  '-','首板封板率',0.15,'BAND_LADDER','first_sealed_rate',2,'首板封住/(封住+炸)'),
    (@fid,'first','first_premium', '-','首板溢价',  0.25,'BAND_LADDER','first_premium_pct',3,'首板次日均溢价(取数未含board=1时用 manual 兜)'),
    (@fid,'first','promo_1to2',    '-','1进2晋级',  0.25,'BAND_LADDER','first_promo_1to2_rate',4,''),
    (@fid,'first','big_1to2',      '-','1进2大面',  0.10,'BAND_LADDER','first_1to2_big_count',5,'');
-- 阵眼
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'anchor','core','-','空间板/核心龙',1.00,'STRATEGY','board_anchor',1,'状态分×监管折扣');

-- ---- 0-100 阈值阶梯(BAND_LADDER 子) + 策略 COMPOUND 展示行 ----
-- 大盘
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'market','turnover',1,'GTE',1.20,NULL,90,'成交额/20日均 >=1.2','spec锚点1.2/0.95-1.1/0.7；余档占位待定标'),
    (@fid,'market','turnover',2,'GTE',0.95,NULL,70,'>=0.95',''),
    (@fid,'market','turnover',3,'GTE',0.70,NULL,45,'>=0.70',''),
    (@fid,'market','turnover',4,'ELSE',NULL,NULL,25,'<0.70',''),
    (@fid,'market','breadth',1,'GTE',0.60,NULL,85,'红盘率 >=0.6','spec锚点0.6/0.4-0.5/0.2；中间占位'),
    (@fid,'market','breadth',2,'GTE',0.40,NULL,55,'>=0.4',''),
    (@fid,'market','breadth',3,'GTE',0.20,NULL,30,'>=0.2',''),
    (@fid,'market','breadth',4,'ELSE',NULL,NULL,10,'<0.2',''),
    (@fid,'market','index_env',1,'COMPOUND',1.00,NULL,100,'三指均涨 >1%','STRATEGY:算法在Java,此行供展示/Parity'),
    (@fid,'market','index_env',2,'COMPOUND',NULL,NULL,40,'两跌一红',''),
    (@fid,'market','index_env',3,'COMPOUND',-1.00,NULL,20,'三指跌 >1%(均<-1%)',''),
    (@fid,'market','index_env',4,'COMPOUND',NULL,NULL,35,'三指全绿但均未破-1%(弱跌日)','STRATEGY:三指均<0且均≥-1%,Java算'),
    (@fid,'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标'),
    (@fid,'market','limit_combo',1,'COMPOUND',80,0,95,'涨停>=80 且 跌停=0','STRATEGY:两操作数,Java算'),
    (@fid,'market','limit_combo',2,'COMPOUND',40,8,45,'涨停40~60 且 跌停5~8',''),
    (@fid,'market','limit_combo',3,'COMPOUND',NULL,20,5,'跌停>20',''),
    (@fid,'market','limit_combo',4,'ELSE',NULL,NULL,50,'其余','中间档占位');
-- 主线
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'theme_main','sector_limit_up',1,'GTE',15,NULL,90,'主线涨停数 >=15','spec锚点15/6-9/3；中间占位'),
    (@fid,'theme_main','sector_limit_up',2,'GTE',10,NULL,78,'>=10',''),
    (@fid,'theme_main','sector_limit_up',3,'GTE',6,NULL,65,'>=6',''),
    (@fid,'theme_main','sector_limit_up',4,'GTE',3,NULL,45,'>=3',''),
    (@fid,'theme_main','sector_limit_up',5,'ELSE',NULL,NULL,30,'<3',''),
    (@fid,'theme_main','sector_premium',1,'GT',3,NULL,90,'主线板块溢价 >3%',''),
    (@fid,'theme_main','sector_premium',2,'GTE',0,NULL,55,'>=0','中间占位'),
    (@fid,'theme_main','sector_premium',3,'ELSE',NULL,NULL,20,'<0',''),
    (@fid,'theme_main','persistence',1,'GTE',3,NULL,85,'连续活跃 >=3天',''),
    (@fid,'theme_main','persistence',2,'GTE',2,NULL,68,'=2天','中间占位'),
    (@fid,'theme_main','persistence',3,'EQ',1,NULL,50,'首日','');
-- 连板：晋级四层(各 60/40/25/15)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','promo_low',1,'GTE',60,NULL,95,'层晋级率 >=60%',''),(@fid,'board','promo_low',2,'GTE',40,NULL,80,'>=40',''),(@fid,'board','promo_low',3,'GTE',25,NULL,65,'>=25',''),(@fid,'board','promo_low',4,'GTE',15,NULL,45,'>=15',''),(@fid,'board','promo_low',5,'ELSE',NULL,NULL,20,'<15',''),
    (@fid,'board','promo_mid',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_mid',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_mid',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_mid',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_mid',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo_midhigh',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_midhigh',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_midhigh',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_midhigh',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_midhigh',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo_top',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_top',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_top',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_top',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_top',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo',0,'GUARD',NULL,NULL,NULL,'中位吹哨:JR中<15% 或 (Prem中<0 且 Big中>=3) → 连板总分×0.8','信号表另有结构信号定义');
-- 连板：溢价四层(>3/1-3/0-1/0~-1/-1~-3/<-3)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','premium_low',1,'GT',3,NULL,95,'层溢价 >3%',''),(@fid,'board','premium_low',2,'GTE',1,NULL,80,'1~3',''),(@fid,'board','premium_low',3,'GTE',0,NULL,65,'0~1',''),(@fid,'board','premium_low',4,'GTE',-1,NULL,45,'0~-1',''),(@fid,'board','premium_low',5,'GTE',-3,NULL,25,'-1~-3',''),(@fid,'board','premium_low',6,'ELSE',NULL,NULL,5,'<-3',''),
    (@fid,'board','premium_mid',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_mid',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_mid',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_mid',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_mid',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_mid',6,'ELSE',NULL,NULL,5,'',''),
    (@fid,'board','premium_midhigh',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_midhigh',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_midhigh',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_midhigh',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_midhigh',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_midhigh',6,'ELSE',NULL,NULL,5,'',''),
    (@fid,'board','premium_top',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_top',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_top',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_top',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_top',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_top',6,'ELSE',NULL,NULL,5,'','');
-- 连板：大面四层(0/1-2/3-5/5-10/>10)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','bigloss_low',1,'EQ',0,NULL,95,'大面家数 =0',''),(@fid,'board','bigloss_low',2,'LTE',2,NULL,80,'1~2',''),(@fid,'board','bigloss_low',3,'LTE',5,NULL,60,'3~5',''),(@fid,'board','bigloss_low',4,'LTE',10,NULL,35,'6~10',''),(@fid,'board','bigloss_low',5,'ELSE',NULL,NULL,10,'>10',''),
    (@fid,'board','bigloss_mid',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_mid',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_mid',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_mid',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_mid',5,'ELSE',NULL,NULL,10,'',''),
    (@fid,'board','bigloss_midhigh',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_midhigh',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_midhigh',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_midhigh',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_midhigh',5,'ELSE',NULL,NULL,10,'',''),
    (@fid,'board','bigloss_top',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_top',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_top',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_top',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_top',5,'ELSE',NULL,NULL,10,'','');
-- 连板：炸板质量两叶 + 数量高度
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','bq_sealed',1,'GTE',85,NULL,95,'封板率 >=85%','spec锚点85/45；中间占位'),(@fid,'board','bq_sealed',2,'GTE',70,NULL,80,'>=70',''),(@fid,'board','bq_sealed',3,'GTE',55,NULL,60,'>=55',''),(@fid,'board','bq_sealed',4,'GTE',45,NULL,40,'>=45',''),(@fid,'board','bq_sealed',5,'ELSE',NULL,NULL,15,'<45',''),
    (@fid,'board','bq_reseal',1,'GTE',75,NULL,95,'回封率 >=75%','档借封板率形态,待定标'),(@fid,'board','bq_reseal',2,'GTE',60,NULL,80,'>=60',''),(@fid,'board','bq_reseal',3,'GTE',45,NULL,60,'>=45',''),(@fid,'board','bq_reseal',4,'GTE',30,NULL,40,'>=30',''),(@fid,'board','bq_reseal',5,'ELSE',NULL,NULL,15,'<30',''),
    (@fid,'board','broken_quality',0,'AGG',NULL,NULL,NULL,'0.6×封板率分 + 0.4×回封率分',''),
    (@fid,'board','count_height',1,'GTE',25,NULL,95,'连板总家数 >=25','spec仅给>=25=95锚点,余占位;H并入'),(@fid,'board','count_height',2,'GTE',15,NULL,80,'>=15',''),(@fid,'board','count_height',3,'GTE',8,NULL,60,'>=8',''),(@fid,'board','count_height',4,'GTE',4,NULL,40,'>=4',''),(@fid,'board','count_height',5,'ELSE',NULL,NULL,20,'<4','');
-- 首板
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'first','first_count',1,'GTE',60,NULL,95,'首板家数 >=60','spec锚点60/10'),(@fid,'first','first_count',2,'GTE',40,NULL,80,'>=40',''),(@fid,'first','first_count',3,'GTE',25,NULL,60,'>=25',''),(@fid,'first','first_count',4,'GTE',10,NULL,40,'>=10',''),(@fid,'first','first_count',5,'ELSE',NULL,NULL,15,'<10',''),
    (@fid,'first','first_sealed',1,'GTE',80,NULL,95,'首板封板率 >=80%','spec锚点80/40'),(@fid,'first','first_sealed',2,'GTE',65,NULL,80,'>=65',''),(@fid,'first','first_sealed',3,'GTE',50,NULL,60,'>=50',''),(@fid,'first','first_sealed',4,'GTE',40,NULL,40,'>=40',''),(@fid,'first','first_sealed',5,'ELSE',NULL,NULL,15,'<40',''),
    (@fid,'first','first_premium',1,'GT',3,NULL,95,'首板溢价 >3%','spec锚点3/-1'),(@fid,'first','first_premium',2,'GTE',1,NULL,75,'1~3',''),(@fid,'first','first_premium',3,'GTE',-1,NULL,50,'-1~1',''),(@fid,'first','first_premium',4,'ELSE',NULL,NULL,25,'<-1',''),
    (@fid,'first','promo_1to2',1,'GTE',25,NULL,95,'1进2晋级率 >=25%','spec锚点25/5'),(@fid,'first','promo_1to2',2,'GTE',15,NULL,75,'>=15',''),(@fid,'first','promo_1to2',3,'GTE',5,NULL,45,'>=5',''),(@fid,'first','promo_1to2',4,'ELSE',NULL,NULL,20,'<5',''),
    (@fid,'first','big_1to2',1,'EQ',0,NULL,95,'1进2大面 =0','spec锚点0/10'),(@fid,'first','big_1to2',2,'LTE',3,NULL,75,'1~3',''),(@fid,'first','big_1to2',3,'LTE',6,NULL,45,'4~6',''),(@fid,'first','big_1to2',4,'LTE',10,NULL,25,'7~10',''),(@fid,'first','big_1to2',5,'ELSE',NULL,NULL,10,'>10','');
-- 阵眼(策略 COMPOUND 展示行)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'anchor','core',1,'COMPOUND',NULL,NULL,95,'一字/涨停封住','STRATEGY:board_anchor,Java算'),
    (@fid,'anchor','core',2,'COMPOUND',NULL,NULL,55,'爆量断板',''),
    (@fid,'anchor','core',3,'COMPOUND',NULL,NULL,0,'核按钮/收盘跌停(同时触发强制退潮)',''),
    (@fid,'anchor','core',0,'GUARD',NULL,NULL,NULL,'监管折扣:命中 SEVERE/EXCH 时上述状态分×discount','');

-- ============ 存量库迁移(2026-09-10 五维双层模型)：可重复执行，缺哪列补哪列 ============
-- 上面全是 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，对已经建好的库一个字都不改。
-- t_daily_record 早在九维时代就建好了，所以五维这 17 列只能在这里补——这是整个迁移里唯一
-- 不能靠"重放 schema.sql"自动完成的一步。
-- 漏掉它的现场表现很有欺骗性：每条碰 t_daily_record 的 SQL 都在
-- Unknown column 'score_market' 上炸掉，而后端用 HTTP 200 包 code=400 返回，
-- 仪表盘只会把红条一闪后显示"暂无数据 + 五张未评卡"，看起来像前端没接上。
-- 所以这里用 information_schema 先查后拼 ALTER：只补真缺的列，重放无害。
SET SESSION group_concat_max_len = 8192;

SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @five_dim_adds
  FROM (
  SELECT          1 ord_no, 'score_market' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·大盘生态分(0-100)''' col_ddl
  UNION ALL SELECT 2 ord_no, 'score_theme_main' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·主线明确度分(0-100)''' col_ddl
  UNION ALL SELECT 3 ord_no, 'score_board' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·连板生态分(0-100，含中位吹哨×0.8后)''' col_ddl
  UNION ALL SELECT 4 ord_no, 'score_first' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·首板生态分(0-100)''' col_ddl
  UNION ALL SELECT 5 ord_no, 'score_anchor' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·阵眼分(0-100)，勿与旧 anchor_score(-3~3)混淆''' col_ddl
  UNION ALL SELECT 6 ord_no, 'signal_flags' col_name, 'VARCHAR(200) DEFAULT NULL COMMENT ''结构信号命中标签，逗号分隔''' col_ddl
  UNION ALL SELECT 7 ord_no, 'forced_ebb' col_name, 'TINYINT DEFAULT 0 COMMENT ''强制退潮：1=命中任一硬条件''' col_ddl
  UNION ALL SELECT 8 ord_no, 'forced_ebb_reason' col_name, 'VARCHAR(300) DEFAULT NULL COMMENT ''强制退潮命中原因，中文''' col_ddl
  UNION ALL SELECT 9 ord_no, 'manual_sector_limit_up_count' col_name, 'INT DEFAULT NULL COMMENT ''五维手填：主线板块涨停数(家)''' col_ddl
  UNION ALL SELECT 10 ord_no, 'manual_sector_premium_pct' col_name, 'DECIMAL(7,2) DEFAULT NULL COMMENT ''五维手填：主线板块昨日涨停今均溢价(%)''' col_ddl
  UNION ALL SELECT 11 ord_no, 'manual_ladder_complete_score' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维手填：板块梯队完整性给分(0-100)''' col_ddl
  UNION ALL SELECT 12 ord_no, 'manual_theme_persistence_days' col_name, 'SMALLINT DEFAULT NULL COMMENT ''五维手填：主线连续活跃天数''' col_ddl
  UNION ALL SELECT 13 ord_no, 'manual_top_high_turnover_pct' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维手填：极高位龙头当日换手%(强制退潮判据)''' col_ddl
  UNION ALL SELECT 14 ord_no, 'manual_first_premium_pct' col_name, 'DECIMAL(7,2) DEFAULT NULL COMMENT ''五维手填：首板次日均溢价(%)，取数未纳入 board=1 时兜底''' col_ddl
  UNION ALL SELECT 15 ord_no, 'manual_first_sealed_rate' col_name, 'DECIMAL(5,2) DEFAULT NULL COMMENT ''五维手填：首板封住/(封住+炸) 百分比(%)''' col_ddl
  UNION ALL SELECT 16 ord_no, 'manual_top_high_break' col_name, 'TINYINT DEFAULT NULL COMMENT ''五维手填：极高位是否爆量断板未回封(1=是)，强制退潮条件4闸门''' col_ddl
  UNION ALL SELECT 17 ord_no, 'manual_anchor_supervision_discount' col_name, 'DECIMAL(3,2) DEFAULT NULL COMMENT ''五维手填：阵眼监管折扣乘数(0-1)，留空=不打折''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_daily_record'
       AND COLUMN_NAME  = need.col_name);

SET @five_dim_sql = IF(@five_dim_adds IS NULL,
    'SELECT ''t_daily_record 五维列已齐，本步跳过'' AS five_dim_migration',
    CONCAT('ALTER TABLE t_daily_record ', @five_dim_adds));
PREPARE five_dim_stmt FROM @five_dim_sql;
EXECUTE five_dim_stmt;
DEALLOCATE PREPARE five_dim_stmt;

-- 其余都由上面覆盖，不用手工做：t_scoring_sub 走 CREATE TABLE IF NOT EXISTS；
-- five_dim 模型/维/子/四层/阶梯种子走 INSERT IGNORE；ultra_short 下线走那句 UPDATE。
-- 建库跑完这一步后，还需要一次 POST /api/records/recalc-all 把历史按新口径重算，
-- 否则 score_* 五列全是 NULL（NULL 是"没算过"，不是"0 分"）。
