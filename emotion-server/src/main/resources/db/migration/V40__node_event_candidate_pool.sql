-- Flyway migration V40: t_node_event 加「接位候选池」一列
-- -----------------------------------------------------------------------------
-- 背景：接位判据改版。原来固定取「老龙断板当天（D0）的全部二板」，现在认的是<b>老龙放量那一天</b>：
--       断板前一日已放量 → 照旧取 D0 的二连板（那批票本就是放量日的首板延续过来的）；
--       前一日没放量 → 往后找到第一个放量日，取那天的首板。
--       哪天、几板从此不是一个常量，得跟其他八个建议值一起落库、一起进采纳指纹，
--       否则他点的是"D0 二板"的那份结论、库里存的却是别的口径。
--
-- 写法：沿用 V26/V34 的套路——SET @var 查 information_schema 判存在 → IF() 选真 DDL 或
--       'SELECT 0' 空操作 → PREPARE/EXECUTE/DEALLOCATE。禁用 CREATE PROCEDURE / BEGIN...END：
--       Flyway 6.5.7 按分号切语句，复合语句体会在第一个内部分号处切碎（ERROR 1064）。
--
-- 版本号为什么是 40：38 现在归 position 那条（了结价），39 归破壁注释那条（原来也叫 38，撞车之后改的名），
--       这里排到 40 只是接着往下走，不参与那场重排。
--
-- 幂等：新库回放 / 存量库重复执行都不报错；列已存在则空操作。
--
-- 存量行的 NULL 语义：这一列 NULL = 判据改版之前落的，当时的口径就是 D0 全部二板，
-- 不是"没取候选池"。改版后第一次复算必然报出「候选池：无 → 现在算出 2026-09-23 首板」这条 diff，
-- 那是判据变了该有的痕迹；他不点采纳就不落，平台不替他改历史。
-- -----------------------------------------------------------------------------

-- 排在 d0_candidates 之后（同属"D0 侧的原料"这一族）；极老的库可能没有那一列，退化为追加表尾。
SET @v40_has_d0c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'd0_candidates');
SET @v40_after = IF(@v40_has_d0c > 0, ' AFTER d0_candidates', '');

SET @v40_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'candidate_pool');
SET @v40_ddl = IF(@v40_c = 0,
    CONCAT('ALTER TABLE t_node_event ADD COLUMN candidate_pool VARCHAR(32) DEFAULT NULL COMMENT ''接位候选池：这次采纳认下的「哪天+几板」，形如 2026-09-23 首板；NULL=判据改版前落的老口径(D0全部二板)''', @v40_after),
    'SELECT 0');
PREPARE v40_stmt FROM @v40_ddl; EXECUTE v40_stmt; DEALLOCATE PREPARE v40_stmt;
