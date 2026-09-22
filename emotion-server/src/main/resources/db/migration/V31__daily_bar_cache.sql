-- Flyway migration V31: 日 K 落库缓存（t_daily_bar / t_daily_bar_fetch）
-- -----------------------------------------------------------------------------
-- 背景：日 K 是全站最贵的取数。阵眼跨度、监管异动追踪、D5 阵眼当日涨幅、/market/daily-bars
--       全都直接打 tencent.dailyBars()，一只票一次网络往返，既不落库也没有任何缓存——
--       同一个窗口每打开一次页面就重打一遍。而日 K 是「过去的事实」，昨天那根收盘
--       不会因为今天再看一眼就变，完全没必要反复问上游。
--
-- 为什么要两张表而不是一张：
--       单看 t_daily_bar 的行数判断不出「这段是不是拉过了」。停牌日天然没有行，
--       且停牌是永久状态——用「行数够不够交易日数」去判命中，停牌股会永远判成未缓存，
--       每次照样回源，缓存等于没做。所以另用 t_daily_bar_fetch 记「哪只票的哪个区间
--       已经向上游要过」，命中与否只看这条记录，不看行数。这是本迁移最要紧的一条。
--
-- 前复权不是真静态（落库最大的坑）：
--       存的是 qfq 价，一旦发生分红送股，除权日之前的整段 K 线价格都会被上游重算，
--       库里的旧值就从「对」变成「错」，而且错得悄无声息。应对是给 fetch 记录加保鲜期
--       （market.daily-bar.max-age-days，默认 30 天），过期就重拉一次并刷新记录，
--       顺带把除权吸收掉。所以本表是「有保鲜期的缓存」，不是不可变事实表——
--       任何依赖它的新逻辑都别把它当永久档案。
--
-- 当天/近日不缓存：腾讯个股日线当天滞后若干小时（实测收盘后拉 sz000993 最新只到 T-3），
--       指数当天倒是有。所以「最近 N 个自然日」一律回源，由
--       market.daily-bar.volatile-days 控制（默认 3）。
--
-- 写法：普通 DDL 即可，不加复合语句（Flyway 6.5.7 的 MySQL 解析器按分号切语句，
--       CREATE PROCEDURE / BEGIN...END 会被切成碎片报 ERROR 1064）。
--       用 CREATE TABLE IF NOT EXISTS 保证重放安全。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_daily_bar (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol       VARCHAR(16)  NOT NULL                COMMENT 'gtimg 代码，如 sz000993 / sh000001',
    trade_date   DATE         NOT NULL                COMMENT '交易日',
    open_price   DECIMAL(12,3)          DEFAULT NULL  COMMENT '开盘（前复权）',
    close_price  DECIMAL(12,3)          DEFAULT NULL  COMMENT '收盘（前复权）',
    high_price   DECIMAL(12,3)          DEFAULT NULL  COMMENT '最高（前复权）',
    low_price    DECIMAL(12,3)          DEFAULT NULL  COMMENT '最低（前复权）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后写入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_date (symbol, trade_date),
    KEY idx_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日K缓存：qfq 四价；pct/low_pct 不落库，读时按序列内前一根重算';

-- 一只票一次拉取的区间留痕。命中判定只看它，不看 t_daily_bar 的行数（停牌日天然无行）。
CREATE TABLE IF NOT EXISTS t_daily_bar_fetch (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol      VARCHAR(16) NOT NULL                COMMENT 'gtimg 代码',
    start_date  DATE        NOT NULL                COMMENT '实际向上游请求的起点（含 lead 段）',
    end_date    DATE        NOT NULL                COMMENT '实际向上游请求的终点',
    bar_count   INT         NOT NULL DEFAULT 0      COMMENT '这次拉回并存下的根数',
    fetched_at  DATETIME    NOT NULL                COMMENT '拉取时间；超过保鲜期就重拉（吸收除权）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_symbol_range (symbol, start_date, end_date),
    KEY idx_symbol (symbol),
    KEY idx_fetched_at (fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日K拉取覆盖留痕：判「这段要不要再问上游」的唯一依据';
