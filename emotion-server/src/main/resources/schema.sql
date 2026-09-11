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

    -- ============ 五维双层模型(five_dim) 落库列（0-100 直加权，替换旧 9 维 -3~3 打分口径）============
    -- 上面九维 score_* / manual_premium_* 等列是旧口径，保留不删只为历史留痕，新引擎不再写它们。
    -- 新五个维分一律 DECIMAL(6,2) DEFAULT NULL：NULL=该维未评(整维剔出分母)，绝不写 0(0 分是"判到最重")。
    score_market     DECIMAL(6,2) DEFAULT NULL COMMENT '五维·大盘生态分(0-100)',
    score_theme_main DECIMAL(6,2) DEFAULT NULL COMMENT '五维·主线明确度分(0-100)',
    score_board      DECIMAL(6,2) DEFAULT NULL COMMENT '五维·连板生态分(0-100，含中位吹哨×0.8后)',
    score_first      DECIMAL(6,2) DEFAULT NULL COMMENT '五维·首板生态分(0-100)',
    score_anchor     DECIMAL(6,2) DEFAULT NULL COMMENT '五维·阵眼分(0-100)。注意与新列名一致，勿与旧 anchor_score(-3~3)混淆',
    -- 结构信号(可多选，四层 JR/Prem/Big 现算)与强制退潮(任一触发直接空仓，无视总分)：
    signal_flags     VARCHAR(200) DEFAULT NULL COMMENT '结构信号命中标签，逗号分隔：中位吹哨/高位抱团/抱团瓦解前兆/高低切/全面退潮',
    forced_ebb       TINYINT DEFAULT 0 COMMENT '强制退潮：1=命中任一硬条件，阶段直接判退潮(强制)',
    forced_ebb_reason VARCHAR(300) DEFAULT NULL COMMENT '强制退潮命中原因，中文，让人一眼看到为什么被强制空仓',
    -- 新模型人工输入(自动取数覆盖不到的子指标)；一律 DEFAULT NULL，NULL=未填=该子指标未评：
    manual_sector_limit_up_count    INT DEFAULT NULL COMMENT '五维手填：主线板块涨停数(家，第一主线今日)',
    manual_sector_premium_pct       DECIMAL(7,2) DEFAULT NULL COMMENT '五维手填：主线板块昨日涨停今均溢价(%)',
    manual_ladder_complete_score    DECIMAL(6,2) DEFAULT NULL COMMENT '五维手填：板块梯队完整性直接给分(0-100，完整梯队≈90/单高标无跟风≈35)',
    manual_theme_persistence_days   SMALLINT DEFAULT NULL COMMENT '五维手填：主线连续活跃天数(≥3 天=持续；首日=新启动)',
    manual_top_high_turnover_pct    DECIMAL(6,2) DEFAULT NULL COMMENT '五维手填：极高位龙头当日换手%(H≥7 时用于强制退潮"爆量断板"判据)',
    manual_first_premium_pct        DECIMAL(7,2) DEFAULT NULL COMMENT '五维手填：首板次日均溢价(%)，若溢价取数未纳入 board=1 时用此兜',
    -- 这三条盘面结构上没有可回补的取数口径(炸板池分不出首板/极高位断板要人看/监管折扣是主观乘数)，只能人判：
    manual_first_sealed_rate        DECIMAL(5,2) DEFAULT NULL COMMENT '五维手填：首板封住/(封住+炸) 百分比(%)，D4·首板封板率',
    manual_top_high_break           TINYINT DEFAULT NULL COMMENT '五维手填：极高位是否爆量断板未回封(1=是 0=否)，强制退潮条件 4 的闸门',
    manual_anchor_supervision_discount DECIMAL(3,2) DEFAULT NULL COMMENT '五维手填：阵眼监管折扣乘数(0-1)，留空=不打折',

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
    -- 【已停用】各节判断文字，JSON：{"index":"...","theme":"..."}。
    -- 2026-09-09 复盘页那块编辑口和导出回填一起下线：判断现在直接写在当天的复盘 md 里。
    -- 列和历史值都留着（不删数据），但没有任何读写方了——没有新需求别把它接回任何一条链路。
    doc_notes TEXT COMMENT '【已停用】复盘文档各节判断文字(JSON)，只留历史值，页与导出都不再读写',
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
    seal_amount DECIMAL(18,2) COMMENT '封单额(元)=东财fund,涨停池收盘封单资金',
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

-- ============ 打分模型 / 维度 / 计算规则（平台全局配置，不绑 user_id）============
-- 三张表把"有哪些维度、各多权重、温度分母多大"从代码里的常量搬到数据里：
--   t_scoring_model   一个模型一行（种一条 ultra_short = 超短情绪模型）。
--   t_scoring_dim     模型有哪些维度、每维权重多少（引擎按 dim_key 取权重配对，改数据即改打分）。
--   t_scoring_rule    每维的阈值档位登记——只供界面展示与复盘追溯，打分引擎不读它，
--                     真源永远是 TemperatureCalculator 里的 if 阶梯；复合逻辑(第2维子档加权、
--                     第4维三子项求平均、第8维取最差)压不进 (算子,阈值) 三元组，用 formula 一栏写人话。
-- 全表无外键(与库内其它表一致，级联在服务层做)；种子用 INSERT IGNORE 幂等，
-- 故意不用 ON DUPLICATE KEY——schema 会被重放到线上库，upsert 会覆盖掉管理员改过的权重。

CREATE TABLE IF NOT EXISTS t_scoring_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_key VARCHAR(40) NOT NULL COMMENT '稳定自然键 ultra_short：幂等种子按它认行，改展示名不许动它',
    name VARCHAR(50) NOT NULL COMMENT '展示名 超短情绪模型',
    max_score DECIMAL(7,2) DEFAULT NULL COMMENT '温度映射分母 M：温度=(加权和+M)/(2M)*100。NULL=按本模型已登记权重之和×每维满分3现推。刻意允许写死：分母要能脱离权重之和独立调(如 (加权和+81)/162 那类口径)，写死后改权重不再自动挪尺子。<=0 管理端直接拒',
    active TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=当前生效。全平台只应有一行为 1；多行 1 时按 id 最小取并打 warn，不靠 DB 随机选行',
    note VARCHAR(200) DEFAULT NULL COMMENT '口径来源(03篇/14天实测定标…)，给以后翻的人看',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_key (model_key),
    INDEX idx_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分模型登记(平台全局配置,不绑用户)';

