-- Flyway migration V45: WaveRider 候选票 T+1 表现回补的排班
-- -----------------------------------------------------------------------------
-- 干什么：每交易日 20:30 把最近 3 个交易日候选票在 D+1 的实际表现补进 t_candidate_t1
--   （跳空 / 次日涨幅 / 是否晋级 / 盘中最高）。策略选股页的「复盘池」与近 30 日聚合复盘
--   全靠这张表，它是权重回归的唯一燃料。
--
-- 为什么要新起一个任务，而不是继续挂在 15:30 的 waverider_daily_scan 里：
--   那趟从来没补上过一行。t_scheduler_run 连续多日都是「补写 X 的 T+1 共 0 行」，
--   库里所有 t_candidate_t1 行都来自晚间的手工运行。原因不在漏跑，在上游发布时点——
--   补写要 open(D+1)，走腾讯个股日线，而 DailyBarService 的配置注释自己就写着
--   「腾讯个股日线当天滞后」；15:30 当日那根还没发布，upsertT1 拿到 openT1 为 null 就静默返回。
--   实测当日日线要 17:48 之后才落得进 t_daily_bar。
--
-- 为什么是 20:30：让开 19:00 的 daily_market_pull 与 19:30 的 review_objective_entry——
--   三个任务打的是同一批上游，挤在一起只会互相拖慢；又还在同一个晚上，人做复盘之前数就齐了。
--
-- 为什么一次扫 3 个交易日：backfillT1 是幂等 upsert，已结算的窗口走 t_daily_bar 库缓存
--   （volatile-days=3 之外不回源），多扫两天几乎不打上游，却换来「漏一个晚上第二天自己补齐」。
--   V36 的注释把这层自愈能力算在 15:30 那步头上，而它一次也没兑现过；这次由扫 3 天真正兜住。
--
-- misfire 用 DO_NOTHING：补跑一次拿到的是同一批已结算的日线（幂等，无害），
--   而下一晚的 3 日扫描本来就会覆盖漏掉的那天。
-- INSERT IGNORE：重放迁移不覆盖运维手工改过的 cron / 停用状态。
--
-- 顺带修 waverider_daily_scan 的 description：V36 已应用，改它的文件会改 checksum、
--   Flyway validate 直接红，所以那句「先补写前一日的候选T+1表现」只能在这里更正。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO t_scheduler_job
    (job_name, job_group, bean_name, cron_expr, misfire_policy, enabled, description)
VALUES
    ('waverider_t1_backfill', 'DEFAULT', 'waveRiderT1BackfillTask', '0 30 20 ? * MON-FRI', 'DO_NOTHING', 1,
     '每交易日20:30回补最近3个交易日候选票的T+1表现(跳空/次日涨幅/晋级)：15:30那趟取不到当日日线，腾讯个股日线当天滞后');

UPDATE t_scheduler_job
   SET description = '每交易日15:30跑WaveRider选股，产出当日候选池(consecutive>=min_board_count)。T+1表现的回补已移到 waverider_t1_backfill(20:30)——15:30取不到当日日线，补不上'
 WHERE job_name = 'waverider_daily_scan';
