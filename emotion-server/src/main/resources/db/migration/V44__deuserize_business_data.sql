-- Flyway migration V44: 业务数据去用户隔离——user_id 从 13 张业务表删除
-- -----------------------------------------------------------------------------
-- 背景：业务表几乎每张都带 user_id，每读每写都按它过滤，于是新账号登进来看到的是空库：
--       仪表盘、温度曲线、节点追踪全空。这不是故障，是隔离。而这些数据是「这一天市场怎么样」，
--       不是「这个账号怎么看」——按账号复制一份，同一天的涨停家数就有了 N 个版本互相打架
--       （t_daily_record 里 8 个日期存在 2-3 个属主的重复行就是这件事）。
--       用户裁定：业务数据全平台共享，user_id 这一列直接删；只有持仓台账继续按账号记。
--
-- 口径（与用户确认）：
--   · 保留谁：t_user 里 role=SUPER_ADMIN 的那个账号（现网实测只有 gaofeng，id=2）。
--     其余账号名下的业务行是测试号留下的自动种子或空壳，删。下面用 @keep 而不是硬写 2，
--     万一角色漂了，@keep 变 NULL → DELETE 一条不匹配 → 后面 ADD UNIQUE KEY 撞上重复行
--     大声失败，此时列还一根没动，是可以 repair 重来的一次失败。
--   · 隔离保留：t_position / t_position_history（「这是你的账」），本次一字不改。
--   · 快照表不动：t_daily_record_bak_pre_d4fix / _bak_pre_ladder 没有实体映射，是历史备份。
--   · 已全局的先例：t_market_daily / t_market_stock / t_daily_bar / t_surveillance /
--     t_premium_tier / t_zt_perf / t_stock 本来就没有 user_id，这张迁移把剩下的表对齐到同一口径。
--
-- 实测删掉的行（2026-10-09 20:07 生产只读重测；下面第一版是 10-08 盘的，会过期——见 ① 段头）：
--     t_theme_stock 87,375（source=AUTO，读时由 IntradayService.rebuildAutoForDate 重建）
--     t_theme 2,679（绝大多数是 status=萌芽/is_main_line=0/hardness=3 的种子默认值）
--     t_theme_daily_snapshot 70（与 @keep 同 (trade_date,rank)，删完才谈得上 uk_date_rank）
--     t_daily_record 12（涉及的日期 @keep 都有自己一行，不丢交易日）
--     t_node_event 4（2 行全空；2 行是测试号 58 的 旅游及景/桂林旅游——见下面「退路」）
--     t_leading_stock 2（测试号 1 的两条 寒武纪）
--     t_strategy 1（156「哎呦喂」的 默认策略：listStrategies 的自动种子在 156 首次打开策略页时
--       又建了一行同名策略，10-08 那次它还不存在。它没有下游——实测 strategy_id=2 在
--       t_strategy_run 里 0 行；t_strategy_version 里 1 行会成孤儿，那张表没有 user_id、
--       不在本迁移范围内，且全局化后没人再按这个 id 读它。）
--   t_anchor / t_cycle / t_mainline_mark / t_manual_leader / t_node_detect / t_prediction
--   非 @keep 属主 0 行（① 照删不误，见上）。
--
-- 写法（重要，别改回存储过程）：理由见 V26 文件头——Flyway 6.5.7 的 MySQL 解析器按分号切语句、
--   不认复合语句体。所以全程 SET @判存在 → IF() 选真 DDL 或 'SELECT 0' 空操作 → PREPARE/EXECUTE。
-- 顺序固定：删冗余行 → 换键 → 删列。反过来不赌 MySQL 对「索引列被删」的处置（它会连带改坏或报错）。
-- 幂等：每条语句都判过自己的前置条件，中途失败重放时已完成的走空操作。
-- 退路（这一版是单向门，新/旧 jar 不能互换）：V44 之后旧 jar 一定起不来——13 个实体的 SQL 还在
--   写 user_id；要回滚得连 t_flyway_history 的 44 行一起删。发版前在服务器 /tmp 留这 13 张表的
--   mysqldump 与 .prev jar，dump 与 SQL 文件永不进仓库（本仓库是公开的）。
-- -----------------------------------------------------------------------------

