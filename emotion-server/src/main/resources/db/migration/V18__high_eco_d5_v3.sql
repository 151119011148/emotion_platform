-- Flyway migration V18: high eco d5 v3
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1677-1806 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 存量库迁移(2026-09-12 D5 高位生态融合 v3)：可重复执行 ============
-- 背景：PRD D5 把原"阵眼(龙头分工,15%)"与"抱团+监管"融合成"高位生态(25%)"，
--   五维权重重分配 22/18/22/13/25；阵眼身份改人工 t_anchor(起止区间)；龙头错位不再扣分只出信号。
-- 与历次迁移同：新库走上面的 INSERT IGNORE 种子；本段只收敛存量库，DELETE+普通 INSERT 保证幂等。

-- 1) t_daily_record 加 D5 维分列（旧 score_anchor 冻结留痕，不再写新值）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @d5_col_adds
  FROM (
  SELECT 1 ord_no, 'score_high' col_name,
         'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·高位生态分(0-100,阵眼个体35+抱团资金30+监管压制20+监管反馈15)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_daily_record'
       AND COLUMN_NAME  = need.col_name);
SET @d5_col_sql = IF(@d5_col_adds IS NULL,
    'SELECT ''t_daily_record score_high 列已齐，本步跳过'' AS d5_col_migration',
    CONCAT('ALTER TABLE t_daily_record ', @d5_col_adds));
PREPARE d5_col_stmt FROM @d5_col_sql;
EXECUTE d5_col_stmt;
DEALLOCATE PREPARE d5_col_stmt;

-- 2) t_anchor.role 扩到 D5 四角色（旧 CYCLE/LEADER 保留，按总龙头权重参与）。
ALTER TABLE t_anchor
  MODIFY COLUMN role VARCHAR(10) NOT NULL DEFAULT 'ZONG'
  COMMENT 'ZONG=总龙头/FENZHI=分支龙/BUZHANG=补涨龙/FANBAO=反包龙(0.5/0.2/0.2/0.1)；兼容旧值 CYCLE/LEADER(按总龙)' ;

-- 3) 模型展示名/说明收敛（model_key 保持 five_dim_v2 不变）。
UPDATE t_scoring_model
   SET name='五维双层情绪模型 v3(D5融合)',
       note='D5融合(2026-09-12)：D1大盘22/D2日内核心18/D3连板22/D4首板13/D5高位生态25=阵眼个体35(人工t_anchor)+抱团资金30+监管压制20+监管反馈15。阵眼身份人工配置(起止区间)；监管期现推 SEVERE/EXCH 10日、ZD 5日只展示'
 WHERE model_key='five_dim_v2';

-- 4) 维度权重重分配 + anchor 行整体改成 high（dim_key/列/权重/标签）。
UPDATE t_scoring_dim SET weight=0.22,
       note='指数环境35/量能25/广度20/涨跌停20'
 WHERE model_id=@fid2 AND dim_key='market';
UPDATE t_scoring_dim SET weight=0.18,
       note='日内最热行业5要素：涨停聚集度25/高度聚集度25/成交额聚集度20/催化剂硬度15/持续性15；连续3交易日热度≥5才收集为主线龙头；龙头错位只输出信号不扣分(扣分归D5阵眼一致性)'
 WHERE model_id=@fid2 AND dim_key='theme_main';
-- board/first 维的 note 与子权重以迁移段 4)/4.1) 为准（时间截面 PRD），此处不覆盖。
UPDATE t_scoring_dim SET weight=0.13 WHERE model_id=@fid2 AND dim_key='first';
-- anchor→high 幂等收敛：high 已存在（重放）时直接删除残留 anchor 行（其 sub/rule 在步骤5先删）；
-- high 不存在时才改名，避免唯一键 (dim_no,dim_key) 冲突中断脚本。
DELETE FROM t_scoring_dim
 WHERE model_id=@fid2 AND dim_key='anchor'
   AND EXISTS (SELECT 1 FROM (SELECT 1 FROM t_scoring_dim WHERE model_id=@fid2 AND dim_key='high') AS x);
