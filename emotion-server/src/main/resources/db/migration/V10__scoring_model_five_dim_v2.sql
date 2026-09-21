-- Flyway migration V10: scoring model five dim v2
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1019-1298 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- =====================================================================================
-- ==== 五维双层模型 v2(five_dim_v2)：PRD 2.0 核心升级——主线5要素 + 阵眼龙头分工 ====
-- =====================================================================================
-- 参考《五维prd.txt》v2.0：
--   主线明确度 20% 拆 5 要素：涨停聚集度25 / 高度聚集度25 / 成交额聚集度20 / 催化剂硬度15 / 持续性15
--   阵眼 15% 拆龙头分工：总龙头50 / 中军20 / 跟风15 / 卡位10 / 反包5
-- 与 five_dim 的关系：five_dim_v2 置 active=1，five_dim/ultra_short 退役(旧种子/列保留，仅作历史留痕)。
-- 取数来源：自动读数由 PrdMetricsService 从 t_market_stock(涨停/炸板池,含 amount 成交额) + t_theme 推导；
--   缺读数的键=该子未评(引擎按已评权重归一化)。成交额聚集度固定涨停股口径
--   (主线涨停股amount/全部涨停股amount,自动,不接受人工覆盖)；高度/催化剂为 STRATEGY(空间板归属、无题材行默认50)。
INSERT IGNORE INTO t_scoring_model (model_key, name, max_score, active, note) VALUES
    ('five_dim_v2', '五维双层情绪模型 v3(D5融合)', 100.00, 1,
     'D5融合(2026-09-12)：D1大盘22/D2日内核心18/D3连板22/D4首板13/D5高位生态25=阵眼个体35(人工t_anchor:行为40/高度25/封板20/主线一致15)+抱团资金30(结构60/强度40)+监管压制20+监管反馈15。阵眼身份人工配置(起止区间)；监管期现推 SEVERE/EXCH 10日、ZD 5日只展示');

-- 旧模型下线(幂等，重放无害)。
UPDATE t_scoring_model SET active = 0 WHERE model_key = 'five_dim' AND active = 1;

SET @fid2 := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim_v2');

-- ---- 五维(权重和=1.00；2026-09-12 D5 融合：22/18/22/13/25，末维 anchor→high) ----
INSERT IGNORE INTO t_scoring_dim (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note) VALUES
    (@fid2,'market',    1,'大盘生态',  0.22, 1,'score_market',    'WEIGHTED_SUM','指数环境35/量能25/广度20/涨跌停20'),
    (@fid2,'theme_main',2,'日内核心',  0.18, 2,'score_theme_main','WEIGHTED_SUM','日内最热行业5要素：涨停聚集度25/高度聚集度25/成交额聚集度20/催化剂硬度15/持续性15；连续3交易日热度≥5才收集为主线龙头；龙头错位只输出信号不扣分(扣分归D5阵眼一致性)'),
    (@fid2,'board',     3,'连板生态',  0.22, 3,'score_board',     'WEIGHTED_SUM','时间截面T-1→T：数量高度10/晋级30/溢价25/大面20/炸板15；三层(低=2/中=3-4/高=5板+对齐D5)；低位=1进2；高位层只留接力效率低权重(抱团监管反包归D5)、H<5高位N/A剔除分母；修正：小样本×0.8、H<5空间未打开晋级×0.8、大盘背离溢价×0.8、全局跌停外溢-35/-20/-8；闸门中位吹哨×0.8、大盘背离×0.85'),
    (@fid2,'first',     4,'首板生态',  0.13, 4,'score_first',     'WEIGHTED_SUM','纯T日试错端：首板数量30/首板封板率25/首板炸板率20/封单质量15(均封单0.6+一字占比0.4)/首板题材聚集10；大盘背离时数量×0.85、封板率-10。1进2/首板溢价已迁入连板低位层'),
    (@fid2,'high',      5,'高位生态',  0.25, 5,'score_high',      'WEIGHTED_SUM','D5融合：阵眼个体35(人工阵眼:行为40/高度25/封板20/主线一致15)+抱团资金30(结构60:高位家数占比30/封单集中25/空间唯一25/梯队20;强度40:高位溢价50/高位晋级50)+监管压制20(家数40/高位占比35/扩散25)+监管反馈15');

