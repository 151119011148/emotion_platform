-- Flyway migration V3: ladder anchor surveillance
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 332-377 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 天梯人工总龙头：全市场最高连板自动标"空间板"，"总龙头"是用户某日手动指定的身份（绑用户）。
CREATE TABLE IF NOT EXISTS t_manual_leader (
    user_id BIGINT NOT NULL COMMENT '账号',
    trade_date DATE NOT NULL COMMENT '交易日',
    code VARCHAR(6) NOT NULL COMMENT '6位代码',
    name VARCHAR(20) NOT NULL COMMENT '证券简称(与t_stock反查一致)',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (user_id, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='连板天梯某日人工总龙头(每账号每交易日一行)';

-- 周期阵眼/总龙头：第 8 维的来源。跨度只存起止日，最高连板/回撤等一律从日 K 现算，不让人手填。
CREATE TABLE IF NOT EXISTS t_anchor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    stock_code VARCHAR(6) NOT NULL COMMENT '6位代码，名称由 t_stock 反查校验',
    stock_name VARCHAR(20) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'CYCLE' COMMENT 'CYCLE=周期阵眼 / LEADER=周期总龙',
    cycle_tag VARCHAR(20) DEFAULT NULL COMMENT '同轮归组标签，仅作显示分组',
    start_date DATE NOT NULL COMMENT '跨度起点：起爆日或人工认定的锚点日',
    end_date DATE DEFAULT NULL COMMENT '跨度终点，NULL 表示仍在位',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_code_start (user_id, stock_code, start_date),
    INDEX idx_user_span (user_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周期阵眼与总龙头';

-- 异动监管事件：只存事件，监管期是查的时候推出来的（改窗口长度不必回补数据）
CREATE TABLE IF NOT EXISTS t_surveillance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(6) NOT NULL,
    stock_name VARCHAR(20) NOT NULL,
    ann_date DATE NOT NULL COMMENT '公告日 D0，监管期从这天起算(含当日)',
    kind VARCHAR(8) NOT NULL COMMENT 'ZD=异常波动 / SEVERE=严重异常波动 / EXCH=交易所监管函或警示或纪律处分',
    title VARCHAR(200) NOT NULL,
    column_code VARCHAR(24) DEFAULT NULL COMMENT '上游类目码，异动固定 001002004007',
    art_code VARCHAR(32) NOT NULL COMMENT '上游公告 ID，幂等键',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code_art (stock_code, art_code),
    INDEX idx_code_date (stock_code, ann_date),
    INDEX idx_kind_date (kind, ann_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异动监管事件(公开数据,不绑用户)';
