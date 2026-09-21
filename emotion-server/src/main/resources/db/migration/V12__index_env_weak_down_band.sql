-- Flyway migration V12: index env weak down band
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1324-1338 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 存量库迁移(2026-09-11 D1 指数环境补「三指全绿弱跌」档) ============
-- 背景：原 index_env 只有 100/40/20/ELSE60，三指全绿但均未破-1% 落进兜底 60（虚高）。
-- 新增 rule_no=4 = 35（弱跌日），原 ELSE60 顺延 rule_no=5。INSERT IGNORE 改不了存量 rule_no=4，
-- 这里幂等收敛一次（新库这两条与种子同值，重放无害）。
UPDATE t_scoring_rule
   SET operator='COMPOUND', threshold_low=NULL, threshold_high=NULL, score=35,
       formula='三指全绿但均未破-1%(弱跌日)', note='STRATEGY:三指均<0且均≥-1%,Java算'
 WHERE model_id IN (@fid, @fid2) AND dim_key='market' AND sub_key='index_env' AND rule_no=4;

INSERT IGNORE INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid, 'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标'),
  (@fid2,'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标');