-- ---- 子指标（D2 五要素 / D5 龙头分工；D1/D3/D4 与 five_dim 逐字一致）----
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'market','index_env',   '-','指数环境',0.35,'STRATEGY','index_env',1,'三指涨跌幅及协同性'),
    (@fid2,'market','turnover',    '-','量能',    0.25,'STRATEGY','turnover',2,'成交额/20日均值 基础分 × 价量配合系数(放量涨1.2/平量涨1.1/缩量涨0.9/缩量跌0.6/平量跌0.35/放量跌0.3/放量暴跌0.15；普跌日量能贡献<指数贡献)'),
    (@fid2,'market','breadth',     '-','广度',    0.20,'BAND_LADDER','red_ratio',3,'红盘率阶梯85/55/30/10/5/0；<0.13(≈涨跌家数比>7:1)起逐级扣分'),
    (@fid2,'market','limit_combo', '-','涨跌停',  0.20,'STRATEGY','limit_combo',4,'涨停/跌停两操作数组合');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'theme_main','zt_gather',    '-','涨停聚集度',  0.25,'BAND_LADDER','zt_gather_pct',   1,'主线板块涨停数/全市场涨停数(PrdMetricsService自动)'),
    (@fid2,'theme_main','height_gather','-','高度聚集度',  0.25,'STRATEGY','height_gather',2,'STRATEGY:空间板(全市场H)在主线行业→按高度比走阶梯；不在→(主线最高板/H)×50封顶50'),
    (@fid2,'theme_main','amount_gather','-','成交额聚集度',0.20,'BAND_LADDER','amount_gather_pct',3,'涨停股口径(自动=主线涨停股amount/全部涨停股amount)，不接受人工覆盖'),
    (@fid2,'theme_main','catalyst',     '-','催化剂硬度',  0.15,'STRATEGY','catalyst', 4,'STRATEGY:题材硬度1-5→100/80/60/40/20(t_theme维护)；日内核心存在但无匹配题材行=默认50(人工未评)'),
    (@fid2,'theme_main','persistence',  '-','持续性',      0.15,'BAND_LADDER','persistence_days',  5,'主线连续活跃天数(自动，当日≥5家涨停算活跃)');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'board','count_height',  '-','数量高度',0.10,'BAND_LADDER','max_height',1,'按空间板H给分:H≥7=95/5-6=70/H=4=25/H=3=15/≤2=5'),
    (@fid2,'board','promo',         '-','晋级结构',0.30,'LAYER_WEIGHTED_BAND',NULL,2,'三层晋级率(低=1进2/中3-4/高5板+)各出分再按 0.50/0.35/0.15 加权'),
    (@fid2,'board','premium',       '-','溢价结构',0.25,'LAYER_WEIGHTED_BAND',NULL,3,'三层T-1→T溢价(低位=昨首板今均溢价,取tier board=1)；权重 0.45/0.35/0.20'),
    (@fid2,'board','bigloss',       '-','大面结构',0.20,'LAYER_WEIGHTED_BAND',NULL,4,'三层T-1→T大面，每层=家数+大面率(大面/该层昨种子)各50%；权重 0.45/0.35/0.20'),
    (@fid2,'board','broken_quality', '-','炸板质量',0.15,'WEIGHTED_SUM',NULL,5,'0.6×封板率分+0.4×回封率分');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'board','promo_low',     'promo','低位晋级',0.50,'BAND_LADDER','jr_low',1,'2板(固定)'),
    (@fid2,'board','promo_mid',     'promo','中位晋级',0.35,'BAND_LADDER','jr_mid',2,'3-4板 吹哨锚点'),
    (@fid2,'board','promo_high',    'promo','高位晋级',0.15,'BAND_LADDER','jr_high',3,'5板+ 对齐高位生态D5,只留效率视角'),
    (@fid2,'board','premium_low',     'premium','低位溢价',0.45,'BAND_LADDER','prem_low',1,''),
    (@fid2,'board','premium_mid',     'premium','中位溢价',0.35,'BAND_LADDER','prem_mid',2,''),
    (@fid2,'board','premium_high',    'premium','高位溢价',0.20,'BAND_LADDER','prem_high',3,''),
    (@fid2,'board','bigloss_low',      'bigloss','低位大面',0.45,'WEIGHTED_SUM',NULL,1,'家数+大面率各50%'),
    (@fid2,'board','bigloss_mid',      'bigloss','中位大面',0.35,'WEIGHTED_SUM',NULL,2,'家数+大面率各50%'),
    (@fid2,'board','bigloss_high',     'bigloss','高位大面',0.20,'WEIGHTED_SUM',NULL,3,'家数+大面率各50% 对齐D5'),
    (@fid2,'board','bigloss_low_cnt',    'bigloss_low','低位大面·家数',0.50,'BAND_LADDER','big_low',1,''),
    (@fid2,'board','bigloss_low_rate',   'bigloss_low','低位大面·大面率',  0.50,'BAND_LADDER','big_low_rate',2,'大面/该层昨种子'),
    (@fid2,'board','bigloss_mid_cnt',    'bigloss_mid','中位大面·家数',0.50,'BAND_LADDER','big_mid',1,''),
    (@fid2,'board','bigloss_mid_rate',   'bigloss_mid','中位大面·大面率',  0.50,'BAND_LADDER','big_mid_rate',2,'大面/该层昨种子'),
    (@fid2,'board','bigloss_high_cnt',   'bigloss_high','高位大面·家数',0.50,'BAND_LADDER','big_high',1,''),
    (@fid2,'board','bigloss_high_rate',  'bigloss_high','高位大面·大面率',  0.50,'BAND_LADDER','big_high_rate',2,'大面/该层昨种子'),
    (@fid2,'board','bq_sealed',  'broken_quality','家数封板率',0.60,'BAND_LADDER','sealed_home_rate',1,''),
    (@fid2,'board','bq_reseal',  'broken_quality','回封率',    0.40,'BAND_LADDER','reseal_rate',2,'');
