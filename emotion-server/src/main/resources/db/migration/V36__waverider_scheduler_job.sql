-- Flyway migration V36: WaveRider 每日选股的排班
-- ⚠️ 版本号说明：PRD 里把这两个迁移预留成 V29 / V30，但落地时必须是 35 / 36。
--    那两个号在历史上被跳过了（库已应用到 V34），回填低号会被 Flyway 判成 out-of-order：
--        Validate failed: Detected resolved migration not applied to database: 29
--    版本号只增不减，这是迁移体系的硬约束，不是笔误。
--
-- -----------------------------------------------------------------------------
-- 排班落在库里而不是代码里（见 SchedulerPlatform 的说明）：改 cron、临时停用、
-- 手动跑一次都不需要重启整机。这里只负责插这一行定义，装载由启动时的 sync() 完成。
--
-- bean_name 必须与容器里的 bean 名逐字一致，否则启动装载时会直接报
-- 「容器里没有叫 xxx 的 bean」——这是好事，bad bean 名在启动那一刻就暴露，
-- 而不是等到 15:30 该跑的时候才发现。
--
-- 时间点为什么是 15:30：收盘 15:00，东财三池数据通常 15:10 后才稳定。
-- 15:30 是个安全起点，但执行体不会盲信这个时间——它自己会检查当日三池是否已入库，
-- 没有就返回 skip 等人工重跑，绝不往库里写一批空候选（那比不写更坏，看起来像"今天没票"）。
--
-- misfire 用 DO_NOTHING：补跑一次 15:30 的扫描，拉到的还是那天的数据（无害但无用），
-- 而次日执行时会先补写前一日缺失的 T+1 表现，漏跑本身不会丢结论。
-- INSERT IGNORE：重放迁移不覆盖运维手工改过的 cron。
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO t_scheduler_job
    (job_name, job_group, bean_name, cron_expr, misfire_policy, enabled, description)
VALUES
    ('waverider_daily_scan', 'DEFAULT', 'waveRiderDailyScanTask', '0 30 15 ? * MON-FRI', 'DO_NOTHING', 1,
     '每交易日15:30跑WaveRider选股：先补写前一日的候选T+1表现，再产出当日候选池(consecutive>=min_board_count)');
