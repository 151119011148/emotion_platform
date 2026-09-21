-- Flyway migration V6: objective market daily
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 566-590 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 全局客观行情日数据（2026-09-13 客观/主观隔离）============
-- 一天一行、全账号共享：这九个数是"每日公开事实"（由 /api/market/snapshot 自动拉取或表单/md 录入），
-- 与谁复盘无关。隔离前寄生在 t_daily_record（绑用户），同一份盘面按账号复制、没复盘的日子就缺客观读数。
-- t_daily_record 从此只装主观内容：人工读数、打分、文本、仓位、阵眼。
-- 读接口在服务层把本表合并进 DailyRecord 响应（null 才填、不覆盖），前端 JSON 契约逐字不变。
CREATE TABLE IF NOT EXISTS t_market_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL,

    max_consecutive_limit INT DEFAULT NULL COMMENT '连板高度（最高板）',
    limit_up_count INT DEFAULT NULL COMMENT '涨停家数',
    limit_down_count INT DEFAULT NULL COMMENT '跌停家数',
    up_count INT DEFAULT NULL COMMENT '全市场上涨家数（东财实时口径，只展示与红盘率，不进九维分母）',
    down_count INT DEFAULT NULL COMMENT '全市场下跌家数（同上）',
    yesterday_limit_premium DECIMAL(5,2) DEFAULT NULL COMMENT '昨日涨停今日溢价(%)，含首板',
    broken_board_rate DECIMAL(5,2) DEFAULT NULL COMMENT '炸板率(%)，次数口径：打开次数 ÷ 触板总次数',
    big_loss_count INT DEFAULT NULL COMMENT '大面数',
    total_volume DECIMAL(10,2) DEFAULT NULL COMMENT '两市成交额(亿)',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局客观行情日数据(公开,不绑用户,全账号共享)';
