-- Flyway migration V13: objective subjective split
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1339-1368 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 存量库迁移(2026-09-13 客观/主观隔离)：t_daily_record 客观九数 → t_market_daily ============
-- 可重复执行：uk_trade_date 上 INSERT IGNORE，已搬过的日子重放不动；旧列冻结留痕、不 DELETE。
-- 同日多用户行取各列 MAX：MAX 自动跳过 NULL，等于"谁有非空值就用谁的"（客观事实本应一致）。
-- HAVING 过滤九列全 NULL 的日子，不给客观表造空壳。
INSERT IGNORE INTO t_market_daily
  (trade_date, max_consecutive_limit, limit_up_count, limit_down_count, up_count, down_count,
   yesterday_limit_premium, broken_board_rate, big_loss_count, total_volume, created_at, updated_at)
SELECT trade_date,
       MAX(max_consecutive_limit),
       MAX(limit_up_count),
       MAX(limit_down_count),
       MAX(up_count),
       MAX(down_count),
       MAX(yesterday_limit_premium),
       MAX(broken_board_rate),
       MAX(big_loss_count),
       MAX(total_volume),
       NOW(), NOW()
  FROM t_daily_record
 GROUP BY trade_date
HAVING MAX(max_consecutive_limit) IS NOT NULL
    OR MAX(limit_up_count)       IS NOT NULL
    OR MAX(limit_down_count)     IS NOT NULL
    OR MAX(up_count)             IS NOT NULL
    OR MAX(down_count)           IS NOT NULL
    OR MAX(yesterday_limit_premium) IS NOT NULL
    OR MAX(broken_board_rate)    IS NOT NULL
    OR MAX(big_loss_count)       IS NOT NULL
    OR MAX(total_volume)         IS NOT NULL;