-- ① 保留 @keep 属主的行——**13 张表全删，不挑表**。
--    判的是「这一列还在不在」：列没了说明这一步早已做完，重放走空操作而不是撞 Unknown column。
--    为什么不照盘点挑 6 张：canary 彩排实测，t_strategy 在 10-08 之后多出一行 156「哎呦喂」的
--    默认策略（listStrategies 的自动种子在 156 首次打开策略页时建的），① 没清它，② 给 name
--    加全局唯一键时就撞 1062 Duplicate entry 中断——盘点会过期，键不会。
SET @keep = (SELECT MIN(id) FROM t_user WHERE role = 'SUPER_ADMIN');

SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_stock' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_theme_stock WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_theme WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_daily_snapshot' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_theme_daily_snapshot WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_record' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_daily_record WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_node_event WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_leading_stock' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_leading_stock WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_strategy' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_strategy WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_prediction WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_anchor WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_cycle' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_cycle WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mainline_mark' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_mainline_mark WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_manual_leader' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_manual_leader WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_detect' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'DELETE FROM t_node_detect WHERE user_id <> @keep', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- ② 键换代：把 (user_id, X) 收成人人共用的 (X)。
--    旧键一律先删再加——同名新键判存在，重放时不会撞 1061 Duplicate key name。

