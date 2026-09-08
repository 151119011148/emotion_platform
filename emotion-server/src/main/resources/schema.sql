-- 建库
CREATE DATABASE IF NOT EXISTS emotion_dashboard DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE emotion_dashboard;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(200) NOT NULL,
    nickname VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每日记录表（核心）
CREATE TABLE IF NOT EXISTS t_daily_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,

    -- 九维原始指标
    -- 这些列一律 DEFAULT NULL 而不是 0：MyBatis-Plus 的 insert 默认跳过 null 字段，
    -- 默认值 0 会把"没录"变成"录了个 0"，而 0 家跌停/0 分主线都是能改变阶段判断的读数。
    max_consecutive_limit INT DEFAULT NULL COMMENT '连板高度',
    limit_up_count INT DEFAULT NULL COMMENT '涨停家数',
    limit_down_count INT DEFAULT NULL COMMENT '跌停家数',
    -- 这两个数**不进分母**：03 篇把"涨跌家数比"列在「辅助指标（选看）」，
    -- 而打分表第 3 维写的是"涨停 vs 跌停家数"。要改判得先动 03 篇，不在导入器里自作主张。
    up_count INT DEFAULT NULL COMMENT '上涨家数（复盘表单手填或 md 导入，只展示与对照，不参与打分）',
    down_count INT DEFAULT NULL COMMENT '下跌家数（同上）',
    yesterday_limit_premium DECIMAL(5,2) DEFAULT NULL COMMENT '昨日涨停今日溢价(%)，含首板，只做展示不参与打分',
    premium_weighted DECIMAL(7,2) DEFAULT NULL COMMENT '非首板三档加权合成溢价(%)，score_premium 的来源，仅追溯用',
    broken_board_rate DECIMAL(5,2) DEFAULT NULL COMMENT '炸板率(%)，次数口径：打开次数 ÷ 触板总次数',
    sealed_home_rate DECIMAL(5,2) DEFAULT NULL COMMENT '家数封板率(%)=涨停家数÷(涨停+炸板)。与炸板率不是互补：一个数家、一个数次数',
    reseal_rate DECIMAL(5,2) DEFAULT NULL COMMENT '回封率(%)=封住前曾打开的涨停家数÷(那些+炸板家数)',
    big_loss_count INT DEFAULT NULL COMMENT '大面数',
    total_volume DECIMAL(10,2) DEFAULT NULL COMMENT '两市成交额(亿)',
    surv_count INT DEFAULT NULL COMMENT '第 9 维进分家数（只算严重异常波动/交易所监管，例行 ZD 只展示）。0=拉过且确实没有，NULL=从没拉取，两者含义不同',
    surv_premium DECIMAL(7,2) DEFAULT NULL COMMENT '进分监管股当日算术平均涨幅(%)，第 9 维的原始值',

    -- 九维打分。下限 -1 = "确认负反馈/崩了"，0 = 原打分表的最低档"差"，NULL = 该维未评（整维剔出分母）。
    -- 量能、主线明确度、阵眼三维仍以 0 为底：它们的 0 档本身就已经是判到最重（背离 / 无主线 / 收盘跌停）。
    score_height TINYINT DEFAULT NULL COMMENT '连板高度得分(-1~3)，-1=真断龙(从 4 板以上掉回首板)',
    score_premium TINYINT DEFAULT NULL COMMENT '溢价得分(-1~3)，由低/中/高三档按当日最高板的一半动态归组后加权合成',
    score_breadth TINYINT DEFAULT NULL COMMENT '涨跌停比得分(-1~3)，-1=跌停成片(≥2倍涨停且≥20家)',
    score_broken TINYINT DEFAULT NULL COMMENT '炸板维得分(-1~3)=炸板率/家数封板率/回封率三子项平均',
    score_loss TINYINT DEFAULT NULL COMMENT '大面数得分(-1~3)，-1=>25 家',
    score_volume TINYINT DEFAULT NULL COMMENT '量能得分(0-3)，0 已是底(背离)',
    score_theme TINYINT DEFAULT NULL COMMENT '主线明确度得分(0-3)，人工三档，不开负档',
    anchor_score TINYINT DEFAULT NULL COMMENT '第 8 维：周期阵眼当日反馈(0-3)，多只取最差，0 已是底(收盘跌停/触板/断板)',
    anchor_note VARCHAR(300) DEFAULT NULL COMMENT '阵眼打分依据中文串，不让人猜 0 分是哪来的',
    surv_note VARCHAR(500) DEFAULT NULL COMMENT '监管股打分依据中文串，含各自在列第几日',
    broken_note VARCHAR(300) DEFAULT NULL COMMENT '第 4 维三个子项的算式与出分：次数口径的炸板率和家数口径的封板率必须分开写',

    -- 子项人工覆盖（2026-09-08）。判据是"一天一个数、他会想改、改了不牵连别人账号"才进这里：
    -- t_premium_tier 与 t_market_stock 都没有 user_id，是"每日公开事实"，人工值塞进去就破坏
    -- "谁复盘都该拿到同一个分"那条不变式，所以覆盖一律落在自己这一行，读的时候现叠在自动值上。
    -- 一律 DEFAULT NULL：0 是一个真实读数（0% 封板率是崩盘、0 家进分是"拉过了确实没有"），
    -- NULL 才表达"这格我没改，用自动算出来的那个"。清空即回退。
    manual_sealed_home_rate DECIMAL(5,2) DEFAULT NULL COMMENT '第4维手改：家数封板率(%)，NULL=用盘面明细算的',
    manual_reseal_rate DECIMAL(5,2) DEFAULT NULL COMMENT '第4维手改：回封率(%)',
    manual_premium_low_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：低位组今日均涨幅(%)，不改公开档位表',
    manual_premium_mid_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：中位组今日均涨幅(%)',
    manual_premium_high_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：高位组今日均涨幅(%)',
    manual_anchor_score TINYINT DEFAULT NULL COMMENT '第8维手改：阵眼当日反馈分(0~3)，直接进分子',
    manual_surv_count INT DEFAULT NULL COMMENT '第9维手改：进分家数',
    manual_surv_premium DECIMAL(7,2) DEFAULT NULL COMMENT '第9维手改：进分组合当日均涨幅(%)',

    -- 汇总
    total_score INT DEFAULT NULL COMMENT '温度计总分(已评维度的原始分之和，可为负)',
    temperature DECIMAL(5,1) DEFAULT NULL COMMENT '换算温度(-33.3~100)=总分/(3*已评维数)*100',
    prev_temperature DECIMAL(5,1) DEFAULT NULL COMMENT '昨日温度',
    scored_dims TINYINT DEFAULT 0 COMMENT '本次参与打分的维数(0-9)，<5 不出阶段',

    -- 阶段定位
    stage VARCHAR(20) DEFAULT '' COMMENT '阶段(七个主阶段之一，集合不变)',
    stage_seq TINYINT DEFAULT NULL COMMENT '该主阶段的第几个回合：段号按回合递增，不按天',
    stage_phase VARCHAR(6) DEFAULT NULL COMMENT '空=阶段延续；反弹=退潮/分歧次日的回升且未回发酵线(55°)，显示为「反弹N阶段」',
    stage_direction VARCHAR(10) DEFAULT '' COMMENT '方向：上升/下降/横盘',
    stage_overridden TINYINT DEFAULT 0 COMMENT '是否手动覆盖阶段',

    -- 主线龙头
    main_theme VARCHAR(100) DEFAULT '' COMMENT '当前主线题材',
    leading_stock VARCHAR(50) DEFAULT '' COMMENT '总龙头',
    leading_stock_status VARCHAR(20) DEFAULT '' COMMENT '龙头状态',
    mid_cap_stock VARCHAR(50) DEFAULT '' COMMENT '中军',

    -- 我自己的仓位：市场读数是公开的，这一格只有你知道。平台不猜，也不参与打分。
    my_position_pct DECIMAL(5,2) DEFAULT NULL COMMENT '我的实际仓位%（复盘表单手填或 md 导入），冰点期满仓这种背离靠它才看得见',

    -- 复盘文本
    rotation_note TEXT COMMENT '轮动观察',
    review_note TEXT COMMENT '对答案',
    tomorrow_plan TEXT COMMENT '明日计划',
    -- 整篇原文，含 ```meta 围栏块本身。md 是唯一真相：库里这份能原样导回编辑器改完再导入，
    -- 页面展示时才把围栏块切掉。存半截（只存正文）会让"导入的东西和贴进去的东西不一致"。
    review_md MEDIUMTEXT COMMENT '整篇复盘原文（含 meta 块），复盘 md 导入器写入',
    -- 各节判断文字，JSON：{"index":"...","theme":"..."}。元宝/豆包给你的答案和你自己的定性都放这里，
    -- 平台原样存、不解析，只喂「导出复盘文档」那条只读链路。不参与打分，也不进 ```meta 导入契约。
    doc_notes TEXT COMMENT '复盘文档各节判断文字(JSON：小节键→正文)',
    -- 手记的盘面读数与原话，如「手记 46涨停/17跌停，系统取到 44/16」。以前只活在 review_md 里，
    -- 现读现展示；复盘导入页撤了以后那一栏没有别的写入口，所以给它一列。
    -- 读的时候列优先、原文兜底（那两天的老数据只在 md 里）。不参与打分。
    compare_note VARCHAR(300) DEFAULT NULL COMMENT '手记与系统读数的对照，人工填',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date (user_id, trade_date),
    INDEX idx_trade_date (trade_date),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 周期表
