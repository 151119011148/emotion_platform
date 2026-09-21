-- Flyway migration V16: board d3 three layer v2 2
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1546-1605 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 4.0.2) 2026-09-13 连板维三层简化 v2.2（存量库，可重复执行）：
-- 数量高度 10/晋级30/溢价25/大面20/炸板15（和=1.00）；四层→三层(低=2/中=3-4/高=5板+，高对齐 D5)；
-- 价格层权重：晋级0.50/0.35/0.15、溢价0.45/0.35/0.20、大面0.45/0.35/0.20；删 promo/premium/bigloss 的 midhigh/top 层；
-- 大面结构每层改为「家数 big_*」+「大面率 big_*_rate（大面/该层昨种子）」各 50% 合成；
-- 空间未打开晋级系数 0.9→0.8。
UPDATE t_scoring_dim
   SET note='时间截面T-1→T：数量高度10/晋级30/溢价25/大面20/炸板15；三层(低=2/中=3-4/高=5板+对齐D5)；低位=1进2；高位层只留接力效率低权重(抱团监管反包归D5)、H<5高位N/A剔除分母；修正：小样本×0.8、H<5空间未打开晋级×0.8、大盘背离溢价×0.8、全局跌停外溢-35/-20/-8；闸门中位吹哨×0.8、大盘背离×0.85'
 WHERE model_id=@fid2 AND dim_key='board';
UPDATE t_scoring_sub SET weight=0.10, sort_no=1 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='count_height';
UPDATE t_scoring_sub SET weight=0.30, sort_no=2 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='promo';
UPDATE t_scoring_sub SET weight=0.25, sort_no=3 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='premium';
UPDATE t_scoring_sub SET weight=0.20, sort_no=4 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='bigloss';
UPDATE t_scoring_sub SET weight=0.15, sort_no=5 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='broken_quality';

-- promo/premium 四层→三层收敛：删 midhigh/top 层与规则，插 high 层，调整 low/mid 权重
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key IN
  ('promo_midhigh','promo_top','promo_high','premium_midhigh','premium_top','premium_high');
DELETE FROM t_scoring_sub
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key IN
  ('promo_midhigh','promo_top','promo_high','premium_midhigh','premium_top','premium_high');
UPDATE t_scoring_sub SET weight=0.50, note='2板(固定)'  WHERE model_id=@fid2 AND dim_key='board' AND sub_key='promo_low';
UPDATE t_scoring_sub SET weight=0.35, note='3-4板 吹哨锚点' WHERE model_id=@fid2 AND dim_key='board' AND sub_key='promo_mid';
UPDATE t_scoring_sub SET weight=0.45 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='premium_low';
UPDATE t_scoring_sub SET weight=0.35 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='premium_mid';
INSERT INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
  (@fid2,'board','promo_high','promo','高位晋级',0.15,'BAND_LADDER','jr_high',3,'5板+ 对齐高位生态D5'),
  (@fid2,'board','premium_high','premium','高位溢价',0.20,'BAND_LADDER','prem_high',3,'');
