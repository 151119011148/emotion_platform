-- Flyway migration V21: stock concept index
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1878-1894 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ D2 题材聚合 v2.3（2026-09-13）：每日主动题材索引 ============
-- t_stock_concept：全市场「股票代码 → 概念板块」静态索引。东财涨停池只给行业(hybk)不给概念，
--   题材热度需先建此索引。概念成分（BKxxxx）变化慢，索引低频刷新（POST /api/review/concepts/build）。
--   建索引为全量重写：DELETE 全表 + 遍历东财全部概念板块拉成分股落库，幂等。
CREATE TABLE IF NOT EXISTS t_stock_concept (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(6) NOT NULL COMMENT '6位股票代码',
    concept_code VARCHAR(12) NOT NULL DEFAULT '' COMMENT '概念板块代码(东财 BKxxxx)',
    concept VARCHAR(50) NOT NULL COMMENT '概念板块名称',
    name VARCHAR(20) DEFAULT '' COMMENT '股票简称(索引构建时带出,便于排查)',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code_concept (code, concept_code),
    INDEX idx_code (code),
    INDEX idx_concept (concept)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票-概念板块全局索引(D2题材聚合)';
