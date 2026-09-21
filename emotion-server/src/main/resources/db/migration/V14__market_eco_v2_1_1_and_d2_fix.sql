-- Flyway migration V14: market eco v2 1 1 and d2 fix
-- -----------------------------------------------------------------------------
-- 由 emotion-server/src/main/resources/schema.sql 第 1369-1503 行原样切出。
-- 该文件历史上是「就地维护」的单体脚本：建表语句里已包含后来补的列，
-- 所以中间版本的 CREATE 语句并非当天的历史原貌，而是当前结构的切片。
-- 新库从 V1 顺序回放即可得到与 schema.sql 完全一致的库；存量库用 baseline 打点跳过。
-- 已经应用过的版本严禁修改——新变更一律写下一个版本号。
-- -----------------------------------------------------------------------------
-- ============ 存量库迁移(2026-09-11 大盘生态打分 v2.1.1)：量能价量配合 + 指数连续函数 + 广度超极端档 ============
-- 引擎 builtinTree()/schema 种子/此处存量迁移必须三处同改；改完跑 ScoringModelSeedParityTest 自证一致。
-- 生效模型只有 five_dim_v2(active=1)，@fid 历史种子不动。INSERT IGNORE 改不了存量子指标/规则行，
-- 所以这里用 UPDATE/DELETE/INSERT 幂等收敛（重放无害，且不会覆盖未来人工改过的其他行）。
SET @fid2 := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim_v2' AND active = 1);
-- 1) 量能子指标：BAND_LADDER → STRATEGY（价量配合系数走 Java）
UPDATE t_scoring_sub
   SET scoring_kind='STRATEGY', source_key='turnover',
       note='成交额/20日均值 基础分 × 价量配合系数(放量涨1.2/平量涨1.1/缩量涨0.9/缩量跌0.6/平量跌0.35/放量跌0.3/放量暴跌0.15；普跌日量能贡献<指数贡献)'
 WHERE model_id=@fid2 AND dim_key='market' AND sub_key='turnover' AND scoring_kind='BAND_LADDER';
-- 2) 大盘三个子指标的 rule 行换版：量能 BAND_LADDER→COMPOUND 展示、广度 4 档→7 档、指数 COMPOUND 换连续锚点
-- 用 DELETE + 普通 INSERT（非 IGNORE）：① DELETE 已保证重放幂等；② 种子一致性测试是纯文本扫描，
-- 带 IGNORE 的规则插入块都会被计入种子，迁移行会被双计，故此处刻意不用 IGNORE（注释里也别写那条锚点原文）。
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='market' AND sub_key IN ('turnover','breadth','index_env');
INSERT INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid2,'market','turnover',1,'COMPOUND',NULL,NULL,100,'放量上涨：量比>=1.1 且三指均值>0 → 基础分×1.2(封顶100)','STRATEGY:基础阶梯90/70/45/25,Java算'),
  (@fid2,'market','turnover',2,'COMPOUND',NULL,NULL,70,'平量下跌：量比0.90~1.10 且三指均值<0 → ×0.35(9/11适用)',''),
  (@fid2,'market','turnover',3,'COMPOUND',NULL,NULL,45,'放量下跌：三指均值<0 且>-1.5% → ×0.3',''),
  (@fid2,'market','turnover',4,'COMPOUND',NULL,NULL,25,'放量暴跌：三指均值<=-1.5% → ×0.15',''),
  (@fid2,'market','breadth',1,'GTE',0.60,NULL,85,'红盘率 >=0.6',''),
  (@fid2,'market','breadth',2,'GTE',0.40,NULL,55,'>=0.4',''),
  (@fid2,'market','breadth',3,'GTE',0.20,NULL,30,'>=0.2',''),
  (@fid2,'market','breadth',4,'GTE',0.13,NULL,10,'>=0.13(涨跌家数比<=7:1)','涨跌家数比>7:1 扣5'),
  (@fid2,'market','breadth',5,'GTE',0.10,NULL,5,'<0.13(涨跌比>7:1)','10-5'),
  (@fid2,'market','breadth',6,'GTE',0.05,NULL,0,'<0.10 超极端','5-5'),
  (@fid2,'market','breadth',7,'ELSE',NULL,NULL,0,'<0.05 崩盘',''),
  (@fid2,'market','index_env',1,'COMPOUND',1.50,NULL,95,'三指均值 +1.5% → 95','连续:score=clamp(50+均值%×30,0,100)'),
  (@fid2,'market','index_env',2,'COMPOUND',1.00,NULL,80,'三指均值 +1% → 80',''),
  (@fid2,'market','index_env',3,'COMPOUND',0.00,NULL,50,'三指均值 0% → 50(中性)',''),
  (@fid2,'market','index_env',4,'COMPOUND',-1.00,NULL,20,'三指均值 -1% → 20(9/11:-0.92→22.4)',''),
  (@fid2,'market','index_env',5,'COMPOUND',-2.00,NULL,0,'三指均值 -2% → 0(封底)','');