INSERT INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
  (@fid2,'board','promo_high',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_high',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_high',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_high',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_high',5,'ELSE',NULL,NULL,20,'',''),
  (@fid2,'board','premium_high',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_high',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_high',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_high',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_high',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_high',6,'ELSE',NULL,NULL,5,'','');

-- 大面结构层级重构：删除旧 BAND_LADDER 层，改插层复合(WEIGHTED_SUM) + 家数/率两个叶子
DELETE FROM t_scoring_sub
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key LIKE 'bigloss_%';
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key LIKE 'bigloss_%';
INSERT INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
  (@fid2,'board','bigloss_low',      'bigloss','低位大面',   0.45,'WEIGHTED_SUM',NULL,1,'家数+大面率各50%'),
  (@fid2,'board','bigloss_mid',      'bigloss','中位大面',   0.35,'WEIGHTED_SUM',NULL,2,'家数+大面率各50%'),
  (@fid2,'board','bigloss_high',     'bigloss','高位大面',   0.20,'WEIGHTED_SUM',NULL,3,'家数+大面率各50% 对齐D5'),
  (@fid2,'board','bigloss_low_cnt',    'bigloss_low','低位大面·家数',  0.50,'BAND_LADDER','big_low',1,''),
  (@fid2,'board','bigloss_low_rate',   'bigloss_low','低位大面·大面率',  0.50,'BAND_LADDER','big_low_rate',2,''),
  (@fid2,'board','bigloss_mid_cnt',    'bigloss_mid','中位大面·家数',  0.50,'BAND_LADDER','big_mid',1,''),
  (@fid2,'board','bigloss_mid_rate',   'bigloss_mid','中位大面·大面率',  0.50,'BAND_LADDER','big_mid_rate',2,''),
  (@fid2,'board','bigloss_high_cnt',   'bigloss_high','高位大面·家数',  0.50,'BAND_LADDER','big_high',1,''),
  (@fid2,'board','bigloss_high_rate',  'bigloss_high','高位大面·大面率', 0.50,'BAND_LADDER','big_high_rate',2,'');
INSERT INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
  (@fid2,'board','bigloss_low_cnt',1,'EQ',0,NULL,95,'大面家数 =0',''),(@fid2,'board','bigloss_low_cnt',2,'LTE',2,NULL,80,'1~2',''),(@fid2,'board','bigloss_low_cnt',3,'LTE',5,NULL,60,'3~5',''),(@fid2,'board','bigloss_low_cnt',4,'LTE',10,NULL,35,'6~10',''),(@fid2,'board','bigloss_low_cnt',5,'ELSE',NULL,NULL,10,'>10',''),
  (@fid2,'board','bigloss_mid_cnt',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_mid_cnt',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_mid_cnt',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_mid_cnt',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_mid_cnt',5,'ELSE',NULL,NULL,10,'',''),
  (@fid2,'board','bigloss_high_cnt',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_high_cnt',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_high_cnt',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_high_cnt',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_high_cnt',5,'ELSE',NULL,NULL,10,'',''),
  (@fid2,'board','bigloss_low_rate',1,'EQ',0,NULL,95,'大面率 =0%',''),(@fid2,'board','bigloss_low_rate',2,'LTE',10,NULL,85,'<=10%',''),(@fid2,'board','bigloss_low_rate',3,'LTE',20,NULL,70,'<=20%',''),(@fid2,'board','bigloss_low_rate',4,'LTE',35,NULL,50,'<=35%',''),(@fid2,'board','bigloss_low_rate',5,'LTE',50,NULL,30,'<=50%',''),(@fid2,'board','bigloss_low_rate',6,'ELSE',NULL,NULL,10,'>50%',''),
  (@fid2,'board','bigloss_mid_rate',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_mid_rate',2,'LTE',10,NULL,85,'',''),(@fid2,'board','bigloss_mid_rate',3,'LTE',20,NULL,70,'',''),(@fid2,'board','bigloss_mid_rate',4,'LTE',35,NULL,50,'',''),(@fid2,'board','bigloss_mid_rate',5,'LTE',50,NULL,30,'',''),(@fid2,'board','bigloss_mid_rate',6,'ELSE',NULL,NULL,10,'',''),
  (@fid2,'board','bigloss_high_rate',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_high_rate',2,'LTE',10,NULL,85,'',''),(@fid2,'board','bigloss_high_rate',3,'LTE',20,NULL,70,'',''),(@fid2,'board','bigloss_high_rate',4,'LTE',35,NULL,50,'',''),(@fid2,'board','bigloss_high_rate',5,'LTE',50,NULL,30,'',''),(@fid2,'board','bigloss_high_rate',6,'ELSE',NULL,NULL,10,'','');

-- 空间未打开晋级系数 0.9→0.8（GUARD 展示行）
UPDATE t_scoring_rule
   SET formula='空间未打开:H<5(无高位层)→晋级结构×0.8'
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='promo' AND rule_no=2;
