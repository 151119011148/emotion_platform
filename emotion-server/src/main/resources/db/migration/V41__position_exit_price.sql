-- Flyway migration V38: 持仓台账补「当日了结」两列（t_position.sell_price / sell_qty，快照表同步）
-- -----------------------------------------------------------------------------
-- 背景：台账是「一天一只一行」的快照表，卖出这件事一直没有落点——
--       ① 卖出日只隐含在 status='今日清仓' 那行的 trade_date 里，前端靠反推；
--          而「竞价走了半仓」这种减仓，行还是持仓中，那天根本没被记成一笔卖出。
--       ② 卖出价完全没有字段。current_price 是当日收盘价（服务端还会从涨停池自动回填），
--          不是成交价。冲高卖了又回落的票，用收盘价当卖价会白亏几个点。
--       于是「近7日已实现」「胜率」「卖出端纪律分」全都只能用清仓行上手记的 float_pct，
--       而那行的 float_pct 经常是空的（成本留在上一天的持仓中行上）。
--
-- 口径（与用户确认）：一组「当日成交均价 + 当日卖出股数」，不建独立成交流水表。
--       一天一笔了结——清仓时 sell_qty 等于持仓，减仓时 sell_qty 是一部分、status 仍是持仓中。
--       同一天分两笔不同价（竞价半仓 + 炸板半仓）记成一个均价，成交价明细写进 action 自由文本。
--       缺价不兜底：sell_price 为 NULL 就是「不知道卖了多少钱」，已实现类统计只算填了的那几笔，
--       绝不拿收盘价冒充成交价。NULL 与 0 的区分沿用 quantity 的规矩（0 是「一股没卖」）。
--
-- 写法沿用 V26/V34：SET @var 判存在 → IF() 选真 DDL 或 'SELECT 0' → PREPARE/EXECUTE。
-- 禁 CREATE PROCEDURE / BEGIN...END：Flyway 按分号切语句，复合体会被切碎（ERROR 1064）。
-- 幂等：新库回放与存量库重复执行都不报错。
-- -----------------------------------------------------------------------------

-- 排在 quantity 之后：读表时「成本 现价 股数 卖价 卖量」连成一组。极老的库可能没有 quantity，
-- 那就退化为落在 current_price 之后，再没有就追加到表尾。
SET @v38_qty = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND COLUMN_NAME = 'quantity');
SET @v38_cur = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND COLUMN_NAME = 'current_price');
SET @v38_anchor = IF(@v38_qty > 0, ' AFTER quantity', IF(@v38_cur > 0, ' AFTER current_price', ''));

SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND COLUMN_NAME = 'sell_price');
SET @v38_ddl = IF(@v38_c = 0,
    CONCAT('ALTER TABLE t_position ADD COLUMN sell_price DECIMAL(12,3) DEFAULT NULL COMMENT ''当日了结成交均价（手记）：真实成交价，不是收盘价；NULL=没填，该行不进已实现口径''', @v38_anchor),
    'SELECT 0');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND COLUMN_NAME = 'sell_qty');
SET @v38_ddl = IF(@v38_c = 0,
    CONCAT('ALTER TABLE t_position ADD COLUMN sell_qty INT DEFAULT NULL COMMENT ''当日卖出股数：清仓=等于持仓，减仓=一部分（status 仍持仓中）；NULL=没填（不等于 0 股）''', @v38_anchor),
    'SELECT 0');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- t_position_history 是整表替换前的快照，少了这两列，一次保存就把成交价丢了、回滚也捞不回来。
SET @v38_hqty = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position_history' AND COLUMN_NAME = 'quantity');
SET @v38_hcur = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position_history' AND COLUMN_NAME = 'current_price');
SET @v38_hanchor = IF(@v38_hqty > 0, ' AFTER quantity', IF(@v38_hcur > 0, ' AFTER current_price', ''));

SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position_history' AND COLUMN_NAME = 'sell_price');
SET @v38_ddl = IF(@v38_c = 0,
    CONCAT('ALTER TABLE t_position_history ADD COLUMN sell_price DECIMAL(12,3) DEFAULT NULL COMMENT ''被替换时的当日成交均价快照''', @v38_hanchor),
    'SELECT 0');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position_history' AND COLUMN_NAME = 'sell_qty');
SET @v38_ddl = IF(@v38_c = 0,
    CONCAT('ALTER TABLE t_position_history ADD COLUMN sell_qty INT DEFAULT NULL COMMENT ''被替换时的当日卖出股数快照''', @v38_hanchor),
    'SELECT 0');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- 「按标的查它哪天以什么价了结过」是卖出流水的取数路径，跟 idx_user_code_date 同族但过滤条件不同。
SET @v38_i = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_position' AND INDEX_NAME = 'idx_user_sell');
SET @v38_ddl = IF(@v38_i = 0,
    'ALTER TABLE t_position ADD INDEX idx_user_sell (user_id, sell_price, trade_date)',
    'SELECT 0');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;
