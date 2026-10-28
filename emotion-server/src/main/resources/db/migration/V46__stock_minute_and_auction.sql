-- -----------------------------------------------------------------------------
-- 个股分钟序列 + 集合竞价定格快照
--
-- 为什么要有这两张表：策略选股那条链子从头到尾只看日级数据——t_market_stock 是收盘后
-- 定格的三池快照（一天一票一行），t_daily_bar 只有日K四价。盘中「几点第一次撬板、
-- 开板持续了几分钟、竞价挂了多少、分时量峰怎么走的」一次都没存过，判据里唯一的盘中
-- 痕迹就是 first_seal_time / last_seal_time / break_count 三个标量。想复盘只能凭肉眼
-- 当时看见的那一眼，而肉眼看过就没了。
--
-- 源的两条硬期限（都是 2026-10-28 在生产机上实测的，不是推理）：
--   ① 分钟序列只有当天。proxy.finance.qq.com 的 minute/query 返回 date=20261028、
--      267 根、首根 0930、末根 1530，每根是「HHMM 价格 累计量 累计额」。过了当天就取不到，
--      所以这张表只能从今天起往后攒，历史一天也补不回来。
--   ② 竞价定格必须在 09:26 现场抓。连续竞价一开（09:30），五档上的竞买/竞卖未匹配量
--      就被冲掉，之后再请求同一个盘口接口，拿到的只是当天收盘的累计值。
--
-- 为什么竞价不走东财逐笔（那样本可以不用早上跑一趟）：逐笔明细的第一笔就是 09:25 的
-- 集合竞价成交，看起来更省事。实测 push2delay 的 details/get 同一个 URL 第一次给 7686
-- 字节、随后连发四次全是 `curl: (52) Empty reply from server`，而 pos=0（取当天开头几笔）
-- 一次都没成功过。不可靠的源不能当主口径，所以竞价只认已验证的批量盘口 qt.gtimg.cn。
-- -----------------------------------------------------------------------------

-- 分钟序列：一票一分钟一行，累计量/累计额照源存原值。
-- 逐分钟量不在这里算——写死成差分之一旦某天缺根就得回头补，读时按上一根差分更安全
-- （和 t_daily_bar「pct 不落库，读时按序列内前一根重算」同一选择）。
CREATE TABLE IF NOT EXISTS t_stock_minute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(6) NOT NULL COMMENT '6位代码',
    trade_date DATE NOT NULL COMMENT '交易日（= 源里的 date 字段，非当天就整票不写）',
    minute SMALLINT NOT NULL COMMENT 'HHMM，源给什么存什么（实测 0930…1530）',
    price DECIMAL(12,3) DEFAULT NULL COMMENT '该分钟成交价（源原值，不复权）',
    cum_volume_hand BIGINT DEFAULT NULL COMMENT '截至该分钟的累计成交量(手)',
    cum_amount_yuan DECIMAL(20,2) DEFAULT NULL COMMENT '截至该分钟的累计成交额(元)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code_date_minute (code, trade_date, minute),
    INDEX idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个股分钟序列(公开数据,不绑用户)：源只给当天,漏一晚就永久缺';

-- 竞价定格：一票一天一行，抓的就是 09:26 那一刻的盘口。
-- 字段位是 2026-10-28 拿百洋股份(002696)那行 88 段实测对过的：
--   3 现价 · 4 昨收 · 5 今开 · 6 成交量(手) · 9/10 买一价/量 · 19/20 卖一价/量 ·
--   30 行情时间 · 37 成交额(万) · 47 涨停价。
-- 09:26 那一刻这些字段的含义还没实测过（要等明早第一趟），所以整行原文留在
-- snapshot_raw 里：字段解释错了我能拿原文重算，而不是再等一天窗口才能核对。
-- 开盘涨幅不落库——auction_price 与 prev_close 现除就行，同一个理由：别把算过的存下来。
CREATE TABLE IF NOT EXISTS t_stock_auction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(6) NOT NULL COMMENT '6位代码',
    name VARCHAR(20) DEFAULT '' COMMENT '证券简称（源给的第 1 段，抓取当时留档）',
    trade_date DATE NOT NULL COMMENT '交易日',
    auction_price DECIMAL(12,3) DEFAULT NULL COMMENT '竞价成交价 = 09:26 盘口现价（也就是当天开盘价）',
    prev_close DECIMAL(12,3) DEFAULT NULL COMMENT '昨收',
    volume_hand BIGINT DEFAULT NULL COMMENT '竞价成交量(手)：09:30 前只有竞价，故累计量就是竞价量',
    amount_wan DECIMAL(18,2) DEFAULT NULL COMMENT '竞价成交额(万元)，源的第 37 段原值',
    bid1_price DECIMAL(12,3) DEFAULT NULL COMMENT '竞买一档价（未匹配委买的价格）',
    bid1_hand BIGINT DEFAULT NULL COMMENT '竞买一档量(手)=未匹配买量',
    ask1_price DECIMAL(12,3) DEFAULT NULL COMMENT '竞卖一档价',
    ask1_hand BIGINT DEFAULT NULL COMMENT '竞卖一档量(手)=未匹配卖量',
    limit_up_price DECIMAL(12,3) DEFAULT NULL COMMENT '涨停价：拿它比 auction_price 就能判「竞价即涨停」',
    captured_at DATETIME DEFAULT NULL COMMENT '实际发出请求的时刻（排班 09:26，网络慢时会是 09:26:0x）',
    snapshot_raw VARCHAR(1000) DEFAULT NULL COMMENT '该行盘口原文，字段解释变了靠它重算',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_code_date (code, trade_date),
    INDEX idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='集合竞价定格快照(公开数据,不绑用户)：只能 09:26 现场抓';

-- 排班同样落库里，不在代码里（见 SchedulerPlatform 的说明）。
--
-- 09:26：09:25 撮合出开盘价后，09:26–09:29 盘口仍是竞价结果，09:30 连续竞价一开就没了。
-- 取 09:26 而不是 09:25:10，是给上游留一点把竞价写进快照的余量；宁可离 09:30 近些，
-- 也别在 09:25 撮合完成之前抓到一批空值。
-- 19:12：名单要靠当天三池，而三池是 19:00 dailyMarketPullTask 写的，所以只能排在它后面；
-- 再往后就撞上 19:30 reviewObjectiveTask（同一批上游），12 分钟是这两个约束之间唯一的窗口。
--
-- misfire 一律 DO_NOTHING：09:26 错过就是错过（补跑只会拿到连续竞价的量，是假数据）；
-- 19:12 错过最多丢当天分钟序列，宁缺毋假，绝不补半截。
-- INSERT IGNORE：重放迁移不覆盖运维手工改过的 cron。
INSERT IGNORE INTO t_scheduler_job
    (job_name, job_group, bean_name, cron_expr, misfire_policy, enabled, description)
VALUES
    ('auction_snapshot_task', 'DEFAULT', 'auctionSnapshotTask', '0 26 9 ? * MON-FRI', 'DO_NOTHING', 1,
     '每交易日09:26抓集合竞价定格快照(一次批量盘口)：昨日涨停/炸板池+阵眼+持仓，写 t_stock_auction。过了09:30五档就是连续竞价的量，补跑无意义'),
    ('stock_minute_pull_task', 'DEFAULT', 'stockMinutePullTask', '0 12 19 ? * MON-FRI', 'DO_NOTHING', 1,
     '每交易日19:12抓当日涨停/炸板池个股的分钟序列，写 t_stock_minute。腾讯分钟源只给当天，漏一晚就永久缺这一天');
