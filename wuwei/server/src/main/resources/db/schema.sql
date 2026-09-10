-- =====================================================================
-- 五维短线情绪评分与节点导航系统 — MySQL 建表（对齐 PRD §3，PostgreSQL 方言转 MySQL）
-- 全部 IF NOT EXISTS，随应用启动幂等执行（spring.sql.init）
-- =====================================================================

-- 用户表（认证用，参考 emotion-server）
CREATE TABLE IF NOT EXISTS t_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(32)  NOT NULL UNIQUE,
    password   VARCHAR(100) NOT NULL,
    nickname   VARCHAR(32),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.1 股票主池
CREATE TABLE IF NOT EXISTS t_stock_base (
    ts_code      VARCHAR(12) PRIMARY KEY,
    symbol       VARCHAR(10)  NOT NULL,
    name         VARCHAR(32)  NOT NULL,
    exchange     VARCHAR(8)   NOT NULL,
    board        VARCHAR(16),
    industry     VARCHAR(32),
    is_st        TINYINT(1) DEFAULT 0,
    is_new_stock TINYINT(1) DEFAULT 0,
    list_date    DATE,
    free_float   DECIMAL(16,2),
    total_float  DECIMAL(16,2),
    limit_pct    DECIMAL(5,2) DEFAULT 10.00,
    delisted     TINYINT(1) DEFAULT 0,
    updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_stock_base_board (board)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.2 板块题材池
CREATE TABLE IF NOT EXISTS t_concept_base (
    concept_id        VARCHAR(16) PRIMARY KEY,
    name              VARCHAR(64) NOT NULL,
    source            VARCHAR(16),
    level1            VARCHAR(32),
    level2            VARCHAR(32),
    is_main_line      TINYINT(1) DEFAULT 0,
    stage             VARCHAR(16) DEFAULT '萌芽',
    catalyst_hardness INT DEFAULT 3,
    continuous_days   INT DEFAULT 0,
    active_since      DATE,
    note              TEXT,
    UNIQUE KEY uk_concept_name_source (name, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.3 个股↔题材关系
CREATE TABLE IF NOT EXISTS t_stock_concept_rel (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date  DATE NOT NULL,
    ts_code     VARCHAR(12) NOT NULL,
    concept_id  VARCHAR(16) NOT NULL,
    role        VARCHAR(8)  DEFAULT 'MINOR',
    dragon_role VARCHAR(16),
    UNIQUE KEY uk_scr (trade_date, ts_code, concept_id),
    KEY idx_scr_concept (concept_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.4 每日触板原始池
CREATE TABLE IF NOT EXISTS t_limit_up_daily (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date     DATE NOT NULL,
    ts_code        VARCHAR(12) NOT NULL,
    name           VARCHAR(32),
    status         VARCHAR(12) NOT NULL,
    n_zones        INT DEFAULT 1,
    prev_n_zones   INT DEFAULT 0,
    is_first_board TINYINT(1) DEFAULT 0,
    concept_main   VARCHAR(16),
    first_lu_time  TIME,
    last_lu_time   TIME,
    open_times     INT DEFAULT 0,
    is_back        TINYINT(1) DEFAULT 0,
    open_chg       DECIMAL(7,2),
    high_chg       DECIMAL(7,2),
    close_chg      DECIMAL(7,2),
    next_open_chg  DECIMAL(7,2),
    next_close_chg DECIMAL(7,2),
    amount         DECIMAL(16,2),
    turnover_rate  DECIMAL(7,2),
    vol_yest_ratio DECIMAL(7,2),
    fd_amount      DECIMAL(16,2),
    fd_float_ratio DECIMAL(7,4),
    max_drawdown   DECIMAL(7,2),
    is_big_noodle  TINYINT(1) DEFAULT 0,
    is_nuke        TINYINT(1) DEFAULT 0,
    broken_flag    TINYINT(1) DEFAULT 0,
    monitor_status VARCHAR(16) DEFAULT 'NONE',
    UNIQUE KEY uk_lud (trade_date, ts_code, status),
    KEY idx_lud_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.5 连板派生表
CREATE TABLE IF NOT EXISTS t_lianban_daily (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date       DATE NOT NULL,
    ts_code          VARCHAR(12) NOT NULL,
    name             VARCHAR(32),
    n_zones          INT,
    tier             VARCHAR(10),
    concept_main     VARCHAR(16),
    prev_n_zones     INT,
    jr_base_count    INT,
    is_promote       TINYINT(1),
    first_lu_time    TIME,
    open_times       INT,
    is_back          TINYINT(1),
    fd_amount        DECIMAL(16,2),
    turnover_rate    DECIMAL(7,2),
    vol_yest_ratio   DECIMAL(7,2),
    open_chg         DECIMAL(7,2),
    close_chg        DECIMAL(7,2),
    max_drawdown     DECIMAL(7,2),
    is_big_noodle    TINYINT(1),
    is_nuke          TINYINT(1),
    monitor_status   VARCHAR(16),
    is_space_leader  TINYINT(1) DEFAULT 0,
    is_sector_leader TINYINT(1) DEFAULT 0,
    leader_action    VARCHAR(16),
    UNIQUE KEY uk_lbd (trade_date, ts_code),
    KEY idx_lbd_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.6 监管异动池
CREATE TABLE IF NOT EXISTS t_monitor_pool (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts_code         VARCHAR(12) NOT NULL,
    name            VARCHAR(32),
    status          VARCHAR(16),
    enter_date      DATE,
    exit_date       DATE,
    related_concept VARCHAR(50),
    is_high_position TINYINT(1),
    UNIQUE KEY uk_monitor (ts_code, enter_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.7 每日评分
CREATE TABLE IF NOT EXISTS t_sentiment_score (
    trade_date    DATE PRIMARY KEY,
    score_market  DECIMAL(5,2),
    score_concept DECIMAL(5,2),
    score_lianban DECIMAL(5,2),
    score_shouban DECIMAL(5,2),
    score_zhenyan DECIMAL(5,2),
    total_score   DECIMAL(5,2),
    force_exit    TINYINT(1) DEFAULT 0,
    force_reason  TEXT,
    details_json  TEXT,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3.8 每日节点
CREATE TABLE IF NOT EXISTS t_node_daily (
    trade_date     DATE PRIMARY KEY,
    node           VARCHAR(16),
    prev_node      VARCHAR(16),
    transition     VARCHAR(32),
    trigger_reason TEXT,
    forecast       TEXT,
    watch_points   TEXT,
    main_concept   VARCHAR(16),
    main_stage     VARCHAR(16),
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