CREATE TABLE IF NOT EXISTS t_scoring_dim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL COMMENT '所属模型，逻辑外键(t_scoring_model.id)，级联删除在服务层做',
    dim_key VARCHAR(20) NOT NULL COMMENT '维度键，必须属于 height/premium/breadth/broken/loss/volume/theme/anchor/surv。引擎只认这九个键，写别的键=凭空少一维且静默不报错，故 create/update 硬校验',
    dim_no SMALLINT NOT NULL COMMENT '引擎维序 1..9，界面上印 第 N 维 用它；与卡片摆放顺序无关(那是 sort_no)',
    label VARCHAR(30) NOT NULL COMMENT '卡面中文名，前端 DIMS[key].label 的来源',
    weight DECIMAL(4,2) NOT NULL COMMENT '权重，进分子的是 该维分×weight',
    sort_no SMALLINT DEFAULT NULL COMMENT '卡片展示序(成交额打头那串)。NULL=不上卡片',
    record_column VARCHAR(30) DEFAULT NULL COMMENT '该维的分落在 t_daily_record 哪一列，仅展示/追溯。第9维没有分列(由 surv_premium 现算)故 NULL；第8维是 anchor_score 不是 score_anchor',
    rule_engine VARCHAR(20) NOT NULL DEFAULT 'THRESHOLD_BAND' COMMENT '合成方式：THRESHOLD_BAND单套阶梯 / WEIGHTED_SUB_BANDS低中高三组各出分再按权重合成 / SUBITEM_AVERAGE三子项各出分取平均 / WORST_OF_MANY多只取最差 / MANUAL_PASSTHROUGH人工分夹到-3~3。算法体在 Java，本列只标它属于哪一类',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_dim (model_id, dim_key),
    INDEX idx_model_no (model_id, dim_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分模型维度登记：维集合与权重(平台全局配置,不绑用户)';

CREATE TABLE IF NOT EXISTS t_scoring_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL COMMENT '所属模型，逻辑外键(t_scoring_model.id)',
    dim_key VARCHAR(20) NOT NULL COMMENT '挂在哪一维。用 dim_key 不用 dim_id：管理端删掉再建同一维时 id 会变，按 id 挂会把规则孤儿掉',
    sub_key VARCHAR(30) NOT NULL DEFAULT '-' COMMENT '子档键：第2维 LOW/MID/HIGH，第4维 BROKEN_RATE/SEALED_HOME/RESEAL，单套阶梯用哨兵 -。刻意 NOT NULL——MySQL 唯一键不约束 NULL，两行 sub_key 为 NULL 能带同一 (model_id,dim_key,rule_no) 重复插入，幂等种子当场作废',
    rule_no SMALLINT NOT NULL COMMENT '命中顺序，小的先判。引擎的 if-ladder 是有序的(breadth/anchor 先判负档再判正档)，乱序会算出别的分，所以这一列是语义不是排版',
    operator VARCHAR(12) NOT NULL COMMENT 'GTE/GT/LTE/LT/EQ/BETWEEN/ELSE，外加结构行 GUARD(进分前置条件)/GROUP(子档权重,threshold_low 放权重)/AGG(子项合成方式)/COMPOUND(判据见 formula)',
    threshold_low DECIMAL(10,2) DEFAULT NULL COMMENT '阈值下界(BETWEEN 的下界；GROUP 行的子档权重)。单位随所属维走：家/亿/%/板',
    threshold_high DECIMAL(10,2) DEFAULT NULL COMMENT '阈值上界：只有第3维那种 涨停家数×跌停家数 成对判据的阶梯才两个都填',
    score TINYINT DEFAULT NULL COMMENT '命中给分(-3~3)。GUARD/GROUP/AGG 不出分，故允许 NULL 而不是 DEFAULT 0——兜 0 等于把前置条件当中性分',
    formula VARCHAR(160) DEFAULT NULL COMMENT '引擎不读这一列。跨操作数 OR、成对判据这类压不进(算子,阈值)三元组的规则，用这句人话摊给复盘的人核',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dim_rule (model_id, dim_key, sub_key, rule_no),
    INDEX idx_model_dim (model_id, dim_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='各维阈值档位登记(展示与追溯用，打分引擎不读；真源是 TemperatureCalculator 的 if-ladder)';

-- ---- 幂等种子：1 模型 + 9 维度 + 各维计算规则(阈值严格照 Java if 阶梯，非 stale javadoc) ----
INSERT IGNORE INTO t_scoring_model (model_key, name, max_score, active, note) VALUES
    ('ultra_short', '超短情绪模型', NULL, 1, '对齐03篇+14天实测定标；分母按权重和13×每维满分3=39现推');

SET @mid := (SELECT id FROM t_scoring_model WHERE model_key = 'ultra_short');

INSERT IGNORE INTO t_scoring_dim (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note) VALUES
    (@mid, 'height',  1, '连板高度',   1.00, 3, 'score_height',  'THRESHOLD_BAND',      NULL),
    (@mid, 'premium', 2, '分档溢价',   1.00, 4, 'score_premium', 'WEIGHTED_SUB_BANDS',  '低/中/高三组各出分再按 1:1.5:2.5 加权'),
    (@mid, 'breadth', 3, '涨停/跌停',  2.00, 2, 'score_breadth', 'THRESHOLD_BAND',      '成对判据，看 formula'),
    (@mid, 'broken',  4, '炸板率',     1.50, 9, 'score_broken',  'SUBITEM_AVERAGE',     '炸板率/家数封板率/回封率三子项取平均'),
    (@mid, 'loss',    5, '大面数',     1.00, 8, 'score_loss',    'THRESHOLD_BAND',      NULL),
    (@mid, 'volume',  6, '成交额',     2.00, 1, 'score_volume',  'THRESHOLD_BAND',      NULL),
    (@mid, 'theme',   7, '主线明确度', 2.00, 6, 'score_theme',   'MANUAL_PASSTHROUGH',  '人工判断，只做 -3~3 夹取'),
    (@mid, 'anchor',  8, '阵眼当日',   1.50, 7, 'anchor_score',  'WORST_OF_MANY',       '多只在位取最差'),
    (@mid, 'surv',    9, '异动监管',   1.00, 5, NULL,            'THRESHOLD_BAND',      '无独立分列，由 surv_premium 现算');

INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@mid,'height','-',1,'GTE',7,NULL,3,'最高连板 >=7','代码体:>=7=3,>=5=1,>=3=-1,<3=-3(javadoc旧)'),
    (@mid,'height','-',2,'GTE',5,NULL,1,'最高连板 >=5',''),
    (@mid,'height','-',3,'GTE',3,NULL,-1,'最高连板 >=3',''),
    (@mid,'height','-',4,'ELSE',NULL,NULL,-3,'最高连板 <3(含 2/1/0)',''),
    (@mid,'premium','LOW',0,'GROUP',1.00,NULL,NULL,'低位组权重','缺档权重整块摊回在场档位,不按0计入分母'),
    (@mid,'premium','MID',0,'GROUP',1.50,NULL,NULL,'中位组权重',''),
    (@mid,'premium','HIGH',0,'GROUP',2.50,NULL,NULL,'高位组权重',''),
    (@mid,'premium','-',1,'GT',5,NULL,3,'组均涨幅 >5%',''),
    (@mid,'premium','-',2,'GT',3,NULL,2,'组均涨幅 >3%',''),
    (@mid,'premium','-',3,'GT',1,NULL,0,'组均涨幅 >1%','代码体给0档,javadoc误作1'),
    (@mid,'premium','-',4,'GTE',0,NULL,-1,'组均涨幅 >=0%',''),
    (@mid,'premium','-',5,'GTE',-3,NULL,-2,'组均涨幅 >=-3%',''),
    (@mid,'premium','-',6,'GTE',-5,NULL,-3,'组均涨幅 >=-5%',''),
    (@mid,'premium','-',7,'ELSE',NULL,NULL,-3,'组均涨幅 <-5%',''),
    (@mid,'premium','-',8,'AGG',NULL,NULL,NULL,'三组各出分按1:1.5:2.5加权,BigDecimal HALF_UP scale0取整;三组全空未评',''),
    (@mid,'breadth','-',1,'COMPOUND',20,40,-3,'涨停<20 且 跌停>=40',''),
    (@mid,'breadth','-',2,'COMPOUND',30,20,-2,'涨停<30 且 跌停>=20',''),
    (@mid,'breadth','-',3,'COMPOUND',80,0,3,'涨停>80 且 跌停=0',''),
    (@mid,'breadth','-',4,'COMPOUND',60,3,2,'涨停>=60 且 跌停<3',''),
    (@mid,'breadth','-',5,'COMPOUND',50,5,1,'涨停>=50 且 跌停<5',''),
    (@mid,'breadth','-',6,'COMPOUND',40,10,0,'涨停属于[40,50) 或 跌停属于[5,10]',''),
    (@mid,'breadth','-',7,'ELSE',NULL,NULL,-1,'其余',''),
    (@mid,'broken','-',0,'AGG',NULL,NULL,NULL,'三子项各出分后BigDecimal HALF_UP scale0取平均;缺项按在场子项平均;三项全缺整维未评',''),
    (@mid,'broken','BROKEN_RATE',1,'LT',20,NULL,3,'炸板率 <20%',''),
    (@mid,'broken','BROKEN_RATE',2,'LT',30,NULL,2,'炸板率 <30%',''),
    (@mid,'broken','BROKEN_RATE',3,'LT',40,NULL,1,'炸板率 <40%',''),
    (@mid,'broken','BROKEN_RATE',4,'LT',50,NULL,-1,'炸板率 <50%','跳过0档'),
    (@mid,'broken','BROKEN_RATE',5,'LTE',70,NULL,-2,'炸板率 <=70%',''),
    (@mid,'broken','BROKEN_RATE',6,'ELSE',NULL,NULL,-3,'炸板率 >70%',''),
    (@mid,'broken','SEALED_HOME',1,'GTE',80,NULL,3,'家数封板率 >=80%','14天实测定标,非03篇'),
    (@mid,'broken','SEALED_HOME',2,'GTE',70,NULL,2,'家数封板率 >=70%',''),
    (@mid,'broken','SEALED_HOME',3,'GTE',55,NULL,1,'家数封板率 >=55%',''),
    (@mid,'broken','SEALED_HOME',4,'GTE',40,NULL,0,'家数封板率 >=40%',''),
    (@mid,'broken','SEALED_HOME',5,'ELSE',NULL,NULL,-3,'家数封板率 <40%',''),
    (@mid,'broken','RESEAL',1,'GTE',75,NULL,3,'回封率 >=75%','14天实测定标,非03篇'),
    (@mid,'broken','RESEAL',2,'GTE',60,NULL,2,'回封率 >=60%',''),
    (@mid,'broken','RESEAL',3,'GTE',45,NULL,1,'回封率 >=45%',''),
    (@mid,'broken','RESEAL',4,'GTE',30,NULL,0,'回封率 >=30%',''),
    (@mid,'broken','RESEAL',5,'ELSE',NULL,NULL,-3,'回封率 <30%',''),
    (@mid,'loss','-',1,'EQ',0,NULL,3,'大面数 =0',''),
    (@mid,'loss','-',2,'LTE',3,NULL,2,'大面数 <=3',''),
    (@mid,'loss','-',3,'LTE',7,NULL,1,'大面数 <=7',''),
    (@mid,'loss','-',4,'LTE',12,NULL,-1,'大面数 <=12',''),
    (@mid,'loss','-',5,'LTE',20,NULL,-2,'大面数 <=20',''),
    (@mid,'loss','-',6,'ELSE',NULL,NULL,-3,'大面数 >20',''),
    (@mid,'volume','-',0,'GUARD',NULL,NULL,NULL,'成交额 NULL 或 0 整维未评',''),
    (@mid,'volume','-',1,'GT',30000,NULL,3,'成交额 >30000亿',''),
    (@mid,'volume','-',2,'GTE',25000,NULL,2,'成交额 >=25000亿',''),
    (@mid,'volume','-',3,'GTE',19000,NULL,1,'成交额 >=19000亿',''),
    (@mid,'volume','-',4,'GTE',18000,NULL,0,'成交额 >=18000亿',''),
    (@mid,'volume','-',5,'GTE',17000,NULL,-1,'成交额 >=17000亿',''),
    (@mid,'volume','-',6,'GTE',15000,NULL,-2,'成交额 >=15000亿',''),
    (@mid,'volume','-',7,'ELSE',NULL,NULL,-3,'成交额 <15000亿',''),
    (@mid,'theme','-',1,'GTE',3,NULL,3,'人工分 >=3',''),
    (@mid,'theme','-',2,'GTE',2,NULL,2,'人工分 >=2',''),
    (@mid,'theme','-',3,'GTE',1,NULL,1,'人工分 >=1',''),
    (@mid,'theme','-',4,'LTE',-3,NULL,-3,'人工分 <=-3',''),
    (@mid,'theme','-',5,'LTE',-2,NULL,-2,'人工分 <=-2',''),
    (@mid,'theme','-',6,'LTE',-1,NULL,-1,'人工分 <=-1',''),
    (@mid,'theme','-',7,'ELSE',NULL,NULL,0,'其余夹到 0','输入=输出列 score_theme'),
    (@mid,'anchor','-',0,'GUARD',NULL,NULL,NULL,'span null/不可用/涨跌取不到 整维未评',''),
    (@mid,'anchor','-',1,'COMPOUND',NULL,NULL,-3,'收盘跌停 或 盘中触及跌停',''),
    (@mid,'anchor','-',2,'LTE',-5,NULL,-2,'涨跌 <=-5%',''),
    (@mid,'anchor','-',3,'COMPOUND',NULL,NULL,-1,'当日断板 isBrokeToday',''),
    (@mid,'anchor','-',4,'GTE',9.5,NULL,3,'涨跌 >=9.5%',''),
    (@mid,'anchor','-',5,'GT',0,NULL,2,'涨跌 >0%',''),
    (@mid,'anchor','-',6,'ELSE',NULL,NULL,0,'平盘',''),
    (@mid,'anchor','-',7,'AGG',NULL,NULL,NULL,'多只在位取最差min(取不到分的不参与);阵眼是哨兵不是投票',''),
    (@mid,'surv','-',0,'GUARD',NULL,NULL,NULL,'家数NULL/0 或 均溢价NULL 整维不进分母',''),
    (@mid,'surv','-',1,'GTE',9.5,NULL,3,'进分组合均涨幅 >=9.5%',''),
    (@mid,'surv','-',2,'GT',0,NULL,2,'进分组合均涨幅 >0%',''),
    (@mid,'surv','-',3,'GTE',-2,NULL,-1,'进分组合均涨幅 >=-2%','跳过+1与0,非bandPremium'),
    (@mid,'surv','-',4,'GTE',-5,NULL,-2,'进分组合均涨幅 >=-5%',''),
    (@mid,'surv','-',5,'ELSE',NULL,NULL,-3,'进分组合均涨幅 <-5%','');

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

--
-- 2026-09-10 打分模型三表 + 超短情绪模型种子：上面的 CREATE TABLE IF NOT EXISTS 与 INSERT IGNORE 自覆盖，
--            重复跑无害；存量库要拿这套新配置，整段重放即可，不会动已改过的权重。
-- 若要把温度口径从 39 分母切到截图的 (加权和+81)/162（注意：现有 9 维权重和=13、每维±3，加权和只到 ±39，
--            切到 81 后温度实际只能落在约 26..74，阶段里的 冰点<=15 与 高潮>=80 将永远取不到，
--            且需整体重 bless golden 与重调 determineStage 阈值，属打分口径重定标，不是建表这一步）：
-- UPDATE t_scoring_model SET max_score = 81.00 WHERE model_key = 'ultra_short';

-- =====================================================================================
-- ============ 五维双层模型 five_dim：新增中间层子指标表 t_scoring_sub + 全量种子 ============
-- =====================================================================================
-- 用户决定：直接替换 9 维引擎、真正数据驱动(权重+单指标 0-100 阈值从表里读出来打分)、尽量自动取数。
-- 与旧 ultra_short 的关系：five_dim 置 active=1，ultra_short active=0(旧种子/列保留不删，仅作历史留痕)。
-- t_scoring_rule.score 是 TINYINT(有符号 -128..127)，0~100 天然放得下，无需改列类型。
-- 引擎把 t_scoring_sub 装配成树(dim→sub→layer/grandchild)注入 ScoreInputs，按 scoring_kind 求值：
--   WEIGHTED_SUM         = Σ child.weight × eval(child)，未评 child 剔出分母(不兜 0)。
--   BAND_LADDER          = 读 metrics[source_key] 走本 sub 的 t_scoring_rule 阶梯(有序命中)。
--   LAYER_WEIGHTED_BAND  = 四层各 BAND_LADDER 出分再按层权重加权。
--   STRATEGY             = 命名算法在 Java(source_key 即策略名)，规则表里的 COMPOUND 行仅供展示/追溯，
--                          由 ScoringModelSeedParityTest 钉住"表登记分 == Java 常量"。
--   MANUAL               = 直接取 metrics[source_key] 夹到 0-100(定性子指标人工给分)。
-- 权重一律 0-1 小数。引擎用「已评子权重之和」归一化(Σw×分/Σw)，故权重和是否恰好=1 不影响维分落在 0-100：
--   顶层 5 维权重和=1(0.25/0.20/0.25/0.15/0.15)；连板四层 promo/premium/bigloss 内和=1(0.15/0.25/0.20/0.40)；
--   连板维 5 子按用户 spec 是 25/20/20/15/10(和=0.90)、首板 5 子 25/15/25/25/10(和=100)——连板的 0.90 靠归一化兜住，
--   全评时维分仍满 100，缺子时自动按剩余权重放大，不额外补 0。改任一层权重后请跑 ScoringModelSeedParityTest。

CREATE TABLE IF NOT EXISTS t_scoring_sub (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL COMMENT '所属模型，逻辑外键(t_scoring_model.id)',
    dim_key VARCHAR(20) NOT NULL COMMENT '挂在五维里的哪一维',
    sub_key VARCHAR(30) NOT NULL COMMENT '子指标键；连板维的四层用 promo_low/promo_mid/... 这类独立键',
    parent_sub_key VARCHAR(30) NOT NULL DEFAULT '-' COMMENT "'-'=维度直属的一级子指标；非-=挂在某复合子指标下的层或子叶(如连板·晋级·低)。刻意 NOT NULL：与 sub_key 同一幂等理由(MySQL UK 不约束 NULL)",
    label VARCHAR(30) NOT NULL,
    weight DECIMAL(5,4) NOT NULL COMMENT '在本父节点内的权重(0-1)。维内各子权重和=1；复合子下四层权重和=1；阵眼单叶=1',
    scoring_kind VARCHAR(22) NOT NULL COMMENT 'WEIGHTED_SUM / BAND_LADDER / LAYER_WEIGHTED_BAND / STRATEGY / MANUAL。算法体在 Java，本列标它走哪条求值',
    source_key VARCHAR(40) DEFAULT NULL COMMENT 'BAND_LADDER/MANUAL：去 ScoreInputs.metrics 取原始读数的键；STRATEGY：策略名(index_env/limit_combo/board_anchor)；WEIGHTED_SUM/LAYER_WEIGHTED_BAND：NULL(靠 children)',
    sort_no SMALLINT DEFAULT NULL COMMENT '展示序',
    note VARCHAR(200) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_dim_sub (model_id, dim_key, sub_key),
    INDEX idx_model_dim_parent (model_id, dim_key, parent_sub_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打分模型子指标/四层登记：维→子→层 的两/三层权重与取数键(平台全局配置,不绑用户)';

-- ---- 模型：five_dim 生效，ultra_short 退役(active 全平台只应有一行 1) ----
INSERT IGNORE INTO t_scoring_model (model_key, name, max_score, active, note) VALUES
    ('five_dim', '五维双层情绪模型', 100.00, 1, '0-100 直加权：总分=Σ(维分×维权)。≥85高潮/60-84发酵/40-59混沌/<40退潮；命中强制退潮直接退潮(强制)');

-- 把旧的超短模型下线。这是"直接替换"的落地开关；重放无害(幂等)。
UPDATE t_scoring_model SET active = 0 WHERE model_key = 'ultra_short' AND active = 1;

SET @fid := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim');

-- ---- 五维(权重和=1.00) ----
INSERT IGNORE INTO t_scoring_dim (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note) VALUES
    (@fid,'market',    1,'大盘生态',  0.25, 1,'score_market',    'WEIGHTED_SUM','指数环境35/量能25/广度20/涨跌停20'),
    (@fid,'theme_main',2,'主线明确度',0.20, 2,'score_theme_main','WEIGHTED_SUM','板块涨停30/梯队完整30/板块溢价25/持续性15'),
    (@fid,'board',     3,'连板生态',  0.25, 3,'score_board',     'WEIGHTED_SUM','晋级25/溢价20/大面20/炸板质量15/数量高度10；命中中位吹哨整维×0.8'),
    (@fid,'first',     4,'首板生态',  0.15, 4,'score_first',     'WEIGHTED_SUM','首板数25/首板封板率15/首板溢价25/1进2晋级25/1进2大面10'),
    (@fid,'anchor',    5,'阵眼',      0.15, 5,'score_anchor',    'WEIGHTED_SUM','空间板/核心龙 100%(状态分×监管折扣)');

-- ---- 子指标 / 四层（parent_sub_key='-' 直属维；非 '-' 是挂在复合子下的层/叶）----
-- 大盘生态
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'market','index_env',   '-','指数环境',0.35,'STRATEGY','index_env',1,'三指涨跌幅及协同性'),
    (@fid,'market','turnover',    '-','量能',    0.25,'BAND_LADDER','turnover_ratio',2,'成交额/20日均值'),
    (@fid,'market','breadth',     '-','广度',    0.20,'BAND_LADDER','red_ratio',3,'红盘率=上涨家数/(涨+跌)'),
    (@fid,'market','limit_combo', '-','涨跌停',  0.20,'STRATEGY','limit_combo',4,'涨停/跌停两操作数组合');
-- 主线明确度
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'theme_main','sector_limit_up','-','板块涨停数',0.30,'BAND_LADDER','sector_limit_up_count',1,'第一主线涨停数(人工,industry≠题材)'),
    (@fid,'theme_main','ladder_complete','-','梯队完整性',0.30,'MANUAL','ladder_complete_score',2,'有无断层(人工直接给 0-100)'),
    (@fid,'theme_main','sector_premium', '-','板块溢价',  0.25,'BAND_LADDER','sector_premium_pct',3,'主线昨日涨停今均溢价(人工)'),
    (@fid,'theme_main','persistence',    '-','持续性',    0.15,'BAND_LADDER','persistence_days',4,'连续活跃天数(人工)');
-- 连板生态：五个一级子
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'board','promo',         '-','晋级结构',0.25,'LAYER_WEIGHTED_BAND',NULL,1,'四层晋级率各出分再按 0.15/0.25/0.20/0.40 加权'),
    (@fid,'board','premium',       '-','溢价结构',0.20,'LAYER_WEIGHTED_BAND',NULL,2,'四层昨日连板今溢价'),
    (@fid,'board','bigloss',       '-','大面结构',0.20,'LAYER_WEIGHTED_BAND',NULL,3,'四层大面家数'),
    (@fid,'board','broken_quality', '-','炸板质量',0.15,'WEIGHTED_SUM',NULL,4,'0.6×封板率分+0.4×回封率分'),
    (@fid,'board','count_height',  '-','数量高度',0.10,'BAND_LADDER','board_total_count',5,'连板总家数(最高板H并入)');