-- D4 时间截面 PRD v2.0（2026-09-12）：纯 T 日。1进2晋级/首板溢价/1进2大面迁入 D3 低位层（jr_low/prem_low/big_low）。
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'first','first_count',        '-','首板数量',    0.30,'BAND_LADDER','first_count',1,'T日新首板封住家数；大盘背离时×0.85'),
    (@fid2,'first','first_sealed',       '-','首板封板率',  0.25,'BAND_LADDER','first_sealed_rate',2,'首板封住/(封住+首板炸板)；大盘背离时-10'),
    (@fid2,'first','first_bomb',         '-','首板炸板率',  0.20,'BAND_LADDER','first_bomb_rate',3,'首板炸板/(封住+首板炸板)，越低越好'),
    (@fid2,'first','first_seal_quality', '-','封单质量',    0.15,'WEIGHTED_SUM',NULL,4,'0.6×首板均封单分+0.4×一字首板占比分'),
    (@fid2,'first','first_theme',        '-','首板题材聚集',0.10,'BAND_LADDER','first_theme_gather_pct',5,'最热行业首板数/首板总数(只数T日新首板)');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'first','first_avg_seal', 'first_seal_quality','首板均封单',   0.60,'BAND_LADDER','first_avg_seal_amount',1,'T日首板封单额均值(亿元)'),
    (@fid2,'first','first_yizi',     'first_seal_quality','一字首板占比', 0.40,'BAND_LADDER','first_yizi_ratio',2,'一字首板/能判形态的首板');
-- D5 高位生态：4 个一级子(阵眼个体35/抱团资金30/监管压制20/监管反馈15)+抱团下两层复合。
-- MANUAL 叶=HighEcoMetricsService 算好的 0-100 直读；BAND_LADDER 叶读原始比率/家数；feedback 为 STRATEGY 五态。
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'high','anchor_ind', '-','阵眼个体',  0.35,'WEIGHTED_SUM',NULL,1,'人工 t_anchor 在位阵眼(起止区间内)：行为40/高度25/封板20/主线一致15；多阵眼按角色 总龙0.5/分支0.2/补涨0.2/反包0.1 加权；无在位阵眼=整支未评'),
    (@fid2,'high','coalition',  '-','抱团与资金',0.30,'WEIGHTED_SUM',NULL,2,'结构质量60(高位家数占比/封单集中/空间唯一/梯队)+资金强度40(高位溢价/高位晋级)；高位阈值 H>=5?5:max(3,H-1)'),
    (@fid2,'high','pressure',   '-','监管压制',  0.20,'WEIGHTED_SUM',NULL,3,'SEVERE/EXCH 在列：监管家数40/高位监管占比35/板块扩散25；事件窗为空=整支未评(不是100)'),
    (@fid2,'high','feedback',   '-','监管反馈',  0.15,'STRATEGY','d5_feedback',4,'STRATEGY 五态：核按钮0/断板大跌25/绿盘50/红盘85/继续涨停70(读 d5f_nuke/d5f_avg)');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'high','d5a_action', 'anchor_ind','龙头行为',0.40,'MANUAL','d5a_action',1,'晋级100/反包80/抗跌60/断板20/核按钮0(HighEcoMetricsService算)'),
    (@fid2,'high','d5a_height', 'anchor_ind','龙头高度',0.25,'MANUAL','d5a_height',2,'板数 vs H：=H 100/H-1 80/H-2 60/更低30(断板取昨板)'),
    (@fid2,'high','d5a_seal',   'anchor_ind','封板质量',0.20,'MANUAL','d5a_seal',3,'一字100/换手回封80/烂板40/断板核按钮0'),
    (@fid2,'high','d5a_consist','anchor_ind','主线一致性',0.15,'MANUAL','d5a_consist',4,'阵眼行业==日内核心100，否则60(错位)'),
    (@fid2,'high','c_structure','coalition','结构质量',0.60,'WEIGHTED_SUM',NULL,1,''),
    (@fid2,'high','c_strength', 'coalition','资金强度',0.40,'WEIGHTED_SUM',NULL,2,'');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'high','d5c_ratio','c_structure','高位家数占比',0.30,'BAND_LADDER','d5c_ratio',1,'高位家数/连板(≥2板)家数 %：20-40健康100/10-20与40-60=70/>60过度抱团40/<10无抱团50'),
    (@fid2,'high','d5c_seal', 'c_structure','高位封单集中',0.25,'BAND_LADDER','d5c_seal',2,'高位 seal_amount/全市场涨停封单 %：≤50=90/≤70=70/>70=40'),
    (@fid2,'high','d5c_top',  'c_structure','空间板唯一性',0.25,'BAND_LADDER','d5c_top',3,'consecutive==H 家数：2-3互相支撑100/≥4分散70/唯一孤军50'),
    (@fid2,'high','d5c_tier', 'c_structure','梯队支撑度',0.20,'MANUAL','d5c_tier',4,'2..H 板无断层100/有断层40(HighEcoMetricsService算)'),
    (@fid2,'high','d5c_prem', 'c_strength','高位溢价',0.50,'BAND_LADDER','d5c_prem',1,'高位档 t_premium_tier 加权均溢价 %：>3=95/1-3=80/0-1=65/-3~0=35/<-3=10'),
    (@fid2,'high','d5c_jr',   'c_strength','高位晋级率',0.50,'BAND_LADDER','d5c_jr',2,'%：≥60=95/40-60=80/25-40=65/15-25=45/<15=20(今高位 ÷ 昨≥highThreshold-1板基数)'),
    (@fid2,'high','d5p_count','pressure','监管家数',0.40,'BAND_LADDER','d5p_count',1,'SEVERE/EXCH 在列家数：0=100/≤2=80/≤5=55/≤10=30/>10=10'),
    (@fid2,'high','d5p_high_ratio','pressure','高位监管占比',0.35,'BAND_LADDER','d5p_high_ratio',2,'在列股中高位股占比 %：0=100/≤30=70/≤60=40/>60=15'),
    (@fid2,'high','d5p_spread','pressure','监管扩散度',0.25,'BAND_LADDER','d5p_spread',3,'同行业最多监管家数：0=100/1=85/≤3=50/>3=20');

