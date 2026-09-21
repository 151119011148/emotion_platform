-- Flyway migration V4: position ledger
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 378-499 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 每日持仓与纪律台账：复盘 md 的 `持仓:` 逐条一行，绑用户（这是你的账，不是公开数据）。
-- 拆成行而不是塞进 review_note 一列，是为了能问"同一只票连续第几次应做未做"——
-- 这句话是这份笔记最该沉淀的东西，写在自由文本里就只能靠人翻文件数。
CREATE TABLE IF NOT EXISTS t_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(6) NOT NULL COMMENT '6位代码，导入时经 t_stock 校验',
    stock_name VARCHAR(20) NOT NULL COMMENT '以 t_stock 为准，不是采信 md 里写的名字',
    cost_price DECIMAL(12,3) DEFAULT NULL,
    current_price DECIMAL(12,3) DEFAULT NULL,
    float_pct DECIMAL(7,2) DEFAULT NULL COMMENT '浮动盈亏%，手记值原样存，不由成本现价反推',
    action VARCHAR(60) DEFAULT '' COMMENT '今日实际动作',
    planned_action VARCHAR(60) DEFAULT '' COMMENT '按纪律应做的动作',
    discipline VARCHAR(8) DEFAULT '' COMMENT '遵守/违约/待执行',
    industry VARCHAR(20) DEFAULT '' COMMENT '所属板块/D2核心板块(手填或自动)',
    board_num INT DEFAULT NULL COMMENT '买入时板数',
    status VARCHAR(8) DEFAULT '持仓中' COMMENT '持仓中/今日清仓',
    delay_days INT DEFAULT NULL COMMENT '清仓延迟天数(应做未及时做)',
    discipline_score INT DEFAULT NULL COMMENT '纪律评分0-100, 违规按延迟折减',
    next_day_plan VARCHAR(255) DEFAULT '' COMMENT '次日处理决策(竞价裁决)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_code (user_id, trade_date, stock_code),
    INDEX idx_user_code_date (user_id, stock_code, trade_date),
    INDEX idx_user_discipline (user_id, discipline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日持仓与纪律台账(复盘导入)';

-- ============ 存量库迁移(t_position 三段式字段)：可重复执行，缺哪列补哪列 ============
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @pos_tri_adds
  FROM (
        SELECT 1 ord_no, 'industry' col_name,
               'VARCHAR(20) DEFAULT '''' COMMENT ''所属板块/D2核心板块(手填或自动)''' col_ddl
        UNION ALL SELECT 2, 'board_num',
               'INT DEFAULT NULL COMMENT ''买入时板数'''
        UNION ALL SELECT 3, 'status',
               'VARCHAR(8) DEFAULT '''' COMMENT ''持仓中/今日清仓'''
        UNION ALL SELECT 4, 'delay_days',
               'INT DEFAULT NULL COMMENT ''清仓延迟天数(应做未及时做)'''
        UNION ALL SELECT 5, 'discipline_score',
               'INT DEFAULT NULL COMMENT ''纪律评分0-100, 违规按延迟折减'''
        UNION ALL SELECT 6, 'next_day_plan',
               'VARCHAR(255) DEFAULT '''' COMMENT ''次日处理决策(竞价裁决)'''
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_position'
       AND COLUMN_NAME  = need.col_name);
SET @pos_tri_sql = IF(@pos_tri_adds IS NULL,
    'SELECT ''t_position 三段式列已齐，本步跳过'' AS pos_tri_migration',
    CONCAT('ALTER TABLE t_position ', @pos_tri_adds));
PREPARE pos_tri_stmt FROM @pos_tri_sql;
EXECUTE pos_tri_stmt;
DEALLOCATE PREPARE pos_tri_stmt;

-- ============ 存量库迁移(t_position 次日决策外溢 + 分档四栏)：可重复执行 ============
--   分档：plan_open(高开)/plan_break(炸板)/plan_low(平开低开)/plan_fall(跌停)——把单文本
--   next_day_plan 拆成四档动作，外溢到仪表盘/次日页时好渲染成"高开→减半 / 炸板→板砸"。
--   executed：0=待裁决（次日执行前），1=已在次日复盘页标记执行。
--   actual_action：标记执行时回填的真实动作（与 action 区分：action 是 T 日当天的动作）。
CREATE TABLE IF NOT EXISTS t_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(6) NOT NULL,
    stock_name VARCHAR(20) NOT NULL,
    cost_price DECIMAL(12,3) DEFAULT NULL,
    current_price DECIMAL(12,3) DEFAULT NULL,
    float_pct DECIMAL(7,2) DEFAULT NULL,
    action VARCHAR(60) DEFAULT '',
    planned_action VARCHAR(60) DEFAULT '',
    discipline VARCHAR(8) DEFAULT '',
    industry VARCHAR(20) DEFAULT '',
    board_num INT DEFAULT NULL,
    status VARCHAR(8) DEFAULT '持仓中',
    delay_days INT DEFAULT NULL,
    discipline_score INT DEFAULT NULL,
    next_day_plan VARCHAR(255) DEFAULT '',
    plan_open VARCHAR(60) DEFAULT '' COMMENT '次日高开→动作(分档①)',
    plan_break VARCHAR(60) DEFAULT '' COMMENT '次日炸板→动作(分档②)',
    plan_low VARCHAR(60) DEFAULT '' COMMENT '次日平开/低开→动作(分档③)',
    plan_fall VARCHAR(60) DEFAULT '' COMMENT '次日跌停→动作(分档④)',
    executed TINYINT DEFAULT 0 COMMENT '外溢闭环：0=待裁决, 1=已标记执行',
    actual_action VARCHAR(60) DEFAULT '' COMMENT '标记执行时回填的真实动作',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_date_code (user_id, trade_date, stock_code),
    INDEX idx_user_code_date (user_id, stock_code, trade_date),
    INDEX idx_user_discipline (user_id, discipline),
    INDEX idx_user_executed (user_id, executed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日持仓与纪律台账(复盘导入)';

-- 对已有的 t_position 缺列做增量 ALTER（幂等：缺哪列补哪列）
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @pos_out_adds
  FROM (
        SELECT 1 ord_no, 'plan_open' col_name,
               'VARCHAR(60) DEFAULT '''' COMMENT ''次日高开→动作(分档①)''' col_ddl
        UNION ALL SELECT 2, 'plan_break',
               'VARCHAR(60) DEFAULT '''' COMMENT ''次日炸板→动作(分档②)'''
        UNION ALL SELECT 3, 'plan_low',
               'VARCHAR(60) DEFAULT '''' COMMENT ''次日平开/低开→动作(分档③)'''
        UNION ALL SELECT 4, 'plan_fall',
               'VARCHAR(60) DEFAULT '''' COMMENT ''次日跌停→动作(分档④)'''
        UNION ALL SELECT 5, 'executed',
               'TINYINT DEFAULT 0 COMMENT ''外溢闭环：0=待裁决, 1=已标记执行'''
        UNION ALL SELECT 6, 'actual_action',
               'VARCHAR(60) DEFAULT '''' COMMENT ''标记执行时回填的真实动作'''
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_position'
       AND COLUMN_NAME  = need.col_name);
SET @pos_out_sql = IF(@pos_out_adds IS NULL,
    'SELECT ''t_position 外溢/分档列已齐，本步跳过'' AS pos_out_migration',
    CONCAT('ALTER TABLE t_position ', @pos_out_adds));
PREPARE pos_out_stmt FROM @pos_out_sql;
EXECUTE pos_out_stmt;
DEALLOCATE PREPARE pos_out_stmt;