-- 连板生态：三复合子 × 四层（每层权重和=1）
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'board','promo_low',     'promo','低位晋级',0.15,'BAND_LADDER','jr_low',1,'2板(固定)'),
    (@fid,'board','promo_mid',     'promo','中位晋级',0.25,'BAND_LADDER','jr_mid',2,'3-4板 吹哨锚点'),
    (@fid,'board','promo_midhigh', 'promo','中高位晋级',0.20,'BAND_LADDER','jr_midhigh',3,'5~⌈H/2⌉'),
    (@fid,'board','promo_top',     'promo','极高位晋级',0.40,'BAND_LADDER','jr_top',4,'⌈H/2⌉+1~H'),
    (@fid,'board','premium_low',     'premium','低位溢价',0.15,'BAND_LADDER','prem_low',1,''),
    (@fid,'board','premium_mid',     'premium','中位溢价',0.25,'BAND_LADDER','prem_mid',2,''),
    (@fid,'board','premium_midhigh', 'premium','中高位溢价',0.20,'BAND_LADDER','prem_midhigh',3,''),
    (@fid,'board','premium_top',     'premium','极高位溢价',0.40,'BAND_LADDER','prem_top',4,''),
    (@fid,'board','bigloss_low',     'bigloss','低位大面',0.15,'BAND_LADDER','big_low',1,''),
    (@fid,'board','bigloss_mid',     'bigloss','中位大面',0.25,'BAND_LADDER','big_mid',2,''),
    (@fid,'board','bigloss_midhigh', 'bigloss','中高位大面',0.20,'BAND_LADDER','big_midhigh',3,''),
    (@fid,'board','bigloss_top',     'bigloss','极高位大面',0.40,'BAND_LADDER','big_top',4,''),
    (@fid,'board','bq_sealed',  'broken_quality','家数封板率',0.60,'BAND_LADDER','sealed_home_rate',1,''),
    (@fid,'board','bq_reseal',  'broken_quality','回封率',    0.40,'BAND_LADDER','reseal_rate',2,'');