-- ---- 0-100 阈值阶梯(D2 五要素 BAND_LADDER) + D1/D3/D4 与 five_dim 同 + D5 MANUAL 无阶梯 ----
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'market','turnover',1,'COMPOUND',NULL,NULL,100,'放量上涨：量比>=1.1 且三指均值>0 → 基础分×1.2(封顶100)','STRATEGY:基础阶梯90/70/45/25,Java算'),
    (@fid2,'market','turnover',2,'COMPOUND',NULL,NULL,70,'平量下跌：量比0.90~1.10 且三指均值<0 → ×0.35(9/11适用)',''),
    (@fid2,'market','turnover',3,'COMPOUND',NULL,NULL,45,'放量下跌：三指均值<0 且>-1.5% → ×0.3',''),
    (@fid2,'market','turnover',4,'COMPOUND',NULL,NULL,25,'放量暴跌：三指均值<=-1.5% → ×0.15',''),
    (@fid2,'market','breadth',1,'GTE',0.60,NULL,85,'红盘率 >=0.6',''),
    (@fid2,'market','breadth',2,'GTE',0.40,NULL,55,'>=0.4',''),
    (@fid2,'market','breadth',3,'GTE',0.20,NULL,30,'>=0.2',''),
    (@fid2,'market','breadth',4,'GTE',0.13,NULL,10,'>=0.13(涨跌家数比<=7:1)','涨跌家数比>7:1 扣5'),
    (@fid2,'market','breadth',5,'GTE',0.10,NULL,5,'<0.13(涨跌比>7:1)','10-5'),
    (@fid2,'market','breadth',6,'GTE',0.05,NULL,0,'<0.10 超极端','5-5'),
    (@fid2,'market','breadth',7,'ELSE',NULL,NULL,0,'<0.05 崩盘',''),
    (@fid2,'market','index_env',1,'COMPOUND',1.50,NULL,95,'三指均值 +1.5% → 95','连续:score=clamp(50+均值%×30,0,100)'),
    (@fid2,'market','index_env',2,'COMPOUND',1.00,NULL,80,'三指均值 +1% → 80',''),
    (@fid2,'market','index_env',3,'COMPOUND',0.00,NULL,50,'三指均值 0% → 50(中性)',''),
    (@fid2,'market','index_env',4,'COMPOUND',-1.00,NULL,20,'三指均值 -1% → 20(9/11:-0.92→22.4)',''),
    (@fid2,'market','index_env',5,'COMPOUND',-2.00,NULL,0,'三指均值 -2% → 0(封底)',''),
    (@fid2,'market','limit_combo',1,'COMPOUND',80,0,95,'涨停>=80 且 跌停=0','STRATEGY:两操作数,Java算'),
    (@fid2,'market','limit_combo',2,'COMPOUND',40,8,45,'涨停40~60 且 跌停5~8',''),
    (@fid2,'market','limit_combo',3,'COMPOUND',NULL,20,5,'跌停>20',''),
    (@fid2,'market','limit_combo',4,'ELSE',NULL,NULL,50,'其余','中间档占位');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'theme_main','zt_gather',1,'GTE',25,NULL,95,'涨停聚集度 >=25%','锚点：主线占四分之一涨停=极强(2026-09-16按市场重校)'),
    (@fid2,'theme_main','zt_gather',2,'GTE',18,NULL,82,'>=18','元件22%(2026-09-11)落此档'),
    (@fid2,'theme_main','zt_gather',3,'GTE',12,NULL,68,'>=12',''),
    (@fid2,'theme_main','zt_gather',4,'GTE',8,NULL,48,'>=8',''),
    (@fid2,'theme_main','zt_gather',5,'ELSE',NULL,NULL,28,'<8','分散无主线'),
    (@fid2,'theme_main','height_gather',1,'COMPOUND',NULL,NULL,95,'空间板在主线行业：高度比>=90%','STRATEGY:空间板归属本板块时走阶梯95/85/70/50/28,Java算'),
    (@fid2,'theme_main','height_gather',2,'COMPOUND',NULL,NULL,50,'空间板不在主线：(主线最高板/H)×50 封顶50','元件案例:2板/4板×50=25,不再按板数比给70'),
    (@fid2,'theme_main','amount_gather',1,'GTE',40,NULL,95,'成交额聚集度 >=40%','自动=主线涨停股amount/全部涨停股amount(涨停股口径,不接受人工覆盖)'),
    (@fid2,'theme_main','amount_gather',2,'GTE',25,NULL,80,'>=25',''),
    (@fid2,'theme_main','amount_gather',3,'GTE',15,NULL,60,'>=15',''),
    (@fid2,'theme_main','amount_gather',4,'ELSE',NULL,NULL,35,'<15',''),
    (@fid2,'theme_main','catalyst',1,'COMPOUND',5,NULL,100,'硬度5星(政策/产业级)','STRATEGY:硬度1-5→20/40/60/80/100,Java算'),
    (@fid2,'theme_main','catalyst',2,'COMPOUND',4,NULL,80,'4星',''),
    (@fid2,'theme_main','catalyst',3,'COMPOUND',3,NULL,60,'3星(行业/事件)',''),
    (@fid2,'theme_main','catalyst',4,'COMPOUND',2,NULL,40,'2星',''),
    (@fid2,'theme_main','catalyst',5,'COMPOUND',1,NULL,20,'1星(Pure情绪)',''),
    (@fid2,'theme_main','catalyst',6,'COMPOUND',NULL,NULL,50,'无匹配题材行=默认50(人工未评)','仅日内核心存在时兜底；无涨停池=整维未评'),
    (@fid2,'theme_main','persistence',1,'GTE',5,NULL,95,'连续活跃 >=5天','当日主线≥5家涨停算活跃1天'),
    (@fid2,'theme_main','persistence',2,'GTE',3,NULL,85,'>=3天',''),
    (@fid2,'theme_main','persistence',3,'GTE',2,NULL,70,'=2天',''),
    (@fid2,'theme_main','persistence',4,'GTE',1,NULL,50,'首日',''),
    (@fid2,'theme_main','persistence',5,'ELSE',NULL,NULL,25,'中断','');
