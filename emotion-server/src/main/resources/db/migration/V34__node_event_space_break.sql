-- Flyway migration V34: t_node_event 补「空间破局节点」所需的 5 列
-- -----------------------------------------------------------------------------
-- 背景：破局节点（空间节点）不是新页面，而是节点追踪页里的一个新「节点类型」。
--       现有 t_node_event 的 D0 → T+1 → 确认 两日结构，天然对应
--       破局日（D0，观察，0 候选）→ 破局次日（T+1，出手），所以不需要新表，
--       加 5 列就能把这个类型塞进既有页面与既有状态机（待验证/有效/失效）。
--
-- 写法（重要，别改回存储过程）：
--       沿用 V26 的套路——SET @var 查 information_schema 判存在 → IF() 选真 DDL
--       或 'SELECT 0' 空操作 → PREPARE/EXECUTE/DEALLOCATE。
--       禁用 CREATE PROCEDURE / BEGIN...END：本机 Flyway 6.5.7 + MySQL 5.7.29 的
--       MySQL 解析器按分号切语句，复合语句体会被在第一个内部分号处切碎（ERROR 1064）。
--       每条语句都在顶层分号结束，Flyway 怎么切都不会碎。
--
-- 幂等：新库回放 / 存量库重复执行都不会报错；已存在的列走空操作。
--
-- 命名约定（与 PRD §2 一致）：
--       node_type 是「类型轴」，只取 5 个具体值；策略没识别出来的就是 NULL，
--       UI 显示「未识别」，绝不回填「普通 / 常规 / 其他」这类反义定义。
--       「来源轴」是另一回事：人工节点落 t_node_event、策略节点落 t_node_detect，
--       两表互不写入，不要混为一谈。
-- -----------------------------------------------------------------------------

-- node_type 想排在 status 之后，但极老的库可能连 status 都没有，那就退化为追加到表尾。
SET @v34_has_status = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'status');
SET @v34_after = IF(@v34_has_status > 0, ' AFTER status', '');

SET @v34_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'node_type');
SET @v34_ddl = IF(@v34_c = 0,
    CONCAT('ALTER TABLE t_node_event ADD COLUMN node_type VARCHAR(16) DEFAULT NULL COMMENT ''节点类型(类型轴)：START=启动日/DIVERGE=分歧日/SWITCH=切换日(三类统称周期节点),SPACE_BREAK=破局日(观察日,0候选),SPACE_BREAK_NEXT=破局次日(出手日)；NULL=策略未识别，UI显示未识别''', @v34_after),
    'SELECT 0');
PREPARE v34_stmt FROM @v34_ddl; EXECUTE v34_stmt; DEALLOCATE PREPARE v34_stmt;

-- 以下 4 列的 AFTER 目标 node_type 在上一步之后必然存在（补上了，或本来就已有），无需再判。
SET @v34_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_stock_code');
SET @v34_ddl = IF(@v34_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN break_stock_code VARCHAR(16) DEFAULT NULL COMMENT ''破局股代码：破局日里那只断了板的空间板本身，它是锚、不进候选池'' AFTER node_type',
    'SELECT 0');
PREPARE v34_stmt FROM @v34_ddl; EXECUTE v34_stmt; DEALLOCATE PREPARE v34_stmt;

SET @v34_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_board');
SET @v34_ddl = IF(@v34_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN break_board INT DEFAULT NULL COMMENT ''破局股断板前的连板数(空间板高度)，破局次日降级判定要用'' AFTER break_stock_code',
    'SELECT 0');
PREPARE v34_stmt FROM @v34_ddl; EXECUTE v34_stmt; DEALLOCATE PREPARE v34_stmt;

SET @v34_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_form');
SET @v34_ddl = IF(@v34_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN break_form VARCHAR(16) DEFAULT NULL COMMENT ''断板形态：ZB_BREAK=炸板断板(曾封板未封住),MILD_BREAK=温和断板(良性破局,次日修复概率最高),A_KILL=A杀(跌停或跌幅超限，判退潮不判破局)'' AFTER break_board',
    'SELECT 0');
PREPARE v34_stmt FROM @v34_ddl; EXECUTE v34_stmt; DEALLOCATE PREPARE v34_stmt;

SET @v34_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'repair_status');
SET @v34_ddl = IF(@v34_c = 0,
    'ALTER TABLE t_node_event ADD COLUMN repair_status VARCHAR(16) DEFAULT NULL COMMENT ''破局次日的修复判定：PENDING=待判定/SUCCESS=修复成功(可出手)/FAILED=修复失败(降级为观察日,0候选)；周期节点此列恒NULL'' AFTER break_form',
    'SELECT 0');
PREPARE v34_stmt FROM @v34_ddl; EXECUTE v34_stmt; DEALLOCATE PREPARE v34_stmt;