-- 首板生态
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'first','first_count',   '-','首板数量',  0.25,'BAND_LADDER','first_count',1,''),
    (@fid,'first','first_sealed',  '-','首板封板率',0.15,'BAND_LADDER','first_sealed_rate',2,'首板封住/(封住+炸)'),
    (@fid,'first','first_premium', '-','首板溢价',  0.25,'BAND_LADDER','first_premium_pct',3,'首板次日均溢价(取数未含board=1时用 manual 兜)'),
    (@fid,'first','promo_1to2',    '-','1进2晋级',  0.25,'BAND_LADDER','first_promo_1to2_rate',4,''),
    (@fid,'first','big_1to2',      '-','1进2大面',  0.10,'BAND_LADDER','first_1to2_big_count',5,'');
-- 阵眼
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid,'anchor','core','-','空间板/核心龙',1.00,'STRATEGY','board_anchor',1,'状态分×监管折扣');

-- ---- 0-100 阈值阶梯(BAND_LADDER 子) + 策略 COMPOUND 展示行 ----
-- 大盘
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'market','turnover',1,'GTE',1.20,NULL,90,'成交额/20日均 >=1.2','spec锚点1.2/0.95-1.1/0.7；余档占位待定标'),
    (@fid,'market','turnover',2,'GTE',0.95,NULL,70,'>=0.95',''),
    (@fid,'market','turnover',3,'GTE',0.70,NULL,45,'>=0.70',''),
    (@fid,'market','turnover',4,'ELSE',NULL,NULL,25,'<0.70',''),
    (@fid,'market','breadth',1,'GTE',0.60,NULL,85,'红盘率 >=0.6','spec锚点0.6/0.4-0.5/0.2；中间占位'),
    (@fid,'market','breadth',2,'GTE',0.40,NULL,55,'>=0.4',''),
    (@fid,'market','breadth',3,'GTE',0.20,NULL,30,'>=0.2',''),
    (@fid,'market','breadth',4,'ELSE',NULL,NULL,10,'<0.2',''),
    (@fid,'market','index_env',1,'COMPOUND',1.00,NULL,100,'三指均涨 >1%','STRATEGY:算法在Java,此行供展示/Parity'),
    (@fid,'market','index_env',2,'COMPOUND',NULL,NULL,40,'两跌一红',''),
    (@fid,'market','index_env',3,'COMPOUND',-1.00,NULL,20,'三指跌 >1%(均<-1%)',''),
    (@fid,'market','index_env',4,'COMPOUND',NULL,NULL,35,'三指全绿但均未破-1%(弱跌日)','STRATEGY:三指均<0且均≥-1%,Java算'),
    (@fid,'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标'),
    (@fid,'market','limit_combo',1,'COMPOUND',80,0,95,'涨停>=80 且 跌停=0','STRATEGY:两操作数,Java算'),
    (@fid,'market','limit_combo',2,'COMPOUND',40,8,45,'涨停40~60 且 跌停5~8',''),
    (@fid,'market','limit_combo',3,'COMPOUND',NULL,20,5,'跌停>20',''),
    (@fid,'market','limit_combo',4,'ELSE',NULL,NULL,50,'其余','中间档占位');
-- 主线
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'theme_main','sector_limit_up',1,'GTE',15,NULL,90,'主线涨停数 >=15','spec锚点15/6-9/3；中间占位'),
    (@fid,'theme_main','sector_limit_up',2,'GTE',10,NULL,78,'>=10',''),
    (@fid,'theme_main','sector_limit_up',3,'GTE',6,NULL,65,'>=6',''),
    (@fid,'theme_main','sector_limit_up',4,'GTE',3,NULL,45,'>=3',''),
    (@fid,'theme_main','sector_limit_up',5,'ELSE',NULL,NULL,30,'<3',''),
    (@fid,'theme_main','sector_premium',1,'GT',3,NULL,90,'主线板块溢价 >3%',''),
    (@fid,'theme_main','sector_premium',2,'GTE',0,NULL,55,'>=0','中间占位'),
    (@fid,'theme_main','sector_premium',3,'ELSE',NULL,NULL,20,'<0',''),
    (@fid,'theme_main','persistence',1,'GTE',3,NULL,85,'连续活跃 >=3天',''),
    (@fid,'theme_main','persistence',2,'GTE',2,NULL,68,'=2天','中间占位'),
    (@fid,'theme_main','persistence',3,'EQ',1,NULL,50,'首日','');
-- 连板：晋级四层(各 60/40/25/15)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','promo_low',1,'GTE',60,NULL,95,'层晋级率 >=60%',''),(@fid,'board','promo_low',2,'GTE',40,NULL,80,'>=40',''),(@fid,'board','promo_low',3,'GTE',25,NULL,65,'>=25',''),(@fid,'board','promo_low',4,'GTE',15,NULL,45,'>=15',''),(@fid,'board','promo_low',5,'ELSE',NULL,NULL,20,'<15',''),
    (@fid,'board','promo_mid',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_mid',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_mid',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_mid',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_mid',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo_midhigh',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_midhigh',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_midhigh',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_midhigh',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_midhigh',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo_top',1,'GTE',60,NULL,95,'',''),(@fid,'board','promo_top',2,'GTE',40,NULL,80,'',''),(@fid,'board','promo_top',3,'GTE',25,NULL,65,'',''),(@fid,'board','promo_top',4,'GTE',15,NULL,45,'',''),(@fid,'board','promo_top',5,'ELSE',NULL,NULL,20,'',''),
    (@fid,'board','promo',0,'GUARD',NULL,NULL,NULL,'中位吹哨:JR中<15% 或 (Prem中<0 且 Big中>=3) → 连板总分×0.8','信号表另有结构信号定义');