-- ============ 存量库迁移(2026-09-11 D2 日内核心修正：amount 取数 + 空间板归属 + 催化剂缺省) ============
-- 背景：①成交额聚集度原标"未评"被按已评权重归一化摊高；②高度聚集度用板数比，掩盖空间板不在主线；
--   ③无题材行时催化剂未评同样被摊高。配套：高度/催化剂升 STRATEGY、引擎加生命周期天花板与龙头错位×0.9。
SET @fid2 := (SELECT id FROM t_scoring_model WHERE model_key = 'five_dim_v2' AND active = 1);

-- 1) t_market_stock 补 amount 列（东财池接口本就返回，旧版本 toRow 丢弃；历史行重拉池子即可回补）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @stock_amount_adds
  FROM (
  SELECT 1 ord_no, 'amount' col_name,
         'DECIMAL(18,2) DEFAULT NULL COMMENT ''当日成交额(元)=东财池接口amount；D2成交额聚集度取数源(池内口径)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_market_stock'
       AND COLUMN_NAME  = need.col_name);
SET @stock_amount_sql = IF(@stock_amount_adds IS NULL,
    'SELECT ''t_market_stock amount 列已齐，本步跳过'' AS stock_amount_migration',
    CONCAT('ALTER TABLE t_market_stock ', @stock_amount_adds));
PREPARE stock_amount_stmt FROM @stock_amount_sql;
EXECUTE stock_amount_stmt;
DEALLOCATE PREPARE stock_amount_stmt;

-- 1b) t_market_stock 补 float_mv 列（东财 ltsz 流通市值，仅涨停池；存量历史行重拉池子即可回补）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @stock_floatmv_adds
  FROM (
  SELECT 1 ord_no, 'float_mv' col_name,
         'DECIMAL(18,2) DEFAULT NULL COMMENT ''流通市值(元)=东财ltsz,仅涨停池；一字断魂刀判据(≤20亿)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_market_stock'
       AND COLUMN_NAME  = need.col_name);
SET @stock_floatmv_sql = IF(@stock_floatmv_adds IS NULL,
    'SELECT ''t_market_stock float_mv 列已齐，本步跳过'' AS stock_floatmv_migration',
    CONCAT('ALTER TABLE t_market_stock ', @stock_floatmv_adds));
PREPARE stock_floatmv_stmt FROM @stock_floatmv_sql;
EXECUTE stock_floatmv_stmt;
DEALLOCATE PREPARE stock_floatmv_stmt;

-- 1c) t_market_stock 补 turnover_rate 列（东财 hs 换手率%，仅涨停池；存量历史行重拉池子即可回补）。
SELECT GROUP_CONCAT(CONCAT('ADD COLUMN ', col_name, ' ', col_ddl) ORDER BY ord_no SEPARATOR ', ')
       INTO @stock_turnover_adds
  FROM (
  SELECT 1 ord_no, 'turnover_rate' col_name,
         'DECIMAL(8,2) DEFAULT NULL COMMENT ''换手率%=东财hs,仅涨停池；一字断魂刀判据(<5%)''' col_ddl
  ) need
 WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 't_market_stock'
       AND COLUMN_NAME  = need.col_name);
