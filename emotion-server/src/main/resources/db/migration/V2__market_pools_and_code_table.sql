-- Flyway migration V2: market pools and code table
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 255-331 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- 每日盘面个股明细（涨停/跌停/炸板三个池逐只落库，供仪表盘 hover 展示真实名单）
-- 公开行情数据、不绑 user_id。存的就是"参与聚合计数的那批行"，所以名单和卡面上的数字永远自洽。
CREATE TABLE IF NOT EXISTS t_market_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '交易日',
    code VARCHAR(6) NOT NULL COMMENT '6位代码',
    name VARCHAR(20) NOT NULL COMMENT '证券简称',
    pool VARCHAR(4) NOT NULL COMMENT 'ZT涨停/DT跌停/ZB炸板',
    market TINYINT COMMENT '东财标识：1=沪 0=深(含北)',
    industry VARCHAR(20) DEFAULT '' COMMENT '行业板块(上游 hybk)',
    consecutive INT COMMENT '连板数 lbc(涨停池)',
    break_count INT COMMENT '炸板次数 zbc',
    change_pct DECIMAL(6,2) COMMENT '当日涨跌幅%',
    close_price DECIMAL(12,3) COMMENT '收盘价',
    limit_price DECIMAL(12,3) COMMENT '涨停/跌停价',
    pullback_pct DECIMAL(6,2) COMMENT '自涨停回撤%(炸板池)',
    big_loss TINYINT DEFAULT 0 COMMENT '是否大面：回撤>7% 且收盘绿盘',
    seal_amount DECIMAL(18,2) COMMENT '封单额(元)=东财fund,涨停池收盘封单资金',
    amount DECIMAL(18,2) COMMENT '当日成交额(元)=东财池接口amount；D2成交额聚集度取数源(池内口径)',
    float_mv DECIMAL(18,2) DEFAULT NULL COMMENT '流通市值(元)=东财ltsz,仅涨停池；一字断魂刀判据(≤20亿)',
    turnover_rate DECIMAL(8,2) DEFAULT NULL COMMENT '换手率%=东财hs,仅涨停池；一字断魂刀判据(<5%)',
    first_seal_time INT COMMENT '首次封板时间HHMMSS(fbt),判一字/T字用',
    last_seal_time INT COMMENT '最后封板时间HHMMSS(lbt)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_code_pool (trade_date, code, pool),
    INDEX idx_date_pool (trade_date, pool),
    INDEX idx_date_bigloss (trade_date, big_loss)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日盘面个股明细(公开数据,不绑用户)';

-- A股代码名称总表（东财 clist 全量翻页灌入，只保留股票、剔除定向可转债）
CREATE TABLE IF NOT EXISTS t_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(6) NOT NULL COMMENT '6位股票代码',
    name VARCHAR(20) NOT NULL COMMENT '证券简称',
    market TINYINT NOT NULL COMMENT '东财标识：1=沪 0=深(含北)',
    board VARCHAR(10) NOT NULL COMMENT '沪主板/深主板/创业板/科创板/北交所',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code (code),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='A股代码名称总表';

-- 连板档位的"昨日涨停今日溢价"（首板不计入，2..8+ 逐档存，打分只用 LOW/MID/HIGH 三组）
CREATE TABLE IF NOT EXISTS t_premium_tier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '当日：衡量的是"昨日涨停池"今天赚不赚钱',
    board SMALLINT NOT NULL COMMENT '昨日连板数 2..8，8 表示 8 及以上',
    group_key VARCHAR(4) NOT NULL COMMENT 'LOW/MID/HIGH。写入时按当日最高板的一半动态归组(见 PremiumGroup.of)，且这一列只写不读——分组在读的时候按 board 现算，改档不需要回补数据',
    stock_count INT NOT NULL COMMENT '该档家数，取自昨日涨停池',
    matched INT NOT NULL COMMENT '真正取到当日涨跌的家数，小于 stock_count 说明有股没拉到',
    avg_pct DECIMAL(7,2) DEFAULT NULL COMMENT '该档算术平均涨幅(%)',
    max_pct DECIMAL(7,2) DEFAULT NULL,
    min_pct DECIMAL(7,2) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_board (trade_date, board),
    INDEX idx_date_group (trade_date, group_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='连板档位的昨日涨停今日溢价(公开数据,不绑用户)';

-- 昨日涨停股"今日表现"逐只一行（含首板！t_premium_tier 刻意不收首板，1 进 2 大面/成绩单却必须有它）
-- 三池只记录今天还触板的票：昨首板今天低开闷杀、全天没触板的票不在任何池子里，
-- 没有这张表，"1 进 2 大面"就只剩炸板池 big_loss 一个下界（09-11 实测 22 只失败只数出 1 只，实有 5 只）。
CREATE TABLE IF NOT EXISTS t_zt_perf (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL COMMENT '表现日 D：衡量昨日涨停股今天的涨跌',
    code VARCHAR(6) NOT NULL COMMENT '6位代码',
    name VARCHAR(20) NOT NULL COMMENT '证券简称',
    prev_consecutive INT NOT NULL COMMENT 'D-1 连板数：1=昨首板(1进2分母口径)，2..=连板档',
    change_pct DECIMAL(6,2) NOT NULL COMMENT 'D 收盘涨跌幅%(相对昨收=昨涨停价)',
    source VARCHAR(8) NOT NULL DEFAULT 'QUOTE' COMMENT 'QUOTE=腾讯批量快照/KBAR=日K回补/BK=东财昨涨停板块',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_code (trade_date, code),
    INDEX idx_date_prev (trade_date, prev_consecutive)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='昨日涨停股今日表现逐只(含首板,公开数据,不绑用户)';