CREATE TABLE IF NOT EXISTS t_cycle (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    start_date DATE NOT NULL COMMENT '周期起始日(冰点日)',
    end_date DATE DEFAULT NULL COMMENT '周期结束日',
    max_temperature DECIMAL(5,1) DEFAULT 0 COMMENT '本轮最高温度',
    max_height INT DEFAULT 0 COMMENT '本轮最高连板',
    leading_stock VARCHAR(50) DEFAULT '' COMMENT '本轮总龙头',
    main_theme VARCHAR(100) DEFAULT '' COMMENT '本轮主线',
    status VARCHAR(10) DEFAULT 'ONGOING' COMMENT 'ONGOING/COMPLETED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 主线题材表
CREATE TABLE IF NOT EXISTS t_theme (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    cycle_id BIGINT DEFAULT NULL COMMENT '所属周期',
    name VARCHAR(100) NOT NULL COMMENT '题材名称',
    start_date DATE COMMENT '题材启动日',
    status VARCHAR(10) DEFAULT '萌芽' COMMENT '萌芽/确认/扩散/亢奋/退潮',
    strength INT DEFAULT 0 COMMENT '强度(0-100)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_cycle_id (cycle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 龙头股表
CREATE TABLE IF NOT EXISTS t_leading_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    theme_id BIGINT DEFAULT NULL COMMENT '所属题材',
    name VARCHAR(50) NOT NULL COMMENT '股票名称',
    role VARCHAR(20) DEFAULT '' COMMENT '总龙头/中军/跟风/卡位/反包龙',
    max_consecutive INT DEFAULT 0 COMMENT '最高连板数',
    start_date DATE COMMENT '启动日',
    status VARCHAR(20) DEFAULT '' COMMENT '当前状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_theme_id (theme_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 节点事件表
CREATE TABLE IF NOT EXISTS t_node_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    cycle_id BIGINT DEFAULT NULL COMMENT '所属周期',
    system_type VARCHAR(10) DEFAULT '' COMMENT 'A(市场总节点)/B(板块节点)',
    anchor_stock VARCHAR(50) DEFAULT '' COMMENT '锚定龙头',
    anchor_max_board INT DEFAULT 0 COMMENT '锚定龙头最高板数',
    d0_date DATE COMMENT 'D0日期',
    d0_candidates TEXT COMMENT 'D0候选票(JSON)',
    t1_date DATE COMMENT 'T+1验证日',
    t1_anchor_repack TINYINT DEFAULT 0 COMMENT 'T+1老龙是否反包',
    t1_promotion_count INT DEFAULT 0 COMMENT 'T+1晋级数量',
    t1_promotion_rate DECIMAL(5,2) DEFAULT 0 COMMENT 'T+1晋级率',
    node_valid TINYINT DEFAULT 0 COMMENT '节点是否有效',
    node_stock VARCHAR(50) DEFAULT '' COMMENT '确认的节点票',
    node_stock_max_board INT DEFAULT 0 COMMENT '节点票最高板数',
    filter_passed TINYINT DEFAULT 0 COMMENT '前置过滤器是否通过',
    filter_detail TEXT COMMENT '过滤器明细(JSON)',
    status VARCHAR(20) DEFAULT '待验证' COMMENT '待验证/有效/失效',
    status_note VARCHAR(300) COMMENT '状态来路：平台那条建议是怎么算出来的，一句话',
    note TEXT COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

-- 每日持仓与纪律台账：复盘 md 的 `持仓:` 逐条一行，绑用户（这是你的账，不是公开数据）。
-- 拆成行而不是塞进 review_note 一列，是为了能问"同一只票连续第几次应做未做"——
-- 这句话是这份笔记最该沉淀的东西，写在自由文本里就只能靠人翻文件数。
CREATE TABLE IF NOT EXISTS t_position (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL,
    stock_code VARCHAR(6) NOT NULL COMMENT '6位代码，导入时经 t_stock 校验',
    stock_name VARCHAR(20) NOT NULL COMMENT '以 t_stock 为准，不是采信 md 里写的名字',
    cost_price DECIMAL(12,3) DEFAULT NULL,
    current_price DECIMAL(12,3) DEFAULT NULL,
    float_pct DECIMAL(7,2) DEFAULT NULL COMMENT '浮动盈亏%，手记值原样存，不由成本现价反推',
    action VARCHAR(60) DEFAULT '' COMMENT '今日实际动作',
    planned_action VARCHAR(60) DEFAULT '' COMMENT '按纪律应做的动作',
    discipline VARCHAR(8) DEFAULT '' COMMENT '遵守/违约/待执行',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_code (user_id, trade_date, stock_code),
    INDEX idx_user_code_date (user_id, stock_code, trade_date),
    INDEX idx_user_discipline (user_id, discipline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日持仓与纪律台账(复盘导入)';

-- 预判与对答案：`预判:` 写成 kind=PLAN 落在计划的那一天，`对答案:` 写成 kind=ANSWER
-- 落在回写的那一天。两边**不互相拷贝**，命中率是"次日的 ANSWER 行按名称 join 前一日 PLAN 行"
-- 现算出来的。做成冗余拷贝会有个坑：重导前一个交易日要删日重建，那行已经被次日回填过的
-- 兑现结果就跟着没了——而这一天你多半只是在改错别字。
CREATE TABLE IF NOT EXISTS t_prediction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trade_date DATE NOT NULL COMMENT 'PLAN=下单这一行的那天；ANSWER=回写兑现结果的那天',
    kind VARCHAR(6) NOT NULL COMMENT 'PLAN=盘前三路径预判 / ANSWER=次日对答案',
    name VARCHAR(40) NOT NULL COMMENT '路径名。跨日对齐只认名称，所以名字必须每天复用，不能换说法',
    prob TINYINT DEFAULT NULL COMMENT 'PLAN：发生概率 0-100',
    condition_text VARCHAR(300) DEFAULT NULL COMMENT 'PLAN：触发条件原文',
    result VARCHAR(8) DEFAULT NULL COMMENT 'ANSWER：命中/落空/部分/违约',
    result_note VARCHAR(300) DEFAULT NULL COMMENT 'ANSWER：一句话依据',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_user_date_kind_name (user_id, trade_date, kind, name),
    INDEX idx_user_kind_date (user_id, kind, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预判留痕与对答案(复盘导入)';

-- 五大指数收盘：`指数:` 一天五行。公开数据、不绑用户，和 t_market_stock 同一族。
-- 腾讯日 K 接口已经在用（TencentClient.dailyBars），以后换成自动取数时这张表就是缓存，
-- 现在先让你手上的历史进得来——指数滞涨 vs 个股普跌这种背离，只有收盘价能看出来。
CREATE TABLE IF NOT EXISTS t_index_close (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date DATE NOT NULL,
    index_code VARCHAR(9) NOT NULL COMMENT 'sh000001/sz399001 之类不带市场前缀的 6-9 位码',
    index_name VARCHAR(20) NOT NULL DEFAULT '',
    close_price DECIMAL(12,2) DEFAULT NULL,
    change_pct DECIMAL(6,2) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_date_index (trade_date, index_code),
    INDEX idx_index_date (index_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='五大指数收盘(公开数据,不绑用户)';

-- ============ 存量库迁移 ============
-- 上面全是 CREATE TABLE IF NOT EXISTS，对已经建好的库一个字都不改。
-- 加列必须在这里另留一条可重复判断的 ALTER，否则存量库跑新代码会直接 Unknown column。
--
-- 2026-09-06 第 4 维并入家数封板率与回封率（打分同时开放 -1 档）：
-- ALTER TABLE t_daily_record
--     ADD COLUMN sealed_home_rate DECIMAL(5,2) DEFAULT NULL COMMENT '家数封板率(%)=涨停家数÷(涨停+炸板)' AFTER broken_board_rate,
--     ADD COLUMN reseal_rate DECIMAL(5,2) DEFAULT NULL COMMENT '回封率(%)=封住前曾打开的涨停家数÷(那些+炸板家数)' AFTER sealed_home_rate,
--     ADD COLUMN broken_note VARCHAR(300) DEFAULT NULL COMMENT '第 4 维三个子项的算式与出分' AFTER surv_note;
--
-- 2026-09-06 复盘 md 导入器（三张新表由上面的 CREATE TABLE IF NOT EXISTS 覆盖，重复跑无害）：
-- ALTER TABLE t_daily_record
--     ADD COLUMN up_count INT DEFAULT NULL COMMENT '上涨家数（复盘 md 导入，不参与打分）' AFTER limit_down_count,
--     ADD COLUMN down_count INT DEFAULT NULL COMMENT '下跌家数（同上）' AFTER up_count,
--     ADD COLUMN my_position_pct DECIMAL(5,2) DEFAULT NULL COMMENT '我的实际仓位%' AFTER mid_cap_stock,
--     ADD COLUMN review_md MEDIUMTEXT COMMENT '整篇复盘原文（含 meta 块）' AFTER tomorrow_plan;
--
-- 2026-09-07 各节判断文字（「导出复盘文档」要能带回你贴进来的定性）：
-- ALTER TABLE t_daily_record
--     ADD COLUMN doc_notes TEXT COMMENT '复盘文档各节判断文字(JSON：小节键→正文)' AFTER review_md;
--
-- 2026-09-08 子项人工覆盖八列（第 2/4/8/9 维的分项，NULL=未覆盖，清空即回退到自动值）：
-- ALTER TABLE t_daily_record
--     ADD COLUMN manual_sealed_home_rate DECIMAL(5,2) DEFAULT NULL COMMENT '第4维手改：家数封板率(%)' AFTER broken_note,
--     ADD COLUMN manual_reseal_rate DECIMAL(5,2) DEFAULT NULL COMMENT '第4维手改：回封率(%)' AFTER manual_sealed_home_rate,
--     ADD COLUMN manual_premium_low_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：低位组今日均涨幅(%)' AFTER manual_reseal_rate,
--     ADD COLUMN manual_premium_mid_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：中位组今日均涨幅(%)' AFTER manual_premium_low_pct,
--     ADD COLUMN manual_premium_high_pct DECIMAL(7,2) DEFAULT NULL COMMENT '第2维手改：高位组今日均涨幅(%)' AFTER manual_premium_mid_pct,
--     ADD COLUMN manual_anchor_score TINYINT DEFAULT NULL COMMENT '第8维手改：阵眼当日反馈分(0~3)' AFTER manual_premium_high_pct,
--     ADD COLUMN manual_surv_count INT DEFAULT NULL COMMENT '第9维手改：进分家数' AFTER manual_anchor_score,
--     ADD COLUMN manual_surv_premium DECIMAL(7,2) DEFAULT NULL COMMENT '第9维手改：进分组合当日均涨幅(%)' AFTER manual_surv_count;
--
-- 2026-09-08 复盘导入页撤掉，给「对照」补一列（原来它只活在 review_md 里，现读现展示）：
-- ALTER TABLE t_daily_record
--     ADD COLUMN compare_note VARCHAR(300) DEFAULT NULL COMMENT '手记与系统读数的对照，人工填' AFTER doc_notes;
-- 存量那两天（09-03/09-04）的对照值不回补：读取路径是"列优先、md 兜底"，老值照样看得见，
-- 等他下次在这天填一次才以列为准。
