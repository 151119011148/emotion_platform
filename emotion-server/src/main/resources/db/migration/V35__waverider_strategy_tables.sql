-- Flyway migration V35: WaveRider 连板周期选股策略引擎的七张表
-- ⚠️ 版本号说明：PRD 里把这两个迁移预留成 V29 / V30，但落地时必须是 35 / 36。
--    那两个号在历史上被跳过了（库已应用到 V34），回填低号会被 Flyway 判成 out-of-order：
--        Validate failed: Detected resolved migration not applied to database: 29
--    版本号只增不减，这是迁移体系的硬约束，不是笔误。
--
-- -----------------------------------------------------------------------------
-- 落地 PRD《短线连板周期选股策略引擎》§10.2。表设计有三个要点：
--
-- ① 版本不可变（t_strategy_version append-only）
--    任何配置修改都产生新版本号，历史版本只读。t_strategy_run.version_id 落快照，
--    保证「当初是用什么参数算出这批候选」永远可查 —— 这是回测与复盘可信的前提，
--    也是本引擎与「直接改一张 config 表」最大的区别。
--
-- ② 运行留痕与候选明细是两件事，别混在一张表里
--    t_strategy_run 记「跑过几次、什么状态、耗时多久、有没有告警」；
--    t_candidate_stock 存「那天选出来的是谁」。重跑同一天时：
--      · run 每次新增一行 —— PRD §11 要求留痕不覆盖，AC-9 也要求同一天连跑三次能看到三行；
--      · 候选整日替换 —— AC-9 同时要求候选行数不变。所以候选表按
--        (strategy_id, trade_date) 先删后插，唯一键只用来防「同一次 run 内重复写同一只票」。
--
-- ③ 与 PRD §10.2 草案的两处有意偏离（照抄会出问题，别改回去）
--    a) t_strategy_run 的唯一键。草案写的是
--       UNIQUE (strategy_id, trade_date, trigger_type, version_id)，
--       但同一天同一触发方式连跑三次必然撞唯一键、第二行根本插不进去，
--       与 §11「每次运行产出独立行并保留历史」及 AC-9 直接矛盾。这里改为普通索引。
--    b) t_node_detect.node_type 放宽到 VARCHAR(20)。草案写 VARCHAR(10)，
--       而 V34 引入的空间破局类型 'SPACE_BREAK' 是 11 个字符，装不下。
--
-- 写法：普通 DDL，不加复合语句（Flyway 6.5.7 的 MySQL 解析器按分号切语句，
--       CREATE PROCEDURE / BEGIN...END 会被切碎报 ERROR 1064）。
--       一律 CREATE TABLE IF NOT EXISTS，保证重放安全。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_strategy (
  id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id            BIGINT       NOT NULL                COMMENT '归属账号',
  name               VARCHAR(60)  NOT NULL                COMMENT '策略名称',
  enabled            TINYINT      NOT NULL DEFAULT 1      COMMENT '是否启用：到点还跑不跑',
  current_version_id BIGINT                DEFAULT NULL   COMMENT '当前生效版本(t_strategy_version.id)',
  description        VARCHAR(300)          DEFAULT NULL   COMMENT '策略说明（人写的，仅展示）',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 策略主实体：名称 + 启用状态 + 当前版本指针';

-- append-only：只插入，不更新。要改配置就写一个新版本号。
-- config_hash 用来实现「内容一模一样就不产生新版本」，避免反复保存把版本号刷成噪音。
CREATE TABLE IF NOT EXISTS t_strategy_version (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  strategy_id  BIGINT       NOT NULL                COMMENT '所属策略',
  version_no   INT          NOT NULL                COMMENT '版本序号，同一策略内从 1 递增',
  config_json  LONGTEXT     NOT NULL                COMMENT '完整配置快照(JSON)，本引擎的全部可调项都在这里',
  config_hash  CHAR(32)     NOT NULL                COMMENT '配置MD5；与最新版本相同则复用版本、不新增',
  change_note  VARCHAR(300)          DEFAULT NULL   COMMENT '这次改了什么（人写的）',
  created_by   BIGINT                DEFAULT NULL   COMMENT '操作人',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_strategy_version (strategy_id, version_no),
  KEY idx_hash (strategy_id, config_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 策略版本快照(append-only，历史只读)';

-- 不绑 user_id：模板是系统预置的三套起步配置（主升浪 / 退潮期 / 震荡市），所有人的一样。
-- 套用模板 = 读模板 config_json，写成一个新的策略版本，不产生新策略实体。
CREATE TABLE IF NOT EXISTS t_strategy_template (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  template_code VARCHAR(40)  NOT NULL                COMMENT 'MAIN_UP / RETREAT / RANGE',
  template_name VARCHAR(60)  NOT NULL                COMMENT '展示名',
  config_json   LONGTEXT     NOT NULL                COMMENT '预置参数',
  sort_no       INT          NOT NULL DEFAULT 0      COMMENT '展示顺序',
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 内置策略模板';

-- 唯一键有意只到 (strategy_id, trade_date) 的普通索引，理由见文件头 ③a。
CREATE TABLE IF NOT EXISTS t_strategy_run (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  strategy_id     BIGINT       NOT NULL                COMMENT '策略',
  version_id      BIGINT       NOT NULL                COMMENT '本次运行使用的版本快照，保证可复算',
  trade_date      DATE         NOT NULL                COMMENT '针对哪个交易日选股',
  trigger_type    VARCHAR(12)  NOT NULL                COMMENT 'SCHEDULE/MANUAL/BACKTEST',
  dry_run         TINYINT      NOT NULL DEFAULT 0      COMMENT '1=试运行，只回结果不落候选表',
  status          VARCHAR(12)  NOT NULL                COMMENT 'RUNNING/SUCCESS/EMPTY/FAILED/SKIPPED',
  candidate_count INT          NOT NULL DEFAULT 0      COMMENT '产出候选数',
  node_count      INT          NOT NULL DEFAULT 0      COMMENT '当日命中的节点数',
  warning         VARCHAR(120)          DEFAULT NULL   COMMENT 'CANDIDATE_TRUNCATED / TOPIC_FALLBACK / NO_NODE ...',
  error_msg       VARCHAR(500)          DEFAULT NULL   COMMENT '失败原因',
  detail_json     TEXT                  DEFAULT NULL   COMMENT '逐级漏斗计数(候选→过滤器→评分)，供界面解释「为什么只剩这几只」',
  started_at      DATETIME     NOT NULL                COMMENT '开始时间',
  finished_at     DATETIME              DEFAULT NULL   COMMENT '结束时间',
  cost_ms         INT                   DEFAULT NULL   COMMENT '耗时毫秒',
  PRIMARY KEY (id),
  KEY idx_strategy_date (strategy_id, trade_date),
  KEY idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 运行留痕：每次运行一行，不覆盖历史';

-- node_type 放宽到 20 字符，理由见文件头 ③b。
-- source=AUTO 由策略的节点规则识别写；source=MANUAL 是交易员在界面上手动标的，
-- 人工标记的 confirmed 才有意义（AUTO 行 confirmed 恒 0，表示「还没被人工表态」）。
CREATE TABLE IF NOT EXISTS t_node_detect (
  id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  user_id      BIGINT       NOT NULL                COMMENT '归属账号',
  strategy_id  BIGINT                DEFAULT NULL   COMMENT 'NULL=人工标记，不属于任何策略',
  trade_date   DATE         NOT NULL                COMMENT '节点日',
  node_type    VARCHAR(20)  NOT NULL                COMMENT 'START/DIVERGE/SWITCH/SPACE_BREAK/SPACE_BREAK_NEXT',
  rule_id      VARCHAR(40)           DEFAULT NULL   COMMENT '命中的规则标识',
  hit_expr     TEXT                  DEFAULT NULL   COMMENT '命中的表达式与实测数值，事后能解释「凭什么说这天是节点」',
  metrics_json TEXT                  DEFAULT NULL   COMMENT '当日 market.* 快照，便于脱离外部依赖复算',
  source       VARCHAR(8)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL',
  confirmed    TINYINT      NOT NULL DEFAULT 0      COMMENT '人工表态：1=确认，-1=取消，0=未表态',
  confirmed_by BIGINT                DEFAULT NULL   COMMENT '表态人',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_date_type (user_id, trade_date, node_type),
  KEY idx_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 节点识别结果：自动识别 + 人工标记共用';

-- 没有 user_id：候选是公开吗？不是——它由 t_strategy 间接归属账号，
-- 而策略本身已带 user_id，再加一遍是冗余。查询一律经 strategy_id 走索引。
CREATE TABLE IF NOT EXISTS t_candidate_stock (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  run_id              BIGINT       NOT NULL                COMMENT '产出它的那次运行',
  strategy_id         BIGINT       NOT NULL                COMMENT '冗余一份策略，避免查候选都要 join run',
  trade_date          DATE         NOT NULL                COMMENT '候选产生日 D（买不到的这天）',
  rank_no             INT          NOT NULL                COMMENT '展示顺序：按可执行性排，不是按分数',
  code                VARCHAR(6)   NOT NULL                COMMENT '证券代码',
  name                VARCHAR(20)  NOT NULL                COMMENT '证券名称',
  board               INT                   DEFAULT NULL   COMMENT 'D 日连板数',
  topic               VARCHAR(50)           DEFAULT NULL   COMMENT '主归属题材',
  concepts_json       TEXT                  DEFAULT NULL   COMMENT '全部命中题材(多归属)',
  hit_principles_json VARCHAR(60)  NOT NULL                COMMENT 'POSITION/NODE，可同时命中',
  node_type           VARCHAR(20)           DEFAULT NULL   COMMENT '命中节点类型',
  node_date           DATE                  DEFAULT NULL   COMMENT '节点日',
  position_type       VARCHAR(30)           DEFAULT NULL   COMMENT '身位描述，如「出版最高板」',
  score               DECIMAL(6,2)          DEFAULT NULL   COMMENT '多因子得分',
  suggest_position    DECIMAL(5,4)          DEFAULT NULL   COMMENT '建议仓位(小数)，单票上限由配置决定',
  risk_flag           VARCHAR(40)           DEFAULT NULL   COMMENT 'YIZI_THIN 一字缩量 / HIGH_TURNOVER 高位过度换手 / DRAGON_DEAD 龙头断板',
  filter_detail_json  TEXT                  DEFAULT NULL   COMMENT '过滤器逐条判定明细，界面点开追溯用的就是它',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_code (run_id, code),
  KEY idx_date_strategy (trade_date, strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 候选池明细：重跑整日替换，不按 run 累积';

-- 比 PRD 草案多两列 t1_open / gap_pct。原因见下方注释（B 口径）。
CREATE TABLE IF NOT EXISTS t_candidate_t1 (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  strategy_id   BIGINT       NOT NULL                COMMENT '策略',
  trade_date    DATE         NOT NULL                COMMENT '候选产生日 D',
  code          VARCHAR(6)   NOT NULL                COMMENT '证券代码',
  board         INT                   DEFAULT NULL   COMMENT 'D 日连板数',
  t1_date       DATE         NOT NULL                COMMENT '验证日 D+1',
  t1_open       DECIMAL(12,3)         DEFAULT NULL   COMMENT 'D+1 开盘价(不复权，与昨收同源)',
  gap_pct       DECIMAL(6,2)          DEFAULT NULL   COMMENT '隔夜跳空 open(D+1)/close(D)-1 %。这是唯一能在 D 日事后算出的「买不买得到」前哨',
  t1_change_pct DECIMAL(6,2)          DEFAULT NULL   COMMENT 'D+1 收盘涨幅%（相对 D 日收盘，即 A 口径分量）',
  t1_pool       VARCHAR(4)            DEFAULT NULL   COMMENT 'D+1 所属池 ZT/ZB/DT，NULL=三池均无',
  promoted      TINYINT      NOT NULL DEFAULT 0      COMMENT '是否晋级（仍涨停且连板数 = D 日 + 1）',
  max_chg       DECIMAL(6,2)          DEFAULT NULL   COMMENT 'D+1 盘中最高涨幅%，算盈利空间用',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_strategy_date_code (strategy_id, trade_date, code),
  KEY idx_t1_date (t1_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='WaveRider 候选票 D+1 表现，供复盘与权重回归';

-- -----------------------------------------------------------------------------
-- t_stock 补 listed_at：filter_new_stock_days（次新股过滤）需要它。
-- 按 V26 的 information_schema 幂等套路补，不得改用存储过程（理由见 V26 文件头）。
-- 新库从 V1 回放时该列已存在，本段走空操作。
-- -----------------------------------------------------------------------------
SET @v35_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_stock' AND COLUMN_NAME = 'listed_at');
SET @v35_ddl = IF(@v35_c = 0,
    'ALTER TABLE t_stock ADD COLUMN listed_at DATE DEFAULT NULL COMMENT ''上市日期，次新股过滤(filter_new_stock_days)用；NULL=未知，该股不因新股被剔''',
    'SELECT 0');
PREPARE v35_stmt FROM @v35_ddl; EXECUTE v35_stmt; DEALLOCATE PREPARE v35_stmt;

-- -----------------------------------------------------------------------------
-- 三套内置模板的种子数据（PRD §17 附录：v1.1 建议默认值直接作为模板种子）。
--
-- 与 PRD 附录的两处取值说明，别按附录照抄：
--   · filter_min_amount 取 1.0 而不是 0.5。附录里 0.5 的依据是「成交额 IC −0.285」，
--     该系数在实盘口径（T+1 开盘买）下归零到 +0.020，下调失去支持，按 §12.5.5
--     退回中性值。它现在的角色是「低于此值只打 MARK 不剔除」，见 filter_conflict_policy。
--   · sort_by 默认 executability 而不是 weighted_score。§6.2 的权重是在
--     「以 T 日涨停价为起算价」的旧口径上标定的，而那条口径实盘不可成交；
--     在可执行的子样本（gap ≤ entry_gap_max）里，连板数、成交额、首封时间的排序力
--     都不成立（连板数 IC 仅 +0.092 且 2/3/4/5 板非单调）。所以先按可执行性排，
--     weighted_score 仍会算出 score 落库，等 B 口径重标定后再切回去。
--
-- node_type_weights 的键一律大写，与 t_node_detect.node_type 的存储值对齐。
-- INSERT IGNORE：允许以后用新迁移改默认值，而不与这台机器上已调过的模板打架。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO t_strategy_template (template_code, template_name, config_json, sort_no) VALUES
('MAIN_UP', '主升浪',
 '{"position_mode":"highest_board","min_board_count":3,"max_same_position":1,"topic_source":"em_industry","degrade_on_dragon_death":true,"degrade_board_threshold":4,"node_scan_window":5,"threshold_mode":"dynamic","node_type_weights":{"START":1.2,"SWITCH":0.8,"DIVERGE":0.6},"node_stock_limit_per_theme":1,"max_select_rate":0.15,"filter_st":true,"filter_new_stock_days":30,"filter_min_amount":1.0,"filter_conflict_policy":"MARK","sort_by":"executability","score_weights":{"board":0.5,"position":0.3,"node":0.2,"timing":0.1,"risk":0.2},"max_position_per_stock":0.08,"entry_price_basis":"open_next","entry_gap_max":0.03,"reject_unbuyable_open":true}',
 1),
('RETREAT', '退潮期',
 '{"position_mode":"highest_board","min_board_count":2,"max_same_position":3,"topic_source":"em_industry","degrade_on_dragon_death":true,"degrade_board_threshold":3,"node_scan_window":5,"threshold_mode":"dynamic","node_type_weights":{"START":1.0,"SWITCH":0.8,"DIVERGE":0.6},"node_stock_limit_per_theme":1,"max_select_rate":0.1,"filter_st":true,"filter_new_stock_days":30,"filter_min_amount":1.0,"filter_conflict_policy":"MARK","sort_by":"executability","score_weights":{"board":0.4,"position":0.2,"node":0.2,"timing":0.1,"risk":0.3},"max_position_per_stock":0.02,"entry_price_basis":"open_next","entry_gap_max":0.02,"reject_unbuyable_open":true}',
 2),
('RANGE', '震荡市',
 '{"position_mode":"highest_board","min_board_count":2,"max_same_position":2,"topic_source":"em_industry","degrade_on_dragon_death":true,"degrade_board_threshold":4,"node_scan_window":5,"threshold_mode":"dynamic","node_type_weights":{"START":1.0,"SWITCH":0.8,"DIVERGE":0.6},"node_stock_limit_per_theme":1,"max_select_rate":0.15,"filter_st":true,"filter_new_stock_days":30,"filter_min_amount":1.0,"filter_conflict_policy":"MARK","sort_by":"executability","score_weights":{"board":0.5,"position":0.3,"node":0.2,"timing":0.1,"risk":0.2},"max_position_per_stock":0.05,"entry_price_basis":"open_next","entry_gap_max":0.03,"reject_unbuyable_open":true}',
 3);