SET @stock_turnover_sql = IF(@stock_turnover_adds IS NULL,
    'SELECT ''t_market_stock turnover_rate 列已齐，本步跳过'' AS stock_turnover_migration',
    CONCAT('ALTER TABLE t_market_stock ', @stock_turnover_adds));
PREPARE stock_turnover_stmt FROM @stock_turnover_sql;
EXECUTE stock_turnover_stmt;
DEALLOCATE PREPARE stock_turnover_stmt;

-- 2) 子指标收敛：height_gather/catalyst BAND_LADDER→STRATEGY（存量行 UPDATE，新库种子同值）。
UPDATE t_scoring_sub
   SET scoring_kind='STRATEGY', source_key='height_gather',
       note='STRATEGY:空间板(全市场H)在主线行业→按高度比走阶梯；不在→(主线最高板/H)×50封顶50'
 WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='height_gather' AND scoring_kind='BAND_LADDER';
UPDATE t_scoring_sub
   SET scoring_kind='STRATEGY', source_key='catalyst',
       note='STRATEGY:题材硬度1-5→100/80/60/40/20(t_theme维护)；日内核心存在但无匹配题材行=默认50(人工未评)'
 WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='catalyst' AND scoring_kind='BAND_LADDER';
UPDATE t_scoring_sub
   SET note='涨停股口径(自动=主线涨停股amount/全部涨停股amount)，不接受人工覆盖'
 WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='amount_gather';

-- 3) height_gather/catalyst 规则换版：DELETE + 普通 INSERT（引擎只读 COMPOUND 锚点，实际算法在 Java）。
DELETE FROM t_scoring_rule
 WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key IN ('height_gather','catalyst');
INSERT INTO t_scoring_rule
  (model_id, dim_key, sub_key, rule_no, operator, threshold_low, threshold_high, score, formula, note)
VALUES
  (@fid2,'theme_main','height_gather',1,'COMPOUND',NULL,NULL,95,'空间板在主线行业：高度比>=90%','STRATEGY:空间板归属本板块时走阶梯95/85/70/50/28,Java算'),
  (@fid2,'theme_main','height_gather',2,'COMPOUND',NULL,NULL,50,'空间板不在主线：(主线最高板/H)×50 封顶50','元件案例:2板/4板×50=25,不再按板数比给70'),
  (@fid2,'theme_main','catalyst',1,'COMPOUND',5,NULL,100,'硬度5星(政策/产业级)','STRATEGY:硬度1-5→20/40/60/80/100,Java算'),
  (@fid2,'theme_main','catalyst',2,'COMPOUND',4,NULL,80,'4星',''),
  (@fid2,'theme_main','catalyst',3,'COMPOUND',3,NULL,60,'3星(行业/事件)',''),
  (@fid2,'theme_main','catalyst',4,'COMPOUND',2,NULL,40,'2星',''),
  (@fid2,'theme_main','catalyst',5,'COMPOUND',1,NULL,20,'1星(Pure情绪)',''),
  (@fid2,'theme_main','catalyst',6,'COMPOUND',NULL,NULL,50,'无匹配题材行=默认50(人工未评)','仅日内核心存在时兜底；无涨停池=整维未评');
UPDATE t_scoring_rule
   SET note='自动=主线涨停股amount/全部涨停股amount(涨停股口径,不接受人工覆盖)'
 WHERE model_id=@fid2 AND dim_key='theme_main' AND sub_key='amount_gather' AND rule_no=1
   AND note IN ('人工列 manual_amount_gather_pct',
                '自动=主线涨停股amount/全涨停池amount；manual_amount_gather_pct 可覆盖');

-- 3.1) 旧两市口径人工列停用：D2 成交额聚集度固定涨停股口径后，该列只作历史留痕（MODIFY 幂等重放）。
ALTER TABLE t_daily_record
  MODIFY COLUMN manual_amount_gather_pct DECIMAL(6,2) DEFAULT NULL
  COMMENT '【已停用】旧两市口径(主线板块成交额/两市成交额)；D2成交额聚集度已固定涨停股口径自动计算,本列不再进分';