-- B(2026-09-17) D2 涨停聚集度阶梯重校：INSERT IGNORE 只建新行、不覆盖已存旧档，
-- 这里显式 UPDATE 幂等刷入新档（守卫旧值，重复执行自动收敛，改后再执行即 no-op）。
UPDATE t_scoring_rule SET threshold_low=25, formula='涨停聚集度 >=25%', note='锚点:主线占四分之一涨停=极强(2026-09-16按市场重校)' WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='zt_gather' AND rule_no=1 AND threshold_low=40;
UPDATE t_scoring_rule SET threshold_low=18, score=82, formula='>=18', note='元件22%(2026-09-11)落此档' WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='zt_gather' AND rule_no=2 AND threshold_low=30;
UPDATE t_scoring_rule SET threshold_low=12, score=68, formula='>=12', note='' WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='zt_gather' AND rule_no=3 AND threshold_low=20;
UPDATE t_scoring_rule SET threshold_low=8, score=48, formula='>=8', note='' WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='zt_gather' AND rule_no=4 AND threshold_low=10;
UPDATE t_scoring_rule SET formula='<8', note='分散无主线' WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='zt_gather' AND rule_no=5 AND threshold_low IS NULL AND formula='<10';
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','promo_low',1,'GTE',60,NULL,95,'层晋级率 >=60%',''),(@fid2,'board','promo_low',2,'GTE',40,NULL,80,'>=40',''),(@fid2,'board','promo_low',3,'GTE',25,NULL,65,'>=25',''),(@fid2,'board','promo_low',4,'GTE',15,NULL,45,'>=15',''),(@fid2,'board','promo_low',5,'ELSE',NULL,NULL,20,'<15',''),
    (@fid2,'board','promo_mid',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_mid',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_mid',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_mid',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_mid',5,'ELSE',NULL,NULL,20,'',''),
    (@fid2,'board','promo_high',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_high',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_high',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_high',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_high',5,'ELSE',NULL,NULL,20,'',''),
    (@fid2,'board','promo',0,'GUARD',NULL,NULL,NULL,'中位吹哨:JR中<15% 或 (Prem中<0 且 Big中>=3) → 连板总分×0.8','信号表另有结构信号定义');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','premium_low',1,'GT',3,NULL,95,'层溢价 >3%',''),(@fid2,'board','premium_low',2,'GTE',1,NULL,80,'1~3',''),(@fid2,'board','premium_low',3,'GTE',0,NULL,65,'0~1',''),(@fid2,'board','premium_low',4,'GTE',-1,NULL,45,'0~-1',''),(@fid2,'board','premium_low',5,'GTE',-3,NULL,25,'-1~-3',''),(@fid2,'board','premium_low',6,'ELSE',NULL,NULL,5,'<-3',''),
    (@fid2,'board','premium_mid',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_mid',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_mid',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_mid',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_mid',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_mid',6,'ELSE',NULL,NULL,5,'',''),
    (@fid2,'board','premium_high',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_high',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_high',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_high',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_high',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_high',6,'ELSE',NULL,NULL,5,'','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','bigloss_low_cnt',1,'EQ',0,NULL,95,'大面家数 =0',''),(@fid2,'board','bigloss_low_cnt',2,'LTE',2,NULL,80,'1~2',''),(@fid2,'board','bigloss_low_cnt',3,'LTE',5,NULL,60,'3~5',''),(@fid2,'board','bigloss_low_cnt',4,'LTE',10,NULL,35,'6~10',''),(@fid2,'board','bigloss_low_cnt',5,'ELSE',NULL,NULL,10,'>10',''),
    (@fid2,'board','bigloss_mid_cnt',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_mid_cnt',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_mid_cnt',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_mid_cnt',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_mid_cnt',5,'ELSE',NULL,NULL,10,'',''),
    (@fid2,'board','bigloss_high_cnt',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_high_cnt',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_high_cnt',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_high_cnt',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_high_cnt',5,'ELSE',NULL,NULL,10,'',''),
    (@fid2,'board','bigloss_low_rate',1,'EQ',0,NULL,95,'大面率 =0%',''),(@fid2,'board','bigloss_low_rate',2,'LTE',10,NULL,85,'<=10%',''),(@fid2,'board','bigloss_low_rate',3,'LTE',20,NULL,70,'<=20%',''),(@fid2,'board','bigloss_low_rate',4,'LTE',35,NULL,50,'<=35%',''),(@fid2,'board','bigloss_low_rate',5,'LTE',50,NULL,30,'<=50%',''),(@fid2,'board','bigloss_low_rate',6,'ELSE',NULL,NULL,10,'>50%',''),
    (@fid2,'board','bigloss_mid_rate',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_mid_rate',2,'LTE',10,NULL,85,'',''),(@fid2,'board','bigloss_mid_rate',3,'LTE',20,NULL,70,'',''),(@fid2,'board','bigloss_mid_rate',4,'LTE',35,NULL,50,'',''),(@fid2,'board','bigloss_mid_rate',5,'LTE',50,NULL,30,'',''),(@fid2,'board','bigloss_mid_rate',6,'ELSE',NULL,NULL,10,'',''),
    (@fid2,'board','bigloss_high_rate',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_high_rate',2,'LTE',10,NULL,85,'',''),(@fid2,'board','bigloss_high_rate',3,'LTE',20,NULL,70,'',''),(@fid2,'board','bigloss_high_rate',4,'LTE',35,NULL,50,'',''),(@fid2,'board','bigloss_high_rate',5,'LTE',50,NULL,30,'',''),(@fid2,'board','bigloss_high_rate',6,'ELSE',NULL,NULL,10,'','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','bq_sealed',1,'GTE',85,NULL,95,'封板率 >=85%','spec锚点85/45；中间占位'),(@fid2,'board','bq_sealed',2,'GTE',70,NULL,80,'>=70',''),(@fid2,'board','bq_sealed',3,'GTE',55,NULL,60,'>=55',''),(@fid2,'board','bq_sealed',4,'GTE',45,NULL,40,'>=45',''),(@fid2,'board','bq_sealed',5,'ELSE',NULL,NULL,15,'<45',''),
    (@fid2,'board','bq_reseal',1,'GTE',75,NULL,95,'回封率 >=75%','档借封板率形态,待定标'),(@fid2,'board','bq_reseal',2,'GTE',60,NULL,80,'>=60',''),(@fid2,'board','bq_reseal',3,'GTE',45,NULL,60,'>=45',''),(@fid2,'board','bq_reseal',4,'GTE',30,NULL,40,'>=30',''),(@fid2,'board','bq_reseal',5,'ELSE',NULL,NULL,15,'<30',''),
    (@fid2,'board','broken_quality',0,'AGG',NULL,NULL,NULL,'0.6×封板率分 + 0.4×回封率分',''),
    (@fid2,'board','count_height',1,'GTE',7,NULL,95,'H>=7 空间打开','2026-09-12 改按空间板H给分'),(@fid2,'board','count_height',2,'GTE',5,NULL,70,'H=5~6',''),(@fid2,'board','count_height',3,'GTE',4,NULL,25,'H=4 空间未打开',''),(@fid2,'board','count_height',4,'GTE',3,NULL,15,'H=3',''),(@fid2,'board','count_height',5,'ELSE',NULL,NULL,5,'H<=2',''),
    (@fid2,'board','promo',1,'GUARD',NULL,NULL,NULL,'小样本:中位晋级昨日基数<5家→中位晋级叶×0.8','引擎BoardScoreCalculator常量'),
    (@fid2,'board','promo',2,'GUARD',NULL,NULL,NULL,'空间未打开:H<5(无高位层,5板+)→晋级结构×0.8',''),
    (@fid2,'board','premium',1,'GUARD',NULL,NULL,NULL,'大盘背离:大盘分<40 或 红盘率<20%→溢价结构×0.8',''),
    (@fid2,'board','bigloss',1,'GUARD',NULL,NULL,NULL,'全局跌停外溢:跌停≥20/≥10/≥5 → 大面结构-35/-20/-8',''),
    (@fid2,'board','-',0,'GUARD',NULL,NULL,NULL,'维分闸门:中位吹哨×0.8;大盘背离(大盘分<40/强制退潮/跌停≥20)×0.85。龙头错位2026-09-12起只输出信号不再扣分(扣分归D5阵眼一致性)','引擎统一施加,见连板生态打分表尾');
-- D4 纯 T 日阶梯（2026-09-12 时间截面重构）：旧 first_premium/promo_1to2/big_1to2 随子项删除，规则在迁移段 DELETE
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'first','first_count',1,'GTE',60,NULL,95,'首板家数 >=60',''),(@fid2,'first','first_count',2,'GTE',40,NULL,80,'>=40',''),(@fid2,'first','first_count',3,'GTE',25,NULL,65,'25~39(大盘背离日×0.85)',''),(@fid2,'first','first_count',4,'GTE',15,NULL,50,'15~24',''),(@fid2,'first','first_count',5,'GTE',8,NULL,35,'8~14',''),(@fid2,'first','first_count',6,'ELSE',NULL,NULL,20,'<8',''),
    (@fid2,'first','first_sealed',1,'GTE',80,NULL,95,'首板封板率 >=80%','大盘背离日-10'),(@fid2,'first','first_sealed',2,'GTE',70,NULL,80,'>=70%',''),(@fid2,'first','first_sealed',3,'GTE',60,NULL,65,'>=60%',''),(@fid2,'first','first_sealed',4,'GTE',50,NULL,50,'>=50%',''),(@fid2,'first','first_sealed',5,'ELSE',NULL,NULL,30,'<50%',''),
    (@fid2,'first','first_bomb',1,'LTE',10,NULL,95,'首板炸板率 <=10%','越低越好'),(@fid2,'first','first_bomb',2,'LTE',20,NULL,80,'<=20%',''),(@fid2,'first','first_bomb',3,'LTE',30,NULL,60,'<=30%',''),(@fid2,'first','first_bomb',4,'LTE',40,NULL,40,'<=40%',''),(@fid2,'first','first_bomb',5,'ELSE',NULL,NULL,20,'>40%',''),
    (@fid2,'first','first_avg_seal',1,'GTE',3,NULL,95,'首板均封单 >=3亿','单位亿元'),(@fid2,'first','first_avg_seal',2,'GTE',1.5,NULL,80,'>=1.5亿',''),(@fid2,'first','first_avg_seal',3,'GTE',0.8,NULL,60,'>=0.8亿',''),(@fid2,'first','first_avg_seal',4,'GTE',0.4,NULL,40,'>=0.4亿',''),(@fid2,'first','first_avg_seal',5,'ELSE',NULL,NULL,20,'<0.4亿',''),
    (@fid2,'first','first_yizi',1,'GTE',30,NULL,95,'一字首板占比 >=30%',''),(@fid2,'first','first_yizi',2,'GTE',20,NULL,80,'>=20%',''),(@fid2,'first','first_yizi',3,'GTE',10,NULL,60,'>=10%',''),(@fid2,'first','first_yizi',4,'GTE',5,NULL,40,'>=5%',''),(@fid2,'first','first_yizi',5,'ELSE',NULL,NULL,20,'<5%',''),
    (@fid2,'first','first_theme',1,'GTE',40,NULL,95,'首板题材聚集度 >=40%','最热行业首板占比'),(@fid2,'first','first_theme',2,'GTE',30,NULL,82,'>=30%',''),(@fid2,'first','first_theme',3,'GTE',20,NULL,68,'>=20%',''),(@fid2,'first','first_theme',4,'GTE',10,NULL,48,'>=10%',''),(@fid2,'first','first_theme',5,'ELSE',NULL,NULL,28,'<10%',''),
    (@fid2,'first','first_seal_quality',0,'AGG',NULL,NULL,NULL,'0.6×首板均封单分 + 0.4×一字首板占比分',''),
    (@fid2,'first','-',1,'GUARD',NULL,NULL,NULL,'大盘背离(大盘分<40/强制退潮/跌停≥20):首板数量×0.85、首板封板率-10','引擎applyFirstCalibration');
-- D5 高位生态：8 个 BAND_LADDER 叶的阶梯 + STRATEGY 反馈五态 COMPOUND 展示行（真算法在 Java）。
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'high','d5c_ratio',1,'BETWEEN',20,40,100,'高位家数占比 20%~40% 健康','分母=连板(≥2板)家数'),
    (@fid2,'high','d5c_ratio',2,'GT',60,NULL,40,'>60% 过度抱团',''),
    (@fid2,'high','d5c_ratio',3,'GTE',40,NULL,70,'40%~60% 偏挤',''),
    (@fid2,'high','d5c_ratio',4,'GTE',10,NULL,70,'10%~20% 抱团偏弱',''),
    (@fid2,'high','d5c_ratio',5,'ELSE',NULL,NULL,50,'<10% 无抱团',''),
    (@fid2,'high','d5c_seal',1,'LTE',50,NULL,90,'封单集中度 ≤50%',''),
    (@fid2,'high','d5c_seal',2,'LTE',70,NULL,70,'≤70%',''),
    (@fid2,'high','d5c_seal',3,'ELSE',NULL,NULL,40,'>70% 资金死守塔尖',''),
    (@fid2,'high','d5c_top',1,'BETWEEN',2,3,100,'空间板 2-3 只互相支撑',''),
    (@fid2,'high','d5c_top',2,'GTE',4,NULL,70,'≥4 只 过多分散',''),
    (@fid2,'high','d5c_top',3,'ELSE',NULL,NULL,50,'唯一空间板=孤军',''),
    (@fid2,'high','d5c_prem',1,'GT',3,NULL,95,'高位溢价 >3%',''),
    (@fid2,'high','d5c_prem',2,'GTE',1,NULL,80,'1~3%',''),
    (@fid2,'high','d5c_prem',3,'GTE',0,NULL,65,'0~1%',''),
    (@fid2,'high','d5c_prem',4,'GTE',-3,NULL,35,'-3%~0',''),
    (@fid2,'high','d5c_prem',5,'ELSE',NULL,NULL,10,'<-3%',''),
    (@fid2,'high','d5c_jr',1,'GTE',60,NULL,95,'高位晋级率 ≥60%',''),
    (@fid2,'high','d5c_jr',2,'GTE',40,NULL,80,'40~60%',''),
    (@fid2,'high','d5c_jr',3,'GTE',25,NULL,65,'25~40%',''),
    (@fid2,'high','d5c_jr',4,'GTE',15,NULL,45,'15~25%',''),
    (@fid2,'high','d5c_jr',5,'ELSE',NULL,NULL,20,'<15%',''),
    (@fid2,'high','d5p_count',1,'EQ',0,NULL,100,'监管家数 0',''),
    (@fid2,'high','d5p_count',2,'LTE',2,NULL,80,'≤2',''),
    (@fid2,'high','d5p_count',3,'LTE',5,NULL,55,'≤5',''),
    (@fid2,'high','d5p_count',4,'LTE',10,NULL,30,'≤10',''),
    (@fid2,'high','d5p_count',5,'ELSE',NULL,NULL,10,'>10',''),
    (@fid2,'high','d5p_high_ratio',1,'EQ',0,NULL,100,'高位监管占比 0%',''),
    (@fid2,'high','d5p_high_ratio',2,'LTE',30,NULL,70,'≤30%',''),
    (@fid2,'high','d5p_high_ratio',3,'LTE',60,NULL,40,'≤60%',''),
    (@fid2,'high','d5p_high_ratio',4,'ELSE',NULL,NULL,15,'>60% 高位被重点盯防',''),
    (@fid2,'high','d5p_spread',1,'EQ',0,NULL,100,'板块扩散 0 家','无进分在列时'),
    (@fid2,'high','d5p_spread',2,'LTE',1,NULL,85,'同板块最多 1 家',''),
    (@fid2,'high','d5p_spread',3,'LTE',3,NULL,50,'≤3 家',''),
    (@fid2,'high','d5p_spread',4,'ELSE',NULL,NULL,20,'>3 家 板块级点名','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'high','feedback',1,'COMPOUND',NULL,NULL,0,'监管股核按钮/跌停 ≥1 只 → 0','STRATEGY:d5_feedback,Java算'),
    (@fid2,'high','feedback',2,'COMPOUND',5,NULL,70,'无核按钮且均涨>5%(继续涨停=监管无效,情绪亢奋)',''),
    (@fid2,'high','feedback',3,'COMPOUND',0,NULL,85,'均涨>0% 红盘温和消化',''),
    (@fid2,'high','feedback',4,'COMPOUND',-7,NULL,50,'均涨 -7%~0% 绿盘分歧',''),
    (@fid2,'high','feedback',5,'ELSE',NULL,NULL,25,'均涨≤-7% 压制明显',''),
    (@fid2,'high','anchor_ind',1,'COMPOUND',NULL,NULL,NULL,'行为40+高度25+封板20+一致15；多阵眼角色加权(总龙0.5/分支0.2/补涨0.2/反包0.1)','MANUAL叶由 HighEcoMetricsService 现算'),
    (@fid2,'high','coalition',1,'COMPOUND',NULL,NULL,NULL,'结构60(占比30/封单25/唯一25/梯队20)+强度40(溢价50/晋级50)',''),
    (@fid2,'high','pressure',1,'COMPOUND',NULL,NULL,NULL,'家数40+高位占比35+扩散25；事件窗为空=整支未评',''),
    (@fid2,'high','-',0,'GUARD',NULL,NULL,NULL,'D5交叉信号:抱团瓦解前兆/死亡结构/监管无效/监管生效/龙头错位/板块级压制/龙头易主；强制退潮:被监管空间板断板核按钮、核按钮+空间板唯一','引擎 BoardScoreCalculator 统一施加');

-- ============ 存量库迁移(2026-09-10 five_dim_v2)：可重复执行，缺哪列补哪列 ============
-- t_theme 补催化剂硬度列（主线详情页展示 + D2·催化剂硬度自动取数源）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @theme_v2_adds
  FROM (
  SELECT 1 ord_no, 'catalyst_hardness' col_name,
         'TINYINT DEFAULT 3 COMMENT ''题材催化硬度1-5(5=政策/产业级,1=Pure情绪)；PRD D2·催化剂硬度取数源''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_theme'
       AND COLUMN_NAME  = need.col_name);
SET @theme_v2_sql = IF(@theme_v2_adds IS NULL,
    'SELECT ''t_theme 催化硬度列已齐，本步跳过'' AS theme_v2_migration',
    CONCAT('ALTER TABLE t_theme ', @theme_v2_adds));
PREPARE theme_v2_stmt FROM @theme_v2_sql;
EXECUTE theme_v2_stmt;
DEALLOCATE PREPARE theme_v2_stmt;

-- t_daily_record 补成交额聚集度人工列（PRD D2·要素3，自动取数未覆盖，走手填）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @record_v2_adds
  FROM (
  SELECT 1 ord_no, 'manual_amount_gather_pct' col_name,
         'DECIMAL(6,2) DEFAULT NULL COMMENT ''v2手填：主线成交额聚集度(%)=主线板块成交额/两市成交额''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_daily_record'
       AND COLUMN_NAME  = need.col_name);
SET @record_v2_sql = IF(@record_v2_adds IS NULL,
    'SELECT ''t_daily_record v2 列已齐，本步跳过'' AS record_v2_migration',
    CONCAT('ALTER TABLE t_daily_record ', @record_v2_adds));
PREPARE record_v2_stmt FROM @record_v2_sql;
EXECUTE record_v2_stmt;
DEALLOCATE PREPARE record_v2_stmt;

-- D2 改名「主线明确度」→「主线生态」：INSERT IGNORE 不会改存量行，这里幂等收敛一次（重放无害）。
UPDATE t_scoring_dim SET label = '主线生态'
 WHERE model_id = @fid2 AND dim_key = 'theme_main' AND label = '主线明确度';

-- D2 再改名「主线生态」→「日内核心」（2026-09-10 规则调整：最热行业=日内核心，连续3交易日有热度才收集为主线龙头）。
UPDATE t_scoring_dim SET label = '日内核心'
 WHERE model_id = @fid2 AND dim_key = 'theme_main' AND label = '主线生态';
