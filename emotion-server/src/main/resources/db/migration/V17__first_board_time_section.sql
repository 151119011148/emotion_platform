-- Flyway migration V17: first board time section
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1606-1676 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 4.1) 2026-09-12 时间截面重构：D4 首板生态改纯 T 日（1进2/首板溢价/1进2大面已迁入 D3 低位层）。
UPDATE t_scoring_dim
   SET note='纯T日试错端：首板数量30/首板封板率25/首板炸板率20/封单质量15(均封单0.6+一字占比0.4)/首板题材聚集10；大盘背离时数量×0.85、封板率-10。1进2/首板溢价已迁入连板低位层'
 WHERE model_id=@fid2 AND dim_key='first';

-- 旧 T-1 子项删除（规则随子项一起删）；首板数量/封板率键名保留但权重调整
DELETE FROM t_scoring_sub
 WHERE model_id=@fid2 AND dim_key='first'
   AND sub_key IN ('first_premium','promo_1to2','big_1to2');
UPDATE t_scoring_sub SET weight=0.30,
       note='T日新首板封住家数；大盘背离时×0.85'
 WHERE model_id=@fid2 AND dim_key='first' AND sub_key='first_count';
UPDATE t_scoring_sub SET weight=0.25,
       note='首板封住/(封住+首板炸板)；大盘背离时-10'
 WHERE model_id=@fid2 AND dim_key='first' AND sub_key='first_sealed';
-- 新子项（先按唯一键删，保证 INSERT 幂等可重入）
DELETE FROM t_scoring_sub
 WHERE model_id=@fid2 AND dim_key='first'
   AND sub_key IN ('first_bomb','first_seal_quality','first_theme','first_avg_seal','first_yizi');
INSERT INTO t_scoring_sub
  (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note)
VALUES
  (@fid2,'first','first_bomb','-','首板炸板率',0.20,'BAND_LADDER','first_bomb_rate',3,'首板炸板/(封住+首板炸板)，越低越好'),
  (@fid2,'first','first_seal_quality','-','封单质量',0.15,'WEIGHTED_SUM',NULL,4,'0.6×首板均封单分+0.4×一字首板占比分'),
  (@fid2,'first','first_theme','-','首板题材聚集',0.10,'BAND_LADDER','first_theme_gather_pct',5,'最热行业首板数/首板总数(只数T日新首板)'),
  (@fid2,'first','first_avg_seal','first_seal_quality','首板均封单',0.60,'BAND_LADDER','first_avg_seal_amount',1,'T日首板封单额均值(亿元)'),
  (@fid2,'first','first_yizi','first_seal_quality','一字首板占比',0.40,'BAND_LADDER','first_yizi_ratio',2,'一字首板/能判形态的首板');

-- D4 规则整体换版：旧 5 子规则全删，插纯 T 日阶梯+AGG+GUARD
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='first'
   AND (sub_key IN ('first_count','first_sealed','first_premium','promo_1to2','big_1to2',
                    'first_bomb','first_avg_seal','first_yizi','first_theme','first_seal_quality')
     OR (sub_key='-' AND rule_no=1));
INSERT INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid2,'first','first_count',1,'GTE',60,NULL,95,'首板家数 >=60',''),
  (@fid2,'first','first_count',2,'GTE',40,NULL,80,'>=40',''),
  (@fid2,'first','first_count',3,'GTE',25,NULL,65,'25~39(大盘背离日×0.85)',''),
  (@fid2,'first','first_count',4,'GTE',15,NULL,50,'15~24',''),
  (@fid2,'first','first_count',5,'GTE',8,NULL,35,'8~14',''),
  (@fid2,'first','first_count',6,'ELSE',NULL,NULL,20,'<8',''),
  (@fid2,'first','first_sealed',1,'GTE',80,NULL,95,'首板封板率 >=80%','大盘背离日-10'),
  (@fid2,'first','first_sealed',2,'GTE',70,NULL,80,'>=70%',''),
  (@fid2,'first','first_sealed',3,'GTE',60,NULL,65,'>=60%',''),
  (@fid2,'first','first_sealed',4,'GTE',50,NULL,50,'>=50%',''),
  (@fid2,'first','first_sealed',5,'ELSE',NULL,NULL,30,'<50%',''),
  (@fid2,'first','first_bomb',1,'LTE',10,NULL,95,'首板炸板率 <=10%','越低越好'),
  (@fid2,'first','first_bomb',2,'LTE',20,NULL,80,'<=20%',''),
  (@fid2,'first','first_bomb',3,'LTE',30,NULL,60,'<=30%',''),
  (@fid2,'first','first_bomb',4,'LTE',40,NULL,40,'<=40%',''),
  (@fid2,'first','first_bomb',5,'ELSE',NULL,NULL,20,'>40%',''),
  (@fid2,'first','first_avg_seal',1,'GTE',3,NULL,95,'首板均封单 >=3亿','单位亿元'),
  (@fid2,'first','first_avg_seal',2,'GTE',1.5,NULL,80,'>=1.5亿',''),
  (@fid2,'first','first_avg_seal',3,'GTE',0.8,NULL,60,'>=0.8亿',''),
  (@fid2,'first','first_avg_seal',4,'GTE',0.4,NULL,40,'>=0.4亿',''),
  (@fid2,'first','first_avg_seal',5,'ELSE',NULL,NULL,20,'<0.4亿',''),
  (@fid2,'first','first_yizi',1,'GTE',30,NULL,95,'一字首板占比 >=30%',''),
  (@fid2,'first','first_yizi',2,'GTE',20,NULL,80,'>=20%',''),
  (@fid2,'first','first_yizi',3,'GTE',10,NULL,60,'>=10%',''),
  (@fid2,'first','first_yizi',4,'GTE',5,NULL,40,'>=5%',''),
  (@fid2,'first','first_yizi',5,'ELSE',NULL,NULL,20,'<5%',''),
  (@fid2,'first','first_theme',1,'GTE',40,NULL,95,'首板题材聚集度 >=40%','最热行业首板占比'),
  (@fid2,'first','first_theme',2,'GTE',30,NULL,82,'>=30%',''),
  (@fid2,'first','first_theme',3,'GTE',20,NULL,68,'>=20%',''),
  (@fid2,'first','first_theme',4,'GTE',10,NULL,48,'>=10%',''),
  (@fid2,'first','first_theme',5,'ELSE',NULL,NULL,28,'<10%',''),
  (@fid2,'first','first_seal_quality',0,'AGG',NULL,NULL,NULL,'0.6×首板均封单分 + 0.4×一字首板占比分',''),
  (@fid2,'first','-',1,'GUARD',NULL,NULL,NULL,'大盘背离(大盘分<40/强制退潮/跌停≥20):首板数量×0.85、首板封板率-10','引擎applyFirstCalibration');
