-- -----------------------------------------------------------------------------
-- Flyway migration V47: 首板筛选留档（t_first_board_screen）
-- -----------------------------------------------------------------------------
-- 要回答的问题（D6 首板生态）：当天涨停的首板里，哪些同时满足
--   ① 站上 250 日均线（年线）——资金在长期趋势之上试新板，不是接着下跌做反弹；
--   ② 它所属的通达信题材，当天<b>连板天梯里一只都没有</b>——天梯 = 当日涨停池 2 板及以上
--      （见 TiantiService，层从 H 铺到 2），所以这条说的是这个题材还没有梯队。
-- 满足两条的票要同时喂两个地方：D6 页展示、D9 策略选股当独立来源的候选。
--
-- 为什么要落一张表，而不是各页现算：
--   年线每只票都要拉一年日 K。当天首板实测几十只，一次判定就是几十次上游往返，
--   而日 K 缓存（t_daily_bar）对「最近 3 个自然日」这一段刻意不留痕
--   （见 V31 与 DailyBarService：那几天数据还没长全，留痕会把中间值钉死），
--   所以每天第一次算都必须回源。把这个成本挂在页面读路径上，D6 就从一个纯库查询
--   变成「打开页面等一串网络」——D6 现在整页只读 t_market_stock，一处也不打上游。
--   因此计算只在 15:30 的选股链路里做（WaveRiderDailyScanTask 在跑策略之前先算这一天），
--   两个页面都只读这张表。
--
-- 为什么把<b>没通过</b>的首板也写进来：
--   只留通过的那几只，界面上就只剩「今天 2 只」，而「62 只首板里 31 只题材在天梯里、
--   28 只没去年线」这半边的信息永久丢失，用户问「某某为什么不在」时无人能答。
--   一天几十行、一个月一千多行，换这层可解释性值得。
--
-- 两个已知边界（都不是 bug，读这张表时要知道）：
--   ① reason=NO_CONCEPT：这只票在 t_stock_concept 里没有任何概念行（V33 那份通达信种子
--      覆盖 5581 只代码，取不到的就是索引里没有）。<b>判不了不等于通过</b>，按未通过记，
--      与全站「取不到的键直接不出现、绝不写 0」同一取向。
--   ② 年线用的是<b>前复权</b>序列的最后 249 根收盘 + 池内当日真实收盘价凑成 250 根。
--      15:30 时腾讯个股日线通常还没发布当天那根（V31 实测只到 T-3，配置里 volatile-days=3
--      就是不信任这段），所以当天的分子只能取自 t_market_stock.close_price。
--      若除权恰好落在当天，qfq 段与新加的那根会差一个复权因子——表现为年线附近的一只票
--      判定翻转，罕见但存在；ma250/bars_used 都留了原值，出事能重算而不是猜。
--   ③ 根数不足 250 时「上市不够一年」与「日 K 这一趟没拉到」共用一个 reason=NO_ANNUAL_LINE：
--      两者对界面都只是「没有年线」，但 bars_used 留了实际参与根数——1 根（只有当日那根）
--      就是取数失败，201 根就是次新，翻表一眼能分开。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_first_board_screen (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    trade_date          DATE          NOT NULL                COMMENT '交易日（当天涨停池里有这行首板的那一日）',
    code                VARCHAR(6)    NOT NULL                COMMENT '6 位股票代码',
    name                VARCHAR(20)   DEFAULT NULL            COMMENT '证券简称（池内原值，便于直接展示）',
    industry            VARCHAR(64)   DEFAULT NULL            COMMENT '引擎分组用的通达信二级行业（与 t_market_stock.industry 同口径，只作展示）',
    concepts            VARCHAR(1024) DEFAULT NULL            COMMENT '该股所属通达信题材简称，逗号分隔（题材空白判据的输入）',
    concept_count       INT           DEFAULT NULL            COMMENT '题材只数；0＝索引里没有这只票的概念行，判不了',
    ladder_concepts     INT           DEFAULT NULL            COMMENT '当日天梯（2 板及以上）覆盖的题材并集只数，解释「为什么大面积淘汰」',
    overlap_count       INT           DEFAULT NULL            COMMENT '该股题材与天梯题材并集的交集只数；0＝天梯里没有这个题材',
    close_price         DECIMAL(12,3) DEFAULT NULL            COMMENT '当日收盘价（池内真实价，非复权）',
    ma250               DECIMAL(12,3) DEFAULT NULL            COMMENT '250 日均线（前复权收盘均值，含当日收盘价）',
    above_pct           DECIMAL(10,2) DEFAULT NULL            COMMENT '(收盘 − 年线) ÷ 年线 × 100，正数即站上年线',
    bars_used           INT           DEFAULT NULL            COMMENT '参与年线的收盘根数（含当日那根）；不足 250＝上市不够一年，判不了',
    passed              TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否两条都过（1 才可进 D9 候选）',
    reason              VARCHAR(24)   NOT NULL DEFAULT ''     COMMENT '判定去向：PASS/BELOW_MA250/THEME_IN_LADDER/NO_ANNUAL_LINE/NO_CONCEPT/NO_CLOSE',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本次判定时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_date_code (trade_date, code),
    KEY idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首板「站上年线 + 题材在天梯里没有」判定留档：15:30 选股链路写，D6/D9 只读';
