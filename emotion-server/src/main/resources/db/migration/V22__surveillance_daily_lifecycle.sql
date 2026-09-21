-- Flyway migration V22: surveillance daily lifecycle
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1895-1921 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- =====================================================================
-- 2026-09-13 监管全生命周期轨迹：t_surveillance 只存事件(ann_date=公告日D0)，
-- 监管期(SEVERE/EXCH=10交易日、ZD=5)由 SurveillanceKind 现推后落此表，
-- 支撑 D5 高位生态"监管池→全生命周期热力表"(D+1→出监管)。幂等 CREATE，重复跑无害。
-- =====================================================================
CREATE TABLE IF NOT EXISTS t_surveillance_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(6) NOT NULL COMMENT '6位股票代码',
    stock_name VARCHAR(20) DEFAULT '' COMMENT '股票简称',
    ann_date DATE NOT NULL COMMENT '监管公告日 D0',
    kind VARCHAR(10) NOT NULL COMMENT 'SEVERE/EXCH/ZD',
    trade_date DATE NOT NULL COMMENT '监管期内交易日',
    day_offset INT NOT NULL COMMENT '相对公告日的交易日偏移: D0=0, D+1=1 ... D+N=N',
    consecutive INT DEFAULT NULL COMMENT '当日连板数(进池日有值,非涨跌停日为NULL)',
    change_pct DECIMAL(6,2) DEFAULT NULL COMMENT '当日涨跌幅%(腾讯日K补齐;停牌/缺失为NULL)',
    pool VARCHAR(4) DEFAULT NULL COMMENT 'ZT涨停/DT跌停/ZB炸板,非进池日为NULL',
    big_loss TINYINT DEFAULT NULL COMMENT '当日是否大面/核按钮(1=是)',
    break_count INT DEFAULT NULL COMMENT '当日炸板次数',
    seal_amount DECIMAL(18,2) DEFAULT NULL COMMENT '当日封单额',
    suspended TINYINT NOT NULL DEFAULT 0 COMMENT '当日停牌(该票无日K,与公共交易日错位):1=停牌',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code_ann_date (stock_code, ann_date, trade_date),
    INDEX idx_kind_date (kind, trade_date),
    INDEX idx_code_date (stock_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监管全生命周期每日轨迹(公开数据,监管窗口现算后落库)';