-- 连板：溢价四层(>3/1-3/0-1/0~-1/-1~-3/<-3)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','premium_low',1,'GT',3,NULL,95,'层溢价 >3%',''),(@fid,'board','premium_low',2,'GTE',1,NULL,80,'1~3',''),(@fid,'board','premium_low',3,'GTE',0,NULL,65,'0~1',''),(@fid,'board','premium_low',4,'GTE',-1,NULL,45,'0~-1',''),(@fid,'board','premium_low',5,'GTE',-3,NULL,25,'-1~-3',''),(@fid,'board','premium_low',6,'ELSE',NULL,NULL,5,'<-3',''),
    (@fid,'board','premium_mid',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_mid',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_mid',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_mid',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_mid',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_mid',6,'ELSE',NULL,NULL,5,'',''),
    (@fid,'board','premium_midhigh',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_midhigh',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_midhigh',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_midhigh',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_midhigh',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_midhigh',6,'ELSE',NULL,NULL,5,'',''),
    (@fid,'board','premium_top',1,'GT',3,NULL,95,'',''),(@fid,'board','premium_top',2,'GTE',1,NULL,80,'',''),(@fid,'board','premium_top',3,'GTE',0,NULL,65,'',''),(@fid,'board','premium_top',4,'GTE',-1,NULL,45,'',''),(@fid,'board','premium_top',5,'GTE',-3,NULL,25,'',''),(@fid,'board','premium_top',6,'ELSE',NULL,NULL,5,'','');
-- 连板：大面四层(0/1-2/3-5/5-10/>10)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','bigloss_low',1,'EQ',0,NULL,95,'大面家数 =0',''),(@fid,'board','bigloss_low',2,'LTE',2,NULL,80,'1~2',''),(@fid,'board','bigloss_low',3,'LTE',5,NULL,60,'3~5',''),(@fid,'board','bigloss_low',4,'LTE',10,NULL,35,'6~10',''),(@fid,'board','bigloss_low',5,'ELSE',NULL,NULL,10,'>10',''),
    (@fid,'board','bigloss_mid',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_mid',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_mid',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_mid',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_mid',5,'ELSE',NULL,NULL,10,'',''),
    (@fid,'board','bigloss_midhigh',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_midhigh',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_midhigh',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_midhigh',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_midhigh',5,'ELSE',NULL,NULL,10,'',''),
    (@fid,'board','bigloss_top',1,'EQ',0,NULL,95,'',''),(@fid,'board','bigloss_top',2,'LTE',2,NULL,80,'',''),(@fid,'board','bigloss_top',3,'LTE',5,NULL,60,'',''),(@fid,'board','bigloss_top',4,'LTE',10,NULL,35,'',''),(@fid,'board','bigloss_top',5,'ELSE',NULL,NULL,10,'','');
-- 连板：炸板质量两叶 + 数量高度
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'board','bq_sealed',1,'GTE',85,NULL,95,'封板率 >=85%','spec锚点85/45；中间占位'),(@fid,'board','bq_sealed',2,'GTE',70,NULL,80,'>=70',''),(@fid,'board','bq_sealed',3,'GTE',55,NULL,60,'>=55',''),(@fid,'board','bq_sealed',4,'GTE',45,NULL,40,'>=45',''),(@fid,'board','bq_sealed',5,'ELSE',NULL,NULL,15,'<45',''),
    (@fid,'board','bq_reseal',1,'GTE',75,NULL,95,'回封率 >=75%','档借封板率形态,待定标'),(@fid,'board','bq_reseal',2,'GTE',60,NULL,80,'>=60',''),(@fid,'board','bq_reseal',3,'GTE',45,NULL,60,'>=45',''),(@fid,'board','bq_reseal',4,'GTE',30,NULL,40,'>=30',''),(@fid,'board','bq_reseal',5,'ELSE',NULL,NULL,15,'<30',''),
    (@fid,'board','broken_quality',0,'AGG',NULL,NULL,NULL,'0.6×封板率分 + 0.4×回封率分',''),
    (@fid,'board','count_height',1,'GTE',25,NULL,95,'连板总家数 >=25','spec仅给>=25=95锚点,余占位;H并入'),(@fid,'board','count_height',2,'GTE',15,NULL,80,'>=15',''),(@fid,'board','count_height',3,'GTE',8,NULL,60,'>=8',''),(@fid,'board','count_height',4,'GTE',4,NULL,40,'>=4',''),(@fid,'board','count_height',5,'ELSE',NULL,NULL,20,'<4','');
-- 首板
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'first','first_count',1,'GTE',60,NULL,95,'首板家数 >=60','spec锚点60/10'),(@fid,'first','first_count',2,'GTE',40,NULL,80,'>=40',''),(@fid,'first','first_count',3,'GTE',25,NULL,60,'>=25',''),(@fid,'first','first_count',4,'GTE',10,NULL,40,'>=10',''),(@fid,'first','first_count',5,'ELSE',NULL,NULL,15,'<10',''),
    (@fid,'first','first_sealed',1,'GTE',80,NULL,95,'首板封板率 >=80%','spec锚点80/40'),(@fid,'first','first_sealed',2,'GTE',65,NULL,80,'>=65',''),(@fid,'first','first_sealed',3,'GTE',50,NULL,60,'>=50',''),(@fid,'first','first_sealed',4,'GTE',40,NULL,40,'>=40',''),(@fid,'first','first_sealed',5,'ELSE',NULL,NULL,15,'<40',''),
    (@fid,'first','first_premium',1,'GT',3,NULL,95,'首板溢价 >3%','spec锚点3/-1'),(@fid,'first','first_premium',2,'GTE',1,NULL,75,'1~3',''),(@fid,'first','first_premium',3,'GTE',-1,NULL,50,'-1~1',''),(@fid,'first','first_premium',4,'ELSE',NULL,NULL,25,'<-1',''),
    (@fid,'first','promo_1to2',1,'GTE',25,NULL,95,'1进2晋级率 >=25%','spec锚点25/5'),(@fid,'first','promo_1to2',2,'GTE',15,NULL,75,'>=15',''),(@fid,'first','promo_1to2',3,'GTE',5,NULL,45,'>=5',''),(@fid,'first','promo_1to2',4,'ELSE',NULL,NULL,20,'<5',''),
    (@fid,'first','big_1to2',1,'EQ',0,NULL,95,'1进2大面 =0','spec锚点0/10'),(@fid,'first','big_1to2',2,'LTE',3,NULL,75,'1~3',''),(@fid,'first','big_1to2',3,'LTE',6,NULL,45,'4~6',''),(@fid,'first','big_1to2',4,'LTE',10,NULL,25,'7~10',''),(@fid,'first','big_1to2',5,'ELSE',NULL,NULL,10,'>10','');
-- 阵眼(策略 COMPOUND 展示行)
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid,'anchor','core',1,'COMPOUND',NULL,NULL,95,'一字/涨停封住','STRATEGY:board_anchor,Java算'),
    (@fid,'anchor','core',2,'COMPOUND',NULL,NULL,55,'爆量断板',''),
    (@fid,'anchor','core',3,'COMPOUND',NULL,NULL,0,'核按钮/收盘跌停(同时触发强制退潮)',''),
    (@fid,'anchor','core',0,'GUARD',NULL,NULL,NULL,'监管折扣:命中 SEVERE/EXCH 时上述状态分×discount','');

-- ============ 存量库迁移(2026-09-10 五维双层模型)：可重复执行，缺哪列补哪列 ============
-- 上面全是 CREATE TABLE IF NOT EXISTS / INSERT IGNORE，对已经建好的库一个字都不改。
-- t_daily_record 早在九维时代就建好了，所以五维这 17 列只能在这里补——这是整个迁移里唯一
-- 不能靠"重放 schema.sql"自动完成的一步。
-- 漏掉它的现场表现很有欺骗性：每条碰 t_daily_record 的 SQL 都在
-- Unknown column 'score_market' 上炸掉，而后端用 HTTP 200 包 code=400 返回，
-- 仪表盘只会把红条一闪后显示"暂无数据 + 五张未评卡"，看起来像前端没接上。
-- 所以这里用 information_schema 先查后拼 ALTER：只补真缺的列，重放无害。
SET SESSION group_concat_max_len = 8192;

SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @five_dim_adds
  FROM (
  SELECT          1 ord_no, 'score_market' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·大盘生态分(0-100)''' col_ddl
  UNION ALL SELECT 2 ord_no, 'score_theme_main' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·主线明确度分(0-100)''' col_ddl
  UNION ALL SELECT 3 ord_no, 'score_board' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·连板生态分(0-100，含中位吹哨×0.8后)''' col_ddl
  UNION ALL SELECT 4 ord_no, 'score_first' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·首板生态分(0-100)''' col_ddl
  UNION ALL SELECT 5 ord_no, 'score_anchor' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维·阵眼分(0-100)，勿与旧 anchor_score(-3~3)混淆''' col_ddl
  UNION ALL SELECT 6 ord_no, 'signal_flags' col_name, 'VARCHAR(200) DEFAULT NULL COMMENT ''结构信号命中标签，逗号分隔''' col_ddl
  UNION ALL SELECT 7 ord_no, 'forced_ebb' col_name, 'TINYINT DEFAULT 0 COMMENT ''强制退潮：1=命中任一硬条件''' col_ddl
  UNION ALL SELECT 8 ord_no, 'forced_ebb_reason' col_name, 'VARCHAR(300) DEFAULT NULL COMMENT ''强制退潮命中原因，中文''' col_ddl
  UNION ALL SELECT 9 ord_no, 'manual_sector_limit_up_count' col_name, 'INT DEFAULT NULL COMMENT ''五维手填：主线板块涨停数(家)''' col_ddl
  UNION ALL SELECT 10 ord_no, 'manual_sector_premium_pct' col_name, 'DECIMAL(7,2) DEFAULT NULL COMMENT ''五维手填：主线板块昨日涨停今均溢价(%)''' col_ddl
  UNION ALL SELECT 11 ord_no, 'manual_ladder_complete_score' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维手填：板块梯队完整性给分(0-100)''' col_ddl
  UNION ALL SELECT 12 ord_no, 'manual_theme_persistence_days' col_name, 'SMALLINT DEFAULT NULL COMMENT ''五维手填：主线连续活跃天数''' col_ddl
  UNION ALL SELECT 13 ord_no, 'manual_top_high_turnover_pct' col_name, 'DECIMAL(6,2) DEFAULT NULL COMMENT ''五维手填：极高位龙头当日换手%(强制退潮判据)''' col_ddl
  UNION ALL SELECT 14 ord_no, 'manual_first_premium_pct' col_name, 'DECIMAL(7,2) DEFAULT NULL COMMENT ''五维手填：首板次日均溢价(%)，取数未纳入 board=1 时兜底''' col_ddl
  UNION ALL SELECT 15 ord_no, 'manual_first_sealed_rate' col_name, 'DECIMAL(5,2) DEFAULT NULL COMMENT ''五维手填：首板封住/(封住+炸) 百分比(%)''' col_ddl
  UNION ALL SELECT 16 ord_no, 'manual_top_high_break' col_name, 'TINYINT DEFAULT NULL COMMENT ''五维手填：极高位是否爆量断板未回封(1=是)，强制退潮条件4闸门''' col_ddl
  UNION ALL SELECT 17 ord_no, 'manual_anchor_supervision_discount' col_name, 'DECIMAL(3,2) DEFAULT NULL COMMENT ''五维手填：阵眼监管折扣乘数(0-1)，留空=不打折''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_daily_record'
       AND COLUMN_NAME  = need.col_name);

