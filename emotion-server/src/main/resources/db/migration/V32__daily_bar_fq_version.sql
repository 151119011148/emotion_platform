-- Flyway migration V32: 日 K 缓存补复权版本号（t_daily_bar.fq_version / t_daily_bar_fetch.fq_version）
-- -----------------------------------------------------------------------------
-- 背景：V31 把日 K 落了库，但存的是前复权（qfq）价，而 qfq 不是静态值——
--       一旦这只票分红送股，上游把除权日之前的整段历史价整体重算，库里旧行就从"对"变成"错"。
--       实测 8 只票里 6 只在 3 个月内除过权：贵州茅台 6/1 实际收盘 1309.60，
--       前复权后是 1281.58，差 2.14%。所以 V31 那句"保鲜期 30 天"只是兜底，不够。
--
-- 涨跌幅其实是安全的，别被上面吓到：
--       pct = (今收 - 前收) / 前收，分子分母同属一段 qfq 序列、共享同一个复权因子，
--       因子在比值里抵消——所以"这段里每一天的涨跌幅"不会因为未来除权而改变。
--       真正会错的是两件事：①拿库里的 qfq 价当"当时的真实股价"做绝对价比较；
--       ②把不同时间拉取的两段拼在一起，衔接处两边因子不同，会凭空出现一次假跳变。
--
-- 本迁移就是冲着 ② 来的：上游每段日 K 自带 version（实测 sz000993 为 "18"），
--       它就是这次返回所用复权基准的版本号。把版本号随行存下来，读一段时校验段内
--       版本号是否唯一——出现两种就说明这段是拼出来的，判为未命中、整段重拉。
--       回源时再顺手比对重叠日的收盘价，不一致就是除权了，把这只票的旧行整体作废。
--
-- 写法：沿用 V26/V28 的幂等补列套路（information_schema 判存在 → IF() 选真 DDL 或
--       'SELECT 0' → PREPARE/EXECUTE），全程不用复合语句——Flyway 6.5.7 的 MySQL
--       解析器按分号切语句，CREATE PROCEDURE / BEGIN...END 会被切成碎片报 ERROR 1064。
-- -----------------------------------------------------------------------------

-- 行级版本号：判"这几根是不是同一批复权基准下算出来的"。
SET @v32_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_bar' AND COLUMN_NAME = 'fq_version');
SET @v32_ddl = IF(@v32_c = 0,
    'ALTER TABLE t_daily_bar ADD COLUMN fq_version VARCHAR(16) DEFAULT NULL COMMENT ''上游复权版本号（data.version）；一段内出现两种即说明是拼凑的，须整段重拉'' AFTER low_price',
    'SELECT 0');
PREPARE v32_stmt FROM @v32_ddl; EXECUTE v32_stmt; DEALLOCATE PREPARE v32_stmt;

-- 拉取留痕同步记一份，排查"这只票现在的复权基准是哪一版"时不用去翻行。
SET @v32_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_daily_bar_fetch' AND COLUMN_NAME = 'fq_version');
SET @v32_ddl = IF(@v32_c = 0,
    'ALTER TABLE t_daily_bar_fetch ADD COLUMN fq_version VARCHAR(16) DEFAULT NULL COMMENT ''这次拉取时上游的复权版本号'' AFTER bar_count',
    'SELECT 0');
PREPARE v32_stmt FROM @v32_ddl; EXECUTE v32_stmt; DEALLOCATE PREPARE v32_stmt;