UPDATE t_scoring_dim
   SET dim_key='high', dim_no=5, label='高位生态', weight=0.25, sort_no=5,
       record_column='score_high',
       note='D5融合：阵眼个体35(人工阵眼:行为40/高度25/封板20/主线一致15)+抱团资金30(结构60/强度40)+监管压制20(家数/高位占比/扩散)+监管反馈15'
 WHERE model_id=@fid2 AND dim_key='anchor';

-- 5) 旧 anchor(龙头分工) sub/rule 清掉，换 high 维全树（先删后插，重放无害）。
DELETE FROM t_scoring_sub WHERE model_id=@fid2 AND dim_key IN ('anchor','high');
DELETE FROM t_scoring_rule WHERE model_id=@fid2 AND dim_key IN ('anchor','high');
INSERT INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'high','anchor_ind', '-','阵眼个体',  0.35,'WEIGHTED_SUM',NULL,1,'人工 t_anchor 在位阵眼(起止区间内)：行为40/高度25/封板20/主线一致15；多阵眼按角色 总龙0.5/分支0.2/补涨0.2/反包0.1 加权；无在位阵眼=整支未评'),
    (@fid2,'high','coalition',  '-','抱团与资金',0.30,'WEIGHTED_SUM',NULL,2,'结构质量60(高位家数占比/封单集中/空间唯一/梯队)+资金强度40(高位溢价/高位晋级)；高位阈值 H>=5?5:max(3,H-1)'),
    (@fid2,'high','pressure',   '-','监管压制',  0.20,'WEIGHTED_SUM',NULL,3,'SEVERE/EXCH 在列：监管家数40/高位监管占比35/板块扩散25；事件窗为空=整支未评(不是100)'),
    (@fid2,'high','feedback',   '-','监管反馈',  0.15,'STRATEGY','d5_feedback',4,'STRATEGY 五态：核按钮0/断板大跌25/绿盘50/红盘85/继续涨停70(读 d5f_nuke/d5f_avg)'),
    (@fid2,'high','d5a_action', 'anchor_ind','龙头行为',0.40,'MANUAL','d5a_action',1,'晋级100/反包80/抗跌60/断板20/核按钮0(HighEcoMetricsService算)'),
    (@fid2,'high','d5a_height', 'anchor_ind','龙头高度',0.25,'MANUAL','d5a_height',2,'板数 vs H：=H 100/H-1 80/H-2 60/更低30(断板取昨板)'),
    (@fid2,'high','d5a_seal',   'anchor_ind','封板质量',0.20,'MANUAL','d5a_seal',3,'一字100/换手回封80/烂板40/断板核按钮0'),
    (@fid2,'high','d5a_consist','anchor_ind','主线一致性',0.15,'MANUAL','d5a_consist',4,'阵眼行业==日内核心100，否则60(错位)'),
    (@fid2,'high','c_structure','coalition','结构质量',0.60,'WEIGHTED_SUM',NULL,1,''),
    (@fid2,'high','c_strength', 'coalition','资金强度',0.40,'WEIGHTED_SUM',NULL,2,''),
    (@fid2,'high','d5c_ratio','c_structure','高位家数占比',0.30,'BAND_LADDER','d5c_ratio',1,'高位家数/连板(≥2板)家数 %：20-40健康100/10-20与40-60=70/>60过度抱团40/<10无抱团50'),
    (@fid2,'high','d5c_seal', 'c_structure','高位封单集中',0.25,'BAND_LADDER','d5c_seal',2,'高位 seal_amount/全市场涨停封单 %：≤50=90/≤70=70/>70=40'),
    (@fid2,'high','d5c_top',  'c_structure','空间板唯一性',0.25,'BAND_LADDER','d5c_top',3,'consecutive==H 家数：2-3互相支撑100/≥4分散70/唯一孤军50'),
    (@fid2,'high','d5c_tier', 'c_structure','梯队支撑度',0.20,'MANUAL','d5c_tier',4,'2..H 板无断层100/有断层40(HighEcoMetricsService算)'),
    (@fid2,'high','d5c_prem', 'c_strength','高位溢价',0.50,'BAND_LADDER','d5c_prem',1,'高位档 t_premium_tier 加权均溢价 %：>3=95/1-3=80/0-1=65/-3~0=35/<-3=10'),
    (@fid2,'high','d5c_jr',   'c_strength','高位晋级率',0.50,'BAND_LADDER','d5c_jr',2,'%：≥60=95/40-60=80/25-40=65/15-25=45/<15=20(今高位 ÷ 昨≥highThreshold-1板基数)'),
    (@fid2,'high','d5p_count','pressure','监管家数',0.40,'BAND_LADDER','d5p_count',1,'SEVERE/EXCH 在列家数：0=100/≤2=80/≤5=55/≤10=30/>10=10'),
    (@fid2,'high','d5p_high_ratio','pressure','高位监管占比',0.35,'BAND_LADDER','d5p_high_ratio',2,'在列股中高位股占比 %：0=100/≤30=70/≤60=40/>60=15'),
    (@fid2,'high','d5p_spread','pressure','监管扩散度',0.25,'BAND_LADDER','d5p_spread',3,'同行业最多监管家数：0=100/1=85/≤3=50/>3=20');