SET @five_dim_sql = IF(@five_dim_adds IS NULL,
    'SELECT ''t_daily_record 五维列已齐，本步跳过'' AS five_dim_migration',
    CONCAT('ALTER TABLE t_daily_record ', @five_dim_adds));
PREPARE five_dim_stmt FROM @five_dim_sql;
EXECUTE five_dim_stmt;
DEALLOCATE PREPARE five_dim_stmt;

-- 其余都由上面覆盖，不用手工做：t_scoring_sub 走 CREATE TABLE IF NOT EXISTS；
-- five_dim 模型/维/子/四层/阶梯种子走 INSERT IGNORE；ultra_short 下线走那句 UPDATE。
-- 建库跑完这一步后，还需要一次 POST /api/records/recalc-all 把历史按新口径重算，
-- 否则 score_* 五列全是 NULL（NULL 是"没算过"，不是"0 分"）。

-- =====================================================================================
-- ==== 五维双层模型 v2(five_dim_v2)：PRD 2.0 核心升级——主线5要素 + 阵眼龙头分工 ====
-- =====================================================================================
-- 参考《五维prd.txt》v2.0：
--   主线明确度 20% 拆 5 要素：涨停聚集度25 / 高度聚集度25 / 成交额聚集度20 / 催化剂硬度15 / 持续性15
--   阵眼 15% 拆龙头分工：总龙头50 / 中军20 / 跟风15 / 卡位10 / 反包5
-- 与 five_dim 的关系：five_dim_v2 置 active=1，five_dim/ultra_short 退役(旧种子/列保留，仅作历史留痕)。
-- 取数来源：自动读数由 PrdMetricsService 从 t_market_stock(涨停/炸板池) + t_theme 推导；
--   缺读数的键=该子未评(引擎按已评权重归一化)，成交额聚集度自动取数未覆盖，走人工列 manual_amount_gather_pct。
INSERT IGNORE INTO t_scoring_model (model_key, name, max_score, active, note) VALUES
    ('five_dim_v2', '五维双层情绪模型 v2(PRD)', 100.00, 1,
     'PRD2.0：主线明确度=5要素(涨停聚集度25/高度聚集度25/成交额聚集度20/催化剂硬度15/持续性15)；阵眼=龙头分工(总龙头50/中军20/跟风15/卡位10/反包5)。其余三维持五维口径');

-- 旧模型下线(幂等，重放无害)。
UPDATE t_scoring_model SET active = 0 WHERE model_key = 'five_dim' AND active = 1;

SET @fid2 := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim_v2');

-- ---- 五维(权重和=1.00；与 five_dim 同维同权，仅 D2/D5 子结构升级) ----
INSERT IGNORE INTO t_scoring_dim (model_id, dim_key, dim_no, label, weight, sort_no, record_column, rule_engine, note) VALUES
    (@fid2,'market',    1,'大盘生态',  0.25, 1,'score_market',    'WEIGHTED_SUM','指数环境35/量能25/广度20/涨跌停20'),
    (@fid2,'theme_main',2,'日内核心',  0.20, 2,'score_theme_main','WEIGHTED_SUM','日内最热行业5要素：涨停聚集度25/高度聚集度25/成交额聚集度20/催化剂硬度15/持续性15；连续3交易日热度≥5才收集为主线龙头'),
    (@fid2,'board',     3,'连板生态',  0.25, 3,'score_board',     'WEIGHTED_SUM','晋级25/溢价20/大面20/炸板质量15/数量高度10；命中中位吹哨整维×0.8'),
    (@fid2,'first',     4,'首板生态',  0.15, 4,'score_first',     'WEIGHTED_SUM','首板数25/首板封板率15/首板溢价25/1进2晋级25/1进2大面10'),
    (@fid2,'anchor',    5,'阵眼',      0.15, 5,'score_anchor',    'WEIGHTED_SUM','PRD龙头分工：总龙头50/中军20/跟风15/卡位10/反包5');

-- ---- 子指标（D2 五要素 / D5 龙头分工；D1/D3/D4 与 five_dim 逐字一致）----
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'market','index_env',   '-','指数环境',0.35,'STRATEGY','index_env',1,'三指涨跌幅及协同性'),
    (@fid2,'market','turnover',    '-','量能',    0.25,'BAND_LADDER','turnover_ratio',2,'成交额/20日均值'),
    (@fid2,'market','breadth',     '-','广度',    0.20,'BAND_LADDER','red_ratio',3,'红盘率=上涨家数/(涨+跌)'),
    (@fid2,'market','limit_combo', '-','涨跌停',  0.20,'STRATEGY','limit_combo',4,'涨停/跌停两操作数组合');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'theme_main','zt_gather',    '-','涨停聚集度',  0.25,'BAND_LADDER','zt_gather_pct',   1,'主线板块涨停数/全市场涨停数(PrdMetricsService自动)'),
    (@fid2,'theme_main','height_gather','-','高度聚集度',  0.25,'BAND_LADDER','height_gather_pct',2,'主线最高板/全市场最高板(自动)'),
    (@fid2,'theme_main','amount_gather','-','成交额聚集度',0.20,'BAND_LADDER','amount_gather_pct',3,'主线成交额占比(人工列，自动取数未覆盖)'),
    (@fid2,'theme_main','catalyst',     '-','催化剂硬度',  0.15,'BAND_LADDER','catalyst_hardness', 4,'题材硬度1-5(主线龙头页维护，无匹配题材=未评)'),
    (@fid2,'theme_main','persistence',  '-','持续性',      0.15,'BAND_LADDER','persistence_days',  5,'主线连续活跃天数(自动，当日≥3家涨停算活跃)');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'board','promo',         '-','晋级结构',0.25,'LAYER_WEIGHTED_BAND',NULL,1,'四层晋级率各出分再按 0.15/0.25/0.20/0.40 加权'),
    (@fid2,'board','premium',       '-','溢价结构',0.20,'LAYER_WEIGHTED_BAND',NULL,2,'四层昨日连板今溢价'),
    (@fid2,'board','bigloss',       '-','大面结构',0.20,'LAYER_WEIGHTED_BAND',NULL,3,'四层大面家数'),
    (@fid2,'board','broken_quality', '-','炸板质量',0.15,'WEIGHTED_SUM',NULL,4,'0.6×封板率分+0.4×回封率分'),
    (@fid2,'board','count_height',  '-','数量高度',0.10,'BAND_LADDER','board_total_count',5,'连板总家数(最高板H并入)');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'board','promo_low',     'promo','低位晋级',0.15,'BAND_LADDER','jr_low',1,'2板(固定)'),
    (@fid2,'board','promo_mid',     'promo','中位晋级',0.25,'BAND_LADDER','jr_mid',2,'3-4板 吹哨锚点'),
    (@fid2,'board','promo_midhigh', 'promo','中高位晋级',0.20,'BAND_LADDER','jr_midhigh',3,'5~⌈H/2⌉'),
    (@fid2,'board','promo_top',     'promo','极高位晋级',0.40,'BAND_LADDER','jr_top',4,'⌈H/2⌉+1~H'),
    (@fid2,'board','premium_low',     'premium','低位溢价',0.15,'BAND_LADDER','prem_low',1,''),
    (@fid2,'board','premium_mid',     'premium','中位溢价',0.25,'BAND_LADDER','prem_mid',2,''),
    (@fid2,'board','premium_midhigh', 'premium','中高位溢价',0.20,'BAND_LADDER','prem_midhigh',3,''),
    (@fid2,'board','premium_top',     'premium','极高位溢价',0.40,'BAND_LADDER','prem_top',4,''),
    (@fid2,'board','bigloss_low',     'bigloss','低位大面',0.15,'BAND_LADDER','big_low',1,''),
    (@fid2,'board','bigloss_mid',     'bigloss','中位大面',0.25,'BAND_LADDER','big_mid',2,''),
    (@fid2,'board','bigloss_midhigh', 'bigloss','中高位大面',0.20,'BAND_LADDER','big_midhigh',3,''),
    (@fid2,'board','bigloss_top',     'bigloss','极高位大面',0.40,'BAND_LADDER','big_top',4,''),
    (@fid2,'board','bq_sealed',  'broken_quality','家数封板率',0.60,'BAND_LADDER','sealed_home_rate',1,''),
    (@fid2,'board','bq_reseal',  'broken_quality','回封率',    0.40,'BAND_LADDER','reseal_rate',2,'');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'first','first_count',   '-','首板数量',  0.25,'BAND_LADDER','first_count',1,''),
    (@fid2,'first','first_sealed',  '-','首板封板率',0.15,'BAND_LADDER','first_sealed_rate',2,'首板封住/(封住+炸)'),
    (@fid2,'first','first_premium', '-','首板溢价',  0.25,'BAND_LADDER','first_premium_pct',3,'首板次日均溢价(取数未含board=1时用 manual 兜)'),
    (@fid2,'first','promo_1to2',    '-','1进2晋级',  0.25,'BAND_LADDER','first_promo_1to2_rate',4,''),
    (@fid2,'first','big_1to2',      '-','1进2大面',  0.10,'BAND_LADDER','first_1to2_big_count',5,'');
INSERT IGNORE INTO t_scoring_sub (model_id, dim_key, sub_key, parent_sub_key, label, weight, scoring_kind, source_key, sort_no, note) VALUES
    (@fid2,'anchor','dragon_zong_long','-','总龙头',0.50,'MANUAL','dragon_zong_long',1,'温度计：晋级/持稳/断板/核按钮(PrdMetricsService算 0-100 直读)'),
    (@fid2,'anchor','dragon_zhong_jun','-','中军',  0.20,'MANUAL','dragon_zhong_jun',2,'容量担当：主线内次高标均涨幅映射(自动)'),
    (@fid2,'anchor','dragon_gen_feng', '-','跟风',  0.15,'MANUAL','dragon_gen_feng', 3,'强度：主线内跟风连板家数(自动)'),
    (@fid2,'anchor','dragon_ka_wei',   '-','卡位',  0.10,'MANUAL','dragon_ka_wei',   4,'分歧护盘：他题材高标封住=80/炸板=40(自动)'),
    (@fid2,'anchor','dragon_fan_bao',  '-','反包',  0.05,'MANUAL','dragon_fan_bao',  5,'修复：昨炸板今回封家数(自动)');

