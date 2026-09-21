-- Flyway migration V15: board d3 caliber
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1504-1545 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 4) 2026-09-12 连板维 D3 口径修正（存量库，可重复执行）：
--    数量高度读数 board_total_count→max_height 按 H 给分；时间截面 PRD 权重晋级30/溢价25/大面20/炸板15/数量10；
--    低位层纳入1进2（prem tier board=1、big 昨首板）；登记小样本/空间/背离/外溢修正与闸门 GUARD。
UPDATE t_scoring_dim
   SET note='时间截面T-1→T：晋级30/溢价25/大面20/炸板质量15/数量高度10；低位层=1进2(昨首板种子)；修正：小样本×0.8、空间未打开晋级×0.9、大盘背离溢价×0.8、全局跌停外溢-35/-20/-8；闸门：中位吹哨×0.8、大盘背离×0.85(龙头错位只出信号,扣分归D5)'
 WHERE model_id=@fid2 AND dim_key='board';

UPDATE t_scoring_sub SET weight=0.30 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='promo';
UPDATE t_scoring_sub SET weight=0.25 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='premium';
UPDATE t_scoring_sub
   SET weight=0.10, source_key='max_height',
       note='按空间板H给分:H≥7=95/5-6=70/H=4=25/H=3=15/≤2=5'
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='count_height';

-- 数量高度阶梯换版：旧 5 档（连板家数）整体删掉再插 H 阶梯
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='board' AND sub_key='count_height';
INSERT INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid2,'board','count_height',1,'GTE',7,NULL,95,'H>=7 空间打开','2026-09-12 改按空间板H给分'),
  (@fid2,'board','count_height',2,'GTE',5,NULL,70,'H=5~6',''),
  (@fid2,'board','count_height',3,'GTE',4,NULL,25,'H=4 空间未打开',''),
  (@fid2,'board','count_height',4,'GTE',3,NULL,15,'H=3',''),
  (@fid2,'board','count_height',5,'ELSE',NULL,NULL,5,'H<=2','');

-- 修正/闸门 GUARD 登记行：按精确键先删后插，重放无害；promo rule_no=0 的吹哨 GUARD 保留
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='board'
   AND ((sub_key='promo' AND rule_no IN (1,2))
     OR (sub_key='premium' AND rule_no=1)
     OR (sub_key='bigloss' AND rule_no=1)
     OR (sub_key='-' AND rule_no=0 AND formula LIKE '维分闸门%'));
INSERT INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid2,'board','promo',1,'GUARD',NULL,NULL,NULL,'小样本:中位晋级昨日基数<5家→中位晋级叶×0.8','引擎BoardScoreCalculator常量'),
  (@fid2,'board','promo',2,'GUARD',NULL,NULL,NULL,'空间未打开:H<5(无高位层,5板+)→晋级结构×0.8',''),
  (@fid2,'board','premium',1,'GUARD',NULL,NULL,NULL,'大盘背离:大盘分<40 或 红盘率<20%→溢价结构×0.8',''),
  (@fid2,'board','bigloss',1,'GUARD',NULL,NULL,NULL,'全局跌停外溢:跌停≥20/≥10/≥5 → 大面结构-35/-20/-8',''),
  (@fid2,'board','-',0,'GUARD',NULL,NULL,NULL,'维分闸门:中位吹哨(晋级<15%或中位大面≥3)×0.8;大盘背离(大盘分<40/强制退潮/跌停≥20)×0.85。龙头错位2026-09-12起只输出信号不再扣分(扣分归D5阵眼一致性)','引擎统一施加,见连板生态打分表尾');
