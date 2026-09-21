-- Flyway migration V11: market stock shape fields
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1299-1323 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 存量库迁移(2026-09-10 盘面形态三字段)：可重复执行，缺哪列补哪列 ============
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @stock_shape_adds
  FROM (
  SELECT 1 ord_no, 'seal_amount' col_name,
         'DECIMAL(18,2) DEFAULT NULL COMMENT ''封单额(元)=东财fund,涨停池收盘封单资金''' col_ddl
  UNION ALL SELECT 2 ord_no, 'first_seal_time' col_name,
         'INT DEFAULT NULL COMMENT ''首次封板时间HHMMSS(fbt),判一字/T字用''' col_ddl
  UNION ALL SELECT 3 ord_no, 'last_seal_time' col_name,
         'INT DEFAULT NULL COMMENT ''最后封板时间HHMMSS(lbt)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_market_stock'
       AND COLUMN_NAME  = need.col_name);
SET @stock_shape_sql = IF(@stock_shape_adds IS NULL,
    'SELECT ''t_market_stock 形态三列已齐，本步跳过'' AS stock_shape_migration',
    CONCAT('ALTER TABLE t_market_stock ', @stock_shape_adds));
PREPARE stock_shape_stmt FROM @stock_shape_sql;
EXECUTE stock_shape_stmt;
DEALLOCATE PREPARE stock_shape_stmt;

-- 重放完成后如需把历史按 v2 口径重算：POST /api/records/recalc-all。
