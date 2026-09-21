-- Flyway migration V23: tdx industry concept dict
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1922-1952 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 通达信行业分类字典（海王星/TDX，来源 incon.dat #TDXNHY；数据由 scripts/import_tdx_tables.py 导入）
CREATE TABLE IF NOT EXISTS t_industry (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    industry_code VARCHAR(10) NOT NULL COMMENT '通达信行业码(T0101)',
    industry_name VARCHAR(50) NOT NULL,
    parent_code VARCHAR(10),
    level TINYINT COMMENT '1一级/2二级/3三级',
    UNIQUE KEY uk_industry_code (industry_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信行业分类字典(海王星/TDX)';

-- 个股 → 通达信二级行业 映射（来源 tdxhy.cfg，行业码截 T+4）
CREATE TABLE IF NOT EXISTS t_industry_stock (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(6) NOT NULL,
    name VARCHAR(20),
    market VARCHAR(4) COMMENT '深/沪/北',
    industry_code VARCHAR(10) NOT NULL COMMENT '二级行业码',
    industry_name VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股→通达信二级行业 映射';

-- 通达信概念板块字典（来源 infoharbor_block.dat GN_ + tdxbk.cfg 全称/类别）
CREATE TABLE IF NOT EXISTS t_concept_board (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    board_code VARCHAR(30) NOT NULL COMMENT '板块简称(锂电池/通达信88)',
    board_name VARCHAR(60) NOT NULL COMMENT '板块全称',
    index_code VARCHAR(10),
    category VARCHAR(4) DEFAULT '1' COMMENT '1概念/2风格/3指数',
    UNIQUE KEY uk_board_code (board_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通达信概念板块字典(海王星/TDX)';
