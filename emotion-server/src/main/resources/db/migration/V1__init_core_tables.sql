-- Flyway migration V1: init core tables
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 5-254 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
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
    -- 【2026-09-13 客观/主观隔离】下面九个客观列（max_consecutive_limit / limit_up_count /
    -- limit_down_count / up_count / down_count / yesterday_limit_premium / broken_board_rate /
    -- big_loss_count / total_volume）已迁移到全局表 t_market_daily，Java 实体标 exist=false 不再读写；
    -- 旧列仅为历史留痕保留，新库建了也是空壳。
    max_consecutive_limit INT DEFAULT NULL COMMENT '[已停用,迁t_market_daily]连板高度',
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
    is_main_line TINYINT DEFAULT 0 COMMENT '是否为人工主线题材(题材表可升级到主线区)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_cycle_id (cycle_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题材-个股关系表(按日快照)：题材(概念)与板块(行业)的根本差异是"一只票可归多个题材"，
-- 所以题材计数必须用 is_primary 主题材去重；这里记(code 可出现在多个 theme_id 下，一票多题材)。
-- 人工归类=MANUAL；自动回填热门行业= AUTO。
CREATE TABLE IF NOT EXISTS t_theme_stock (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    theme_id    BIGINT      NOT NULL COMMENT '关联 t_theme.id',
    trade_date  DATE        NOT NULL COMMENT '按日快照(题材轮换可回溯)',
    code        VARCHAR(6)  NOT NULL COMMENT '6位代码',
    name        VARCHAR(20) DEFAULT '' COMMENT '冗余简称',
    industry    VARCHAR(20) DEFAULT '' COMMENT '冗余行业，便于题材→板块映射',
    is_primary  TINYINT     DEFAULT 1 COMMENT '1=主题材(参与涨停数统计) 0=辅题材(仅关联不计数)',
    source      VARCHAR(8)  DEFAULT 'MANUAL' COMMENT 'MANUAL人工/AUTO自动回填',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_theme_date_code (theme_id, trade_date, code),
    INDEX idx_user_date (user_id, trade_date),
    INDEX idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题材-个股关系(按日)';

-- 老库 t_theme 没有 is_main_line：幂等补列(MySQL < 8.0.29 不支持 ADD COLUMN IF NOT EXISTS，用预编译判存在)
SET @themes_col = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme' AND COLUMN_NAME = 'is_main_line');
SET @themes_ddl = IF(@themes_col = 0,
    'ALTER TABLE t_theme ADD COLUMN is_main_line TINYINT DEFAULT 0 COMMENT ''1=人工主线题材''',
    'SELECT 0');
PREPARE themes_stmt FROM @themes_ddl; EXECUTE themes_stmt; DEALLOCATE PREPARE themes_stmt;

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
    anchor_id BIGINT DEFAULT NULL COMMENT '锚定龙头关联的人工阵眼(t_anchor.id)；NULL=未关联(仍可手填)',
    theme VARCHAR(50) DEFAULT NULL COMMENT '所属题材/板块(系统B必填)',
    d0_score DECIMAL(5,2) DEFAULT NULL COMMENT 'D0当日情绪总分(five_dim总分或旧温度)，节点产生时市场温度',
    d0_cycle VARCHAR(20) DEFAULT NULL COMMENT 'D0当日周期阶段(退潮/启动/发酵/高潮/...)',
    last_recalc_at DATETIME DEFAULT NULL COMMENT '上次复算(T+1自动判定/人工复算)时间，数据新鲜度',
    conclusion_reason VARCHAR(30) DEFAULT NULL COMMENT '状态来路细分原因：有效·强/有效·中等/有效·板块达标；反包失效/晋级清零失效。权威写在采纳时，读完端再按需叠加监管/情绪退潮标签',
    filter_passed TINYINT DEFAULT 0 COMMENT '前置过滤器是否通过',
    filter_detail TEXT COMMENT '过滤器明细(JSON)',
    status VARCHAR(20) DEFAULT '待验证' COMMENT '待验证/有效/失效',
    status_note VARCHAR(300) COMMENT '状态来路：这条状态/失效原因是按哪几个数算出来的，一句话',
    note TEXT COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_anchor (anchor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