INSERT INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
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
    (@fid2,'high','d5p_spread',4,'ELSE',NULL,NULL,20,'>3 家 板块级点名',''),
    (@fid2,'high','feedback',1,'COMPOUND',NULL,NULL,0,'监管股核按钮/跌停 ≥1 只 → 0','STRATEGY:d5_feedback,Java算'),
    (@fid2,'high','feedback',2,'COMPOUND',5,NULL,70,'无核按钮且均涨>5%(继续涨停=监管无效,情绪亢奋)',''),
    (@fid2,'high','feedback',3,'COMPOUND',0,NULL,85,'均涨>0% 红盘温和消化',''),
    (@fid2,'high','feedback',4,'COMPOUND',-7,NULL,50,'均涨 -7%~0% 绿盘分歧',''),
    (@fid2,'high','feedback',5,'ELSE',NULL,NULL,25,'均涨≤-7% 压制明显',''),
    (@fid2,'high','anchor_ind',1,'COMPOUND',NULL,NULL,NULL,'行为40+高度25+封板20+一致15；多阵眼角色加权(总龙0.5/分支0.2/补涨0.2/反包0.1)','MANUAL叶由 HighEcoMetricsService 现算'),
    (@fid2,'high','coalition',1,'COMPOUND',NULL,NULL,NULL,'结构60(占比30/封单25/唯一25/梯队20)+强度40(溢价50/晋级50)',''),
    (@fid2,'high','pressure',1,'COMPOUND',NULL,NULL,NULL,'家数40+高位占比35+扩散25；事件窗为空=整支未评',''),
    (@fid2,'high','-',0,'GUARD',NULL,NULL,NULL,'D5交叉信号:抱团瓦解前兆/死亡结构/监管无效/监管生效/龙头错位/板块级压制/龙头易主；强制退潮:被监管空间板断板核按钮、核按钮+空间板唯一','引擎 BoardScoreCalculator 统一施加');

-- 6) D3 龙头错位闸门下线（只保留信号输出）：GUARD 行文案收敛。
UPDATE t_scoring_rule
   SET formula='维分闸门:中位吹哨×0.8;大盘背离(大盘分<40/强制退潮/跌停≥20)×0.85。龙头错位2026-09-12起只输出信号不再扣分(扣分归D5阵眼一致性)'
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='-' AND rule_no=0;

-- 重放完成后历史按新口径重算：POST /api/records/recalc-all（旧 score_anchor 列冻结留痕）。