-- t_daily_record: uk_user_date(user_id, trade_date) -> uk_date(trade_date)
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_record' AND INDEX_NAME = 'uk_user_date');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_daily_record DROP INDEX uk_user_date', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_record' AND INDEX_NAME = 'uk_date');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_daily_record ADD UNIQUE KEY uk_date (trade_date)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_prediction: uk_user_date_kind_name -> uk_date_kind_name；idx_user_kind_date -> idx_kind_date
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND INDEX_NAME = 'uk_user_date_kind_name');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_prediction DROP INDEX uk_user_date_kind_name', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND INDEX_NAME = 'uk_date_kind_name');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_prediction ADD UNIQUE KEY uk_date_kind_name (trade_date, kind, name)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND INDEX_NAME = 'idx_user_kind_date');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_prediction DROP INDEX idx_user_kind_date', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND INDEX_NAME = 'idx_kind_date');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_prediction ADD INDEX idx_kind_date (kind, trade_date)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_mainline_mark: uk_user_date_industry -> uk_date_industry(trade_date, industry)
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mainline_mark' AND INDEX_NAME = 'uk_user_date_industry');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_mainline_mark DROP INDEX uk_user_date_industry', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mainline_mark' AND INDEX_NAME = 'uk_date_industry');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_mainline_mark ADD UNIQUE KEY uk_date_industry (trade_date, industry)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_theme_daily_snapshot: uk_user_date_rank -> uk_date_rank(trade_date, rank)
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_daily_snapshot' AND INDEX_NAME = 'uk_user_date_rank');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme_daily_snapshot DROP INDEX uk_user_date_rank', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_daily_snapshot' AND INDEX_NAME = 'uk_date_rank');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_theme_daily_snapshot ADD UNIQUE KEY uk_date_rank (trade_date, `rank`)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_theme_stock: 只删 idx_user_date。uk_theme_date_code(theme_id, trade_date, code) 本来就不含
--   user_id，全局化后它正好充当并发回填的兜底——代码侧把批插改成 INSERT IGNORE 配合它。
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_stock' AND INDEX_NAME = 'idx_user_date');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme_stock DROP INDEX idx_user_date', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_anchor: uk_user_code_start -> uk_code_start(stock_code, start_date)；idx_user_span -> idx_span
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND INDEX_NAME = 'uk_user_code_start');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_anchor DROP INDEX uk_user_code_start', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND INDEX_NAME = 'uk_code_start');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_anchor ADD UNIQUE KEY uk_code_start (stock_code, start_date)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND INDEX_NAME = 'idx_user_span');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_anchor DROP INDEX idx_user_span', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND INDEX_NAME = 'idx_span');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_anchor ADD INDEX idx_span (start_date, end_date)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_strategy: uk_user_name -> uk_name(name)。全局唯一是有意的：
--   代码侧同时删掉 listStrategies 的「没有就自动建默认策略」种子，否则第二个账号会撞 1062。
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_strategy' AND INDEX_NAME = 'uk_user_name');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_strategy DROP INDEX uk_user_name', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_strategy' AND INDEX_NAME = 'uk_name');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_strategy ADD UNIQUE KEY uk_name (name)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_node_detect: uk_user_date_type -> uk_date_type(trade_date, node_type)
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_detect' AND INDEX_NAME = 'uk_user_date_type');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_node_detect DROP INDEX uk_user_date_type', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_detect' AND INDEX_NAME = 'uk_date_type');
SET @v44_ddl = IF(@v44_c = 0, 'ALTER TABLE t_node_detect ADD UNIQUE KEY uk_date_type (trade_date, node_type)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- t_manual_leader 特殊：它没有 id 列，主键就是 PRIMARY KEY (user_id, trade_date)。
--   换成 PRIMARY KEY (trade_date)——「某天的人工总龙头」本来就只该有一个，实体侧的
--   @TableId 也跟着从 userId 移到 tradeDate，updateById 才终于按日命中而不是按账号全刷。
--   必须在删列之前做：主键列不能直接 DROP COLUMN。
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_manual_leader' AND INDEX_NAME = 'PRIMARY' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_manual_leader DROP PRIMARY KEY, ADD PRIMARY KEY (trade_date)', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- ③ 删列。现网实测 MySQL 8.0.46（≥8.0.29 支持 INSTANT 删列），10 万行的 t_theme_stock 也是秒级。
--    剩下 4 张表的 idx_user_id 是单列普通索引，随列一起消失即可，先显式删掉免得留下空索引。
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_cycle' AND INDEX_NAME = 'idx_user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_cycle DROP INDEX idx_user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme' AND INDEX_NAME = 'idx_user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme DROP INDEX idx_user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_leading_stock' AND INDEX_NAME = 'idx_user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_leading_stock DROP INDEX idx_user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND INDEX_NAME = 'idx_user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_node_event DROP INDEX idx_user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_record' AND INDEX_NAME = 'idx_user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_daily_record DROP INDEX idx_user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_anchor' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_anchor DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_cycle' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_cycle DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_record' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_daily_record DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_leading_stock' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_leading_stock DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mainline_mark' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_mainline_mark DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_manual_leader' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_manual_leader DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_detect' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_node_detect DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_node_event DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_prediction' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_prediction DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_strategy' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_strategy DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_daily_snapshot' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme_daily_snapshot DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;
SET @v44_c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_theme_stock' AND COLUMN_NAME = 'user_id');
SET @v44_ddl = IF(@v44_c > 0, 'ALTER TABLE t_theme_stock DROP COLUMN user_id', 'SELECT 0');
PREPARE v44_stmt FROM @v44_ddl; EXECUTE v44_stmt; DEALLOCATE PREPARE v44_stmt;

-- ④ 自检（只读，留日志）：迁移完成后全库带 user_id 列的表应当只剩 4 张——
--    t_position / t_position_history（台账，有意保留隔离）+ 两张 t_daily_record 备份快照。
--    查 COLUMNS 不查 STATISTICS：后者只列「进了索引」的列，那两张快照表有 user_id 却没给它建索引，
--    用 STATISTICS 会只报 2 张，把发版取证读歪。
SELECT TABLE_NAME, COLUMN_TYPE AS user_id_left FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'user_id'
 GROUP BY TABLE_NAME, COLUMN_TYPE ORDER BY TABLE_NAME;
