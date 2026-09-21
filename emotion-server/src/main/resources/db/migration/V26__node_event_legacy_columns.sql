-- Flyway migration V26: 存量库补齐节点追踪改版（2026-09-13）的 t_node_event 派生列
-- -----------------------------------------------------------------------------
-- 背景：这批列当年只以注释形式留在 V8 的「存量库迁移」存档里，约定由存量库手工 ALTER。
--       部分环境（如 192.168.123.18 的 emotion_dashboard，基线 V24 + V25）漏跑了那批手工
--       ALTER，节点追踪页 /api/nodes、/api/nodes/current 全部 500：
--       Unknown column 'anchor_id' in 'field list'。
--
-- 写法（重要，别改回存储过程）：
--       上一版用 CREATE PROCEDURE + BEGIN ... END 写，在本机的 Flyway 6.5.7 + MySQL 5.7 上
--       直接 ERROR 1064：6.5.7 的 MySQL 解析器按分号切语句，不认复合语句体，
--       把过程体在第一个内部分号处切碎了（报错里 STATEMENT 片段止于 END IF）。
--       所以全程不用复合语句，改用 V1（t_theme.is_main_line）已经在用的套路：
--       SET @var 判存在 → IF() 选真 DDL 或 'SELECT 0' 空操作 → PREPARE/EXECUTE。
--       每条语句都在顶层分号结束，Flyway 怎么切都不会碎。
-- 幂等：新库从 V1 回放时 t_node_event 的 CREATE 里已含全部 6 列（V1 第 241-246 行），
--       本脚本查 information_schema 后一律走空操作；存量库则按缺失情况逐列补。
--       列名/类型/注释与 V1 建表语句逐字对齐。
-- -----------------------------------------------------------------------------

-- anchor_id 的落位：V1 里它排在 node_stock_max_board 之后。极老的库可能连那一列都没有，
-- 那就退化为追加到表尾（列序只是观感，不影响读写）。
SET @v26_has_anchor_base = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'node_stock_max_board');
SET @v26_after = IF(@v26_has_anchor_base > 0, ' AFTER node_stock_max_board', '');

SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'anchor_id');
SET @v26_ddl = IF(@v26_c = 0,
    CONCAT('ALTER TABLE t_node_event ADD COLUMN anchor_id BIGINT DEFAULT NULL COMMENT ''锚定龙头关联的人工阵眼(t_anchor.id)；NULL=未关联(仍可手填)''', @v26_after),
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

-- 以下 5 列的 AFTER 目标都在上一步之后必然存在（补上了，或本来就已有），无需再判。
SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'theme');
SET @v26_ddl = IF(@v26_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN theme VARCHAR(50) DEFAULT NULL COMMENT ''所属题材/板块(系统B必填)'' AFTER anchor_id',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'd0_score');
SET @v26_ddl = IF(@v26_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN d0_score DECIMAL(5,2) DEFAULT NULL COMMENT ''D0当日情绪总分(five_dim总分或旧温度)，节点产生时市场温度'' AFTER theme',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'd0_cycle');
SET @v26_ddl = IF(@v26_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN d0_cycle VARCHAR(20) DEFAULT NULL COMMENT ''D0当日周期阶段(退潮/启动/发酵/高潮/...)'' AFTER d0_score',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'last_recalc_at');
SET @v26_ddl = IF(@v26_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN last_recalc_at DATETIME DEFAULT NULL COMMENT ''上次复算(T+1自动判定/人工复算)时间，数据新鲜度'' AFTER d0_cycle',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

SET @v26_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'conclusion_reason');
SET @v26_ddl = IF(@v26_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN conclusion_reason VARCHAR(30) DEFAULT NULL COMMENT ''状态来路细分原因'' AFTER last_recalc_at',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;

SET @v26_i = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND INDEX_NAME = 'idx_anchor');
SET @v26_ddl = IF(@v26_i = 0,
    'ALTER TABLE t_node_event ADD INDEX idx_anchor (anchor_id)',
    'SELECT 0');
PREPARE v26_stmt FROM @v26_ddl; EXECUTE v26_stmt; DEALLOCATE PREPARE v26_stmt;
