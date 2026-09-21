-- Flyway migration V19: review v2 snapshot tables
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1807-1862 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 每日复盘 v2.0（2026-09-13）：T1-T8 一键编排所需的两张新表 ============
-- t_industry_daily_snapshot：行业板块聚合快照，由 t_market_stock 涨停池按 industry 现算（T5），
--   无外部数据源。封单/一字/大面/覆盖层都是当日公开事实，不绑用户。
CREATE TABLE IF NOT EXISTS t_industry_daily_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '交易日',
    industry VARCHAR(20) NOT NULL COMMENT '行业板块(上游 hybk)',
    zt_count INT NOT NULL DEFAULT 0 COMMENT '板块涨停家数',
    max_board INT NULL COMMENT '板块最高连板数',
    seal_sum DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '封单总额(元)',
    yizi_cnt INT NOT NULL DEFAULT 0 COMMENT '一字板家数(首封<=9:30:00且未开板)',
    big_loss_cnt INT NOT NULL DEFAULT 0 COMMENT '板块大面家数(炸板池 big_loss)',
    tier_levels VARCHAR(64) DEFAULT '' COMMENT '覆盖层，逗号分隔的最高连板层如 "4,3,2"',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_industry (trade_date, industry),
    INDEX idx_date_maxboard (trade_date, max_board)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行业板块单日聚合快照(公开数据,T5)';

-- t_theme_daily_snapshot：日内核心题材榜 Top5 快照。题材维度按用户个性化（t_theme/t_theme_stock
--   带 userId，AUTO 绑定），故带 user_id；仅持久化当日展示的前 5 名，题材表读取时自动回填（已有跳过）。
CREATE TABLE IF NOT EXISTS t_theme_daily_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '交易日',
    user_id BIGINT NOT NULL COMMENT '题材绑定所属用户(AUTO口径)',
    `rank` TINYINT NOT NULL COMMENT '题材榜排名1-5',
    theme_name VARCHAR(80) NOT NULL COMMENT '题材名称',
    zt_count INT NOT NULL DEFAULT 0 COMMENT '题材涨停家数',
    strength DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT '题材强度',
    max_board TINYINT NOT NULL DEFAULT 0 COMMENT '题材最高连板',
    continuous_days TINYINT NOT NULL DEFAULT 0 COMMENT '连续活跃天数',
    hardness TINYINT NOT NULL DEFAULT 3 COMMENT '题材硬度(星数)',
    lifecycle VARCHAR(20) NOT NULL DEFAULT '萌芽' COMMENT '生命周期阶段',
    related_industries VARCHAR(500) DEFAULT NULL COMMENT '关联板块，逗号拼接的通达信二级行业',
    leader_code VARCHAR(6) DEFAULT NULL COMMENT '题材龙头代码',
    leader_name VARCHAR(20) DEFAULT NULL COMMENT '题材龙头名称',
    leader_board TINYINT NOT NULL DEFAULT 0 COMMENT '题材龙头连板',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_rank (user_id, trade_date, `rank`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日内核心题材榜Top5快照(按用户,读取回填)';

-- t_review_fetch：每日复盘「一键拉取」最近一次编排的状态留档（T1-T8 逐任务结果 + 汇总）。
--   只记拉取编排的元信息，不存放任何行情/评分数据；行情数据仍在各自的业务表里。
CREATE TABLE IF NOT EXISTS t_review_fetch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '拉取的交易日',
    overall VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT 'RUNNING/DONE/PARTIAL/FAILED/PENDING',
    tasks_json VARCHAR(2000) NULL COMMENT 'T1-T8 逐任务 {task,status,rows,msg} 的 JSON 数组',
    warnings VARCHAR(1000) DEFAULT '' COMMENT '该日编排的汇总提示',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日复盘一键拉取状态留档(T1-T8)';
