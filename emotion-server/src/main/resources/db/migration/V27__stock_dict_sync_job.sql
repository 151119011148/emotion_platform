-- 股票字典（t_stock）每周同步一次的排班
--
-- t_stock 是选股下拉（高位生态「新增阵眼」、持仓台账）的唯一数据源：远程搜索只查它。
-- 新建的库在第一次同步之前这张表是空的，表现是"输股票名称搜不出来"——不是搜索坏了，是没数据。
-- 名字变化很慢（新股上市、ST 摘帽改名），一周一次足够；执行体 stockDictSyncTask 拉的是
-- EastmoneyClient.allStocks() 全市场名单，upsert 进库，不做清空重灌。
--
-- 排周一 08:30：赶在开盘前把上周新上市的票补进来；错过就补跑一次（字典空着会一直碍事）。
-- INSERT IGNORE：重放迁移不会覆盖运维手工改过的 cron。
INSERT IGNORE INTO t_scheduler_job
    (job_name, job_group, bean_name, cron_expr, misfire_policy, enabled, description)
VALUES
    ('stock_dict_sync', 'DEFAULT', 'stockDictSyncTask', '0 30 8 ? * MON', 'FIRE_ONCE_NOW', 1,
     '每周一08:30同步A股代码名称字典(t_stock)，供阵眼登记与持仓台账的选股搜索使用');
