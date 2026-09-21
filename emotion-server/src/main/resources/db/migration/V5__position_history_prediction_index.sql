-- Flyway migration V5: position history prediction index
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 500-565 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ t_position_history：整表替换前的自动快照（可回滚） ============
CREATE TABLE IF NOT EXISTS t_position_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL COMMENT '被替换的交易日',
    snapshot_date DATE NOT NULL COMMENT '快照发生日',
    position_id BIGINT COMMENT '对应 t_position.id（当天被删行已删，仅作溯源）',
    stock_code VARCHAR(6),
    stock_name VARCHAR(20),
    cost_price DECIMAL(12,3),
    current_price DECIMAL(12,3),
    float_pct DECIMAL(7,2),
    action VARCHAR(60) DEFAULT '',
    planned_action VARCHAR(60) DEFAULT '',
    discipline VARCHAR(8) DEFAULT '',
    industry VARCHAR(20) DEFAULT '',
    board_num INT,
    status VARCHAR(8) DEFAULT '',
    delay_days INT,
    discipline_score INT,
    next_day_plan VARCHAR(255) DEFAULT '',
    plan_open VARCHAR(60) DEFAULT '',
    plan_break VARCHAR(60) DEFAULT '',
    plan_low VARCHAR(60) DEFAULT '',
    plan_fall VARCHAR(60) DEFAULT '',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_date (user_id, trade_date),
    INDEX idx_user_snapshot (user_id, snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓台账整表替换前的自动快照，可回滚';

-- 预判与对答案：`预判:` 写成 kind=PLAN 落在计划的那一天，`对答案:` 写成 kind=ANSWER
-- 落在回写的那一天。两边**不互相拷贝**，命中率是"次日的 ANSWER 行按名称 join 前一日 PLAN 行"
-- 现算出来的。做成冗余拷贝会有个坑：重导前一个交易日要删日重建，那行已经被次日回填过的
-- 兑现结果就跟着没了——而这一天你多半只是在改错别字。
CREATE TABLE IF NOT EXISTS t_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL COMMENT 'PLAN=下单这一行的那天；ANSWER=回写兑现结果的那天',
    kind VARCHAR(6) NOT NULL COMMENT 'PLAN=盘前三路径预判 / ANSWER=次日对答案',
    name VARCHAR(40) NOT NULL COMMENT '路径名。跨日对齐只认名称，所以名字必须每天复用，不能换说法',
    prob TINYINT DEFAULT NULL COMMENT 'PLAN：发生概率 0-100',
    condition_text VARCHAR(300) DEFAULT NULL COMMENT 'PLAN：触发条件原文',
    result VARCHAR(8) DEFAULT NULL COMMENT 'ANSWER：命中/落空/部分/违约',
    result_note VARCHAR(300) DEFAULT NULL COMMENT 'ANSWER：一句话依据',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_kind_name (user_id, trade_date, kind, name),
    INDEX idx_user_kind_date (user_id, kind, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预判留痕与对答案(复盘导入)';

-- 五大指数收盘：`指数:` 一天五行。公开数据、不绑用户，和 t_market_stock 同一族。
-- 腾讯日 K 接口已经在用（TencentClient.dailyBars），以后换成自动取数时这张表就是缓存，
-- 现在先让你手上的历史进得来——指数滞涨 vs 个股普跌这种背离，只有收盘价能看出来。
CREATE TABLE IF NOT EXISTS t_index_close (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL,
    index_code VARCHAR(9) NOT NULL COMMENT 'sh000001/sz399001 之类不带市场前缀的 6-9 位码',
    index_name VARCHAR(20) NOT NULL DEFAULT '',
    close_price DECIMAL(12,2) DEFAULT NULL,
    change_pct DECIMAL(6,2) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_index (trade_date, index_code),
    INDEX idx_index_date (index_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='五大指数收盘(公开数据,不绑用户)';