-- ---- 0-100 阈值阶梯(D2 五要素 BAND_LADDER) + D1/D3/D4 与 five_dim 同 + D5 MANUAL 无阶梯 ----
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'market','turnover',1,'GTE',1.20,NULL,90,'成交额/20日均 >=1.2','spec锚点1.2/0.95-1.1/0.7；余档占位待定标'),
    (@fid2,'market','turnover',2,'GTE',0.95,NULL,70,'>=0.95',''),
    (@fid2,'market','turnover',3,'GTE',0.70,NULL,45,'>=0.70',''),
    (@fid2,'market','turnover',4,'ELSE',NULL,NULL,25,'<0.70',''),
    (@fid2,'market','breadth',1,'GTE',0.60,NULL,85,'红盘率 >=0.6','spec锚点0.6/0.4-0.5/0.2；中间占位'),
    (@fid2,'market','breadth',2,'GTE',0.40,NULL,55,'>=0.4',''),
    (@fid2,'market','breadth',3,'GTE',0.20,NULL,30,'>=0.2',''),
    (@fid2,'market','breadth',4,'ELSE',NULL,NULL,10,'<0.2',''),
    (@fid2,'market','index_env',1,'COMPOUND',1.00,NULL,100,'三指均涨 >1%','STRATEGY:算法在Java,此行供展示/Parity'),
    (@fid2,'market','index_env',2,'COMPOUND',NULL,NULL,40,'两跌一红',''),
    (@fid2,'market','index_env',3,'COMPOUND',-1.00,NULL,20,'三指跌 >1%(均<-1%)',''),
    (@fid2,'market','index_env',4,'COMPOUND',NULL,NULL,35,'三指全绿但均未破-1%(弱跌日)','STRATEGY:三指均<0且均≥-1%,Java算'),
    (@fid2,'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标'),
    (@fid2,'market','limit_combo',1,'COMPOUND',80,0,95,'涨停>=80 且 跌停=0','STRATEGY:两操作数,Java算'),
    (@fid2,'market','limit_combo',2,'COMPOUND',40,8,45,'涨停40~60 且 跌停5~8',''),
    (@fid2,'market','limit_combo',3,'COMPOUND',NULL,20,5,'跌停>20',''),
    (@fid2,'market','limit_combo',4,'ELSE',NULL,NULL,50,'其余','中间档占位');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'theme_main','zt_gather',1,'GTE',40,NULL,95,'涨停聚集度 >=40%','锚点：主线占四成涨停=极强'),
    (@fid2,'theme_main','zt_gather',2,'GTE',30,NULL,82,'>=30',''),
    (@fid2,'theme_main','zt_gather',3,'GTE',20,NULL,68,'>=20',''),
    (@fid2,'theme_main','zt_gather',4,'GTE',10,NULL,48,'>=10',''),
    (@fid2,'theme_main','zt_gather',5,'ELSE',NULL,NULL,28,'<10','分散无主线'),
    (@fid2,'theme_main','height_gather',1,'GTE',90,NULL,95,'高度聚集度 >=90%','主线即市场最高板'),
    (@fid2,'theme_main','height_gather',2,'GTE',70,NULL,85,'>=70',''),
    (@fid2,'theme_main','height_gather',3,'GTE',50,NULL,70,'>=50',''),
    (@fid2,'theme_main','height_gather',4,'GTE',30,NULL,50,'>=30',''),
    (@fid2,'theme_main','height_gather',5,'ELSE',NULL,NULL,28,'<30',''),
    (@fid2,'theme_main','amount_gather',1,'GTE',40,NULL,95,'成交额聚集度 >=40%','人工列 manual_amount_gather_pct'),
    (@fid2,'theme_main','amount_gather',2,'GTE',25,NULL,80,'>=25',''),
    (@fid2,'theme_main','amount_gather',3,'GTE',15,NULL,60,'>=15',''),
    (@fid2,'theme_main','amount_gather',4,'ELSE',NULL,NULL,35,'<15',''),
    (@fid2,'theme_main','catalyst',1,'GTE',5,NULL,100,'硬度5星(政策/产业级)','主线龙头页维护'),
    (@fid2,'theme_main','catalyst',2,'GTE',4,NULL,80,'4星',''),
    (@fid2,'theme_main','catalyst',3,'GTE',3,NULL,60,'3星(行业/事件)',''),
    (@fid2,'theme_main','catalyst',4,'GTE',2,NULL,40,'2星',''),
    (@fid2,'theme_main','catalyst',5,'GTE',1,NULL,20,'1星(Pure情绪)',''),
    (@fid2,'theme_main','catalyst',6,'ELSE',NULL,NULL,0,'非法值',''),
    (@fid2,'theme_main','persistence',1,'GTE',5,NULL,95,'连续活跃 >=5天','当日主线≥3家涨停算活跃1天'),
    (@fid2,'theme_main','persistence',2,'GTE',3,NULL,85,'>=3天',''),
    (@fid2,'theme_main','persistence',3,'GTE',2,NULL,70,'=2天',''),
    (@fid2,'theme_main','persistence',4,'GTE',1,NULL,50,'首日',''),
    (@fid2,'theme_main','persistence',5,'ELSE',NULL,NULL,25,'中断','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','promo_low',1,'GTE',60,NULL,95,'层晋级率 >=60%',''),(@fid2,'board','promo_low',2,'GTE',40,NULL,80,'>=40',''),(@fid2,'board','promo_low',3,'GTE',25,NULL,65,'>=25',''),(@fid2,'board','promo_low',4,'GTE',15,NULL,45,'>=15',''),(@fid2,'board','promo_low',5,'ELSE',NULL,NULL,20,'<15',''),
    (@fid2,'board','promo_mid',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_mid',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_mid',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_mid',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_mid',5,'ELSE',NULL,NULL,20,'',''),
    (@fid2,'board','promo_midhigh',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_midhigh',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_midhigh',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_midhigh',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_midhigh',5,'ELSE',NULL,NULL,20,'',''),
    (@fid2,'board','promo_top',1,'GTE',60,NULL,95,'',''),(@fid2,'board','promo_top',2,'GTE',40,NULL,80,'',''),(@fid2,'board','promo_top',3,'GTE',25,NULL,65,'',''),(@fid2,'board','promo_top',4,'GTE',15,NULL,45,'',''),(@fid2,'board','promo_top',5,'ELSE',NULL,NULL,20,'',''),
    (@fid2,'board','promo',0,'GUARD',NULL,NULL,NULL,'中位吹哨:JR中<15% 或 (Prem中<0 且 Big中>=3) → 连板总分×0.8','信号表另有结构信号定义');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','premium_low',1,'GT',3,NULL,95,'层溢价 >3%',''),(@fid2,'board','premium_low',2,'GTE',1,NULL,80,'1~3',''),(@fid2,'board','premium_low',3,'GTE',0,NULL,65,'0~1',''),(@fid2,'board','premium_low',4,'GTE',-1,NULL,45,'0~-1',''),(@fid2,'board','premium_low',5,'GTE',-3,NULL,25,'-1~-3',''),(@fid2,'board','premium_low',6,'ELSE',NULL,NULL,5,'<-3',''),
    (@fid2,'board','premium_mid',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_mid',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_mid',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_mid',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_mid',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_mid',6,'ELSE',NULL,NULL,5,'',''),
    (@fid2,'board','premium_midhigh',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_midhigh',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_midhigh',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_midhigh',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_midhigh',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_midhigh',6,'ELSE',NULL,NULL,5,'',''),
    (@fid2,'board','premium_top',1,'GT',3,NULL,95,'',''),(@fid2,'board','premium_top',2,'GTE',1,NULL,80,'',''),(@fid2,'board','premium_top',3,'GTE',0,NULL,65,'',''),(@fid2,'board','premium_top',4,'GTE',-1,NULL,45,'',''),(@fid2,'board','premium_top',5,'GTE',-3,NULL,25,'',''),(@fid2,'board','premium_top',6,'ELSE',NULL,NULL,5,'','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','bigloss_low',1,'EQ',0,NULL,95,'大面家数 =0',''),(@fid2,'board','bigloss_low',2,'LTE',2,NULL,80,'1~2',''),(@fid2,'board','bigloss_low',3,'LTE',5,NULL,60,'3~5',''),(@fid2,'board','bigloss_low',4,'LTE',10,NULL,35,'6~10',''),(@fid2,'board','bigloss_low',5,'ELSE',NULL,NULL,10,'>10',''),
    (@fid2,'board','bigloss_mid',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_mid',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_mid',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_mid',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_mid',5,'ELSE',NULL,NULL,10,'',''),
    (@fid2,'board','bigloss_midhigh',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_midhigh',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_midhigh',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_midhigh',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_midhigh',5,'ELSE',NULL,NULL,10,'',''),
    (@fid2,'board','bigloss_top',1,'EQ',0,NULL,95,'',''),(@fid2,'board','bigloss_top',2,'LTE',2,NULL,80,'',''),(@fid2,'board','bigloss_top',3,'LTE',5,NULL,60,'',''),(@fid2,'board','bigloss_top',4,'LTE',10,NULL,35,'',''),(@fid2,'board','bigloss_top',5,'ELSE',NULL,NULL,10,'','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'board','bq_sealed',1,'GTE',85,NULL,95,'封板率 >=85%','spec锚点85/45；中间占位'),(@fid2,'board','bq_sealed',2,'GTE',70,NULL,80,'>=70',''),(@fid2,'board','bq_sealed',3,'GTE',55,NULL,60,'>=55',''),(@fid2,'board','bq_sealed',4,'GTE',45,NULL,40,'>=45',''),(@fid2,'board','bq_sealed',5,'ELSE',NULL,NULL,15,'<45',''),
    (@fid2,'board','bq_reseal',1,'GTE',75,NULL,95,'回封率 >=75%','档借封板率形态,待定标'),(@fid2,'board','bq_reseal',2,'GTE',60,NULL,80,'>=60',''),(@fid2,'board','bq_reseal',3,'GTE',45,NULL,60,'>=45',''),(@fid2,'board','bq_reseal',4,'GTE',30,NULL,40,'>=30',''),(@fid2,'board','bq_reseal',5,'ELSE',NULL,NULL,15,'<30',''),
    (@fid2,'board','broken_quality',0,'AGG',NULL,NULL,NULL,'0.6×封板率分 + 0.4×回封率分',''),
    (@fid2,'board','count_height',1,'GTE',25,NULL,95,'连板总家数 >=25','spec仅给>=25=95锚点,余占位;H并入'),(@fid2,'board','count_height',2,'GTE',15,NULL,80,'>=15',''),(@fid2,'board','count_height',3,'GTE',8,NULL,60,'>=8',''),(@fid2,'board','count_height',4,'GTE',4,NULL,40,'>=4',''),(@fid2,'board','count_height',5,'ELSE',NULL,NULL,20,'<4','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'first','first_count',1,'GTE',60,NULL,95,'首板家数 >=60','spec锚点60/10'),(@fid2,'first','first_count',2,'GTE',40,NULL,80,'>=40',''),(@fid2,'first','first_count',3,'GTE',25,NULL,60,'>=25',''),(@fid2,'first','first_count',4,'GTE',10,NULL,40,'>=10',''),(@fid2,'first','first_count',5,'ELSE',NULL,NULL,15,'<10',''),
    (@fid2,'first','first_sealed',1,'GTE',80,NULL,95,'首板封板率 >=80%','spec锚点80/40'),(@fid2,'first','first_sealed',2,'GTE',65,NULL,80,'>=65',''),(@fid2,'first','first_sealed',3,'GTE',50,NULL,60,'>=50',''),(@fid2,'first','first_sealed',4,'GTE',40,NULL,40,'>=40',''),(@fid2,'first','first_sealed',5,'ELSE',NULL,NULL,15,'<40',''),
    (@fid2,'first','first_premium',1,'GT',3,NULL,95,'首板溢价 >3%','spec锚点3/-1'),(@fid2,'first','first_premium',2,'GTE',1,NULL,75,'1~3',''),(@fid2,'first','first_premium',3,'GTE',-1,NULL,50,'-1~1',''),(@fid2,'first','first_premium',4,'ELSE',NULL,NULL,25,'<-1',''),
    (@fid2,'first','promo_1to2',1,'GTE',25,NULL,95,'1进2晋级率 >=25%','spec锚点25/5'),(@fid2,'first','promo_1to2',2,'GTE',15,NULL,75,'>=15',''),(@fid2,'first','promo_1to2',3,'GTE',5,NULL,45,'>=5',''),(@fid2,'first','promo_1to2',4,'ELSE',NULL,NULL,20,'<5',''),
    (@fid2,'first','big_1to2',1,'EQ',0,NULL,95,'1进2大面 =0','spec锚点0/10'),(@fid2,'first','big_1to2',2,'LTE',3,NULL,75,'1~3',''),(@fid2,'first','big_1to2',3,'LTE',6,NULL,45,'4~6',''),(@fid2,'first','big_1to2',4,'LTE',10,NULL,25,'7~10',''),(@fid2,'first','big_1to2',5,'ELSE',NULL,NULL,10,'>10','');
INSERT IGNORE INTO t_scoring_rule (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note) VALUES
    (@fid2,'anchor','dragon_zong_long',1,'COMPOUND',NULL,NULL,95,'晋级(封住且晋级)','MANUAL直读:PrdMetricsService算 60+6×连板数 封顶100'),
    (@fid2,'anchor','dragon_zong_long',2,'COMPOUND',NULL,NULL,55,'持稳(封住未晋级)',''),
    (@fid2,'anchor','dragon_zong_long',3,'COMPOUND',NULL,NULL,10,'断板(收跌>5%=10,否则25)',''),
    (@fid2,'anchor','dragon_zong_long',4,'COMPOUND',NULL,NULL,0,'核按钮/跌停/大面',''),
    (@fid2,'anchor','dragon_zhong_jun',1,'COMPOUND',NULL,NULL,NULL,'50+中军均涨幅×5 夹0-100','MANUAL直读'),
    (@fid2,'anchor','dragon_gen_feng',1,'COMPOUND',NULL,NULL,NULL,'min(100,跟风连板家数×20)','MANUAL直读'),
    (@fid2,'anchor','dragon_ka_wei',1,'COMPOUND',NULL,NULL,80,'他题材高标封住','MANUAL直读:炸板=40'),
    (@fid2,'anchor','dragon_fan_bao',1,'COMPOUND',NULL,NULL,NULL,'min(100,昨炸今回封家数×40)','MANUAL直读');

-- ============ 存量库迁移(2026-09-10 five_dim_v2)：可重复执行，缺哪列补哪列 ============
-- t_theme 补催化剂硬度列（主线详情页展示 + D2·催化剂硬度自动取数源）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @theme_v2_adds
  FROM (
  SELECT 1 ord_no, 'catalyst_hardness' col_name,
         'TINYINT DEFAULT 3 COMMENT ''题材催化硬度1-5(5=政策/产业级,1=Pure情绪)；PRD D2·催化剂硬度取数源''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_theme'
       AND COLUMN_NAME  = need.col_name);
SET @theme_v2_sql = IF(@theme_v2_adds IS NULL,
    'SELECT ''t_theme 催化硬度列已齐，本步跳过'' AS theme_v2_migration',
    CONCAT('ALTER TABLE t_theme ', @theme_v2_adds));
PREPARE theme_v2_stmt FROM @theme_v2_sql;
EXECUTE theme_v2_stmt;
DEALLOCATE PREPARE theme_v2_stmt;

-- t_daily_record 补成交额聚集度人工列（PRD D2·要素3，自动取数未覆盖，走手填）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @record_v2_adds
  FROM (
  SELECT 1 ord_no, 'manual_amount_gather_pct' col_name,
         'DECIMAL(6,2) DEFAULT NULL COMMENT ''v2手填：主线成交额聚集度(%)=主线板块成交额/两市成交额''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_daily_record'
       AND COLUMN_NAME  = need.col_name);
SET @record_v2_sql = IF(@record_v2_adds IS NULL,
    'SELECT ''t_daily_record v2 列已齐，本步跳过'' AS record_v2_migration',
    CONCAT('ALTER TABLE t_daily_record ', @record_v2_adds));
PREPARE record_v2_stmt FROM @record_v2_sql;
EXECUTE record_v2_stmt;
DEALLOCATE PREPARE record_v2_stmt;

-- D2 改名「主线明确度」→「主线生态」：INSERT IGNORE 不会改存量行，这里幂等收敛一次（重放无害）。
UPDATE t_scoring_dim SET label = '主线生态'
 WHERE model_id = @fid2 AND dim_key = 'theme_main' AND label = '主线明确度';

-- D2 再改名「主线生态」→「日内核心」（2026-09-10 规则调整：最热行业=日内核心，连续3交易日有热度才收集为主线龙头）。
UPDATE t_scoring_dim SET label = '日内核心'
 WHERE model_id = @fid2 AND dim_key = 'theme_main' AND label = '主线生态';

-- ============ 存量库迁移(2026-09-10 盘面形态三字段)：可重复执行，缺哪列补哪列 ============
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @stock_shape_adds
  FROM (
  SELECT 1 ord_no, 'seal_amount' col_name,
         'DECIMAL(18,2) DEFAULT NULL COMMENT ''封单额(元)=东财fund,涨停池收盘封单资金''' col_ddl
  UNION ALL SELECT 2 ord_no, 'first_seal_time' col_name,
         'INT DEFAULT NULL COMMENT ''首次封板时间HHMMSS(fbt),判一字/T字用''' col_ddl
  UNION ALL SELECT 3 ord_no, 'last_seal_time' col_name,
         'INT DEFAULT NULL COMMENT ''最后封板时间HHMMSS(lbt)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_market_stock'
       AND COLUMN_NAME  = need.col_name);
SET @stock_shape_sql = IF(@stock_shape_adds IS NULL,
    'SELECT ''t_market_stock 形态三列已齐，本步跳过'' AS stock_shape_migration',
    CONCAT('ALTER TABLE t_market_stock ', @stock_shape_adds));
PREPARE stock_shape_stmt FROM @stock_shape_sql;
EXECUTE stock_shape_stmt;
DEALLOCATE PREPARE stock_shape_stmt;

-- 重放完成后如需把历史按 v2 口径重算：POST /api/records/recalc-all。

-- ============ 存量库迁移(2026-09-11 D1 指数环境补「三指全绿弱跌」档) ============
-- 背景：原 index_env 只有 100/40/20/ELSE60，三指全绿但均未破-1% 落进兜底 60（虚高）。
-- 新增 rule_no=4 = 35（弱跌日），原 ELSE60 顺延 rule_no=5。INSERT IGNORE 改不了存量 rule_no=4，
-- 这里幂等收敛一次（新库这两条与种子同值，重放无害）。
UPDATE t_scoring_rule
   SET operator='COMPOUND', threshold_low=NULL, threshold_high=NULL, score=35,
       formula='三指全绿但均未破-1%(弱跌日)', note='STRATEGY:三指均<0且均≥-1%,Java算'
 WHERE model_id IN (@fid, @fid2) AND dim_key='market' AND sub_key='index_env' AND rule_no=4;

INSERT IGNORE INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid, 'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标'),
  (@fid2,'market','index_env',5,'ELSE',NULL,NULL,60,'其余混合/微动(含平盘)','中间档占位待定标');

