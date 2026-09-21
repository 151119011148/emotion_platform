-- Flyway migration V20: mainline dual track mark
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1863-1877 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 主线双轨 v0.2（2026-09-13）：人工主线标记 ============
-- t_mainline_mark：雷达区「升级到主线区」的人工标记。命中即算当日有主线（hasMainline=true），
--   D2 评分对象优先取人工标记行业（高于 ≥3天自动主线）。
CREATE TABLE IF NOT EXISTS t_mainline_mark (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '归属用户',
    trade_date DATE NOT NULL COMMENT '交易日',
    industry VARCHAR(20) NOT NULL COMMENT '人工标记的主线行业',
    manual TINYINT NOT NULL DEFAULT 1 COMMENT '固定1(人工标记)，保留位',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_industry (user_id, trade_date, industry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='主线双轨雷达区人工主线标记';
