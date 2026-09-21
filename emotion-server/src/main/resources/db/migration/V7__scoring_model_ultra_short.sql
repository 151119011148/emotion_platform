-- Flyway migration V7: scoring model ultra short
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 591-741 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
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
