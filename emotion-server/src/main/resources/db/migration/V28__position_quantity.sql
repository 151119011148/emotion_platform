-- Flyway migration V28: 持仓台账补股数（t_position.quantity / t_position_history.quantity）
-- -----------------------------------------------------------------------------
-- 背景：t_position 建表（V4）时只有 cost_price / current_price 两个单价，没有股数。
--       没有股数就没有金额——「总成本合计」只能把不同股票的股价相加（10 元的票 + 100 元的票
--       = 110），浮动盈亏额同样算不出来，仓位占比更无从谈起。复盘页汇总卡因此只能退到
--       「等权平均浮动% / 盈亏家数」这些与股数无关的口径。
--       补上 quantity 之后：成本额 = Σ(股数 × 成本)、市值 = Σ(股数 × 现价)、盈亏额 = 两者之差，
--       这三个数才第一次在数学上成立。
--
-- 快照表 t_position_history 同步补：replaceForDate 每次整表替换前会把当天旧行整批快照过去，
--       历史表少这一列的话，一次保存就把股数静默丢掉了，回滚也捞不回来。
--
-- 可空、不设默认值：股数是「你记了才算数」的东西，空 = 未填，汇总按无股数回退到等权口径，
--       不要用 0 冒充——0 会让人以为「这只票一股没买」，与「不知道买了多少」不是一回事。
--
-- 写法：沿用 V26 的幂等补列套路（information_schema 判存在 → IF() 选真 DDL 或 'SELECT 0'
--       → PREPARE/EXECUTE），全程不用复合语句——Flyway 6.5.7 的 MySQL 解析器按分号切语句，
--       CREATE PROCEDURE / BEGIN...END 会被在第一个内部分号处切成碎片、报 ERROR 1064。
-- -----------------------------------------------------------------------------

-- t_position.quantity 落在 current_price 之后：读表时「代码 名称 成本 现价 股数」连着看最顺。
SET @v28_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND COLUMN_NAME = 'quantity');
SET @v28_ddl = IF(@v28_c = 0,
    'ALTER TABLE t_position ADD COLUMN quantity INT DEFAULT NULL COMMENT ''持仓股数（手记）；有了它才算得出成本额/市值/盈亏额；NULL=未填，汇总按无股数处理'' AFTER current_price',
    'SELECT 0');
PREPARE v28_stmt FROM @v28_ddl; EXECUTE v28_stmt; DEALLOCATE PREPARE v28_stmt;

-- t_position_history.quantity 同上，落在 current_price 之后，与 t_position 列序对齐。
SET @v28_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position_history' AND COLUMN_NAME = 'quantity');
SET @v28_ddl = IF(@v28_c = 0,
    'ALTER TABLE t_position_history ADD COLUMN quantity INT DEFAULT NULL COMMENT ''被替换时的持仓股数快照'' AFTER current_price',
    'SELECT 0');
PREPARE v28_stmt FROM @v28_ddl; EXECUTE v28_stmt; DEALLOCATE PREPARE v28_stmt;
