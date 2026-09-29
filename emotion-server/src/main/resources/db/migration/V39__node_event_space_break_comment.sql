-- Flyway migration V38: 把 V34 那五列的注释改到真正落地的语义上（只改注释，不动类型/长度/NULL/位置）
-- -----------------------------------------------------------------------------
-- 为什么要有这一支：V34 建列时设想的是另一件事——「老空间票断板之后次日自己修不修得回来」，
-- 所以写的是 break_stock_code=「破了板的那只空间板本身、它是锚」、SPACE_BREAK=「破局日(观察日,0候选)」、
-- repair_status=「破局次日它自己修复的判定」。
-- 真正做下来（2026-09 这一轮，口径由他逐条定）主体是反的：认的是新票够不够得着那堵墙——
-- 试探日某只票首次追平破壁线（它就是这一行的节点票，不是"锚"、恰恰是要看的那一只），
-- 成不成只看它次日续没续板。老票断板只是这条线被钉起来的原因，不是这一行的主体。
-- 注释留着老语义，下一个人照着它写代码就会写歪，所以就地纠正。
--
-- 只改注释：MODIFY COLUMN 原样重复列类型、DEFAULT 与位置，一个字节的数据都不动，
-- 存量行（含 6-7 月那批只有名义天梯的点，本轮不补录）读出来跟改之前完全一样。
--
-- 写法沿用 V34：SET @var 查 information_schema 判存在 → IF() 选真 DDL 或 'SELECT 0' → PREPARE/EXECUTE。
-- 禁用 CREATE PROCEDURE / BEGIN...END——Flyway 6.5.7 按分号切语句，复合体会被切碎（ERROR 1064）。
-- 幂等：新库回放（V34 刚建完就被这里改成新注释）与存量库重复执行都不报错；列不存在时空操作。
-- -----------------------------------------------------------------------------

-- 1) node_type：V34 这份连"周期节点"那三个值和上一轮的接位/补位/转切都没写全，
--    破壁这两个值更是写成了"0 候选"。闭集与 NodeService.checkNodeType 一一对应。
SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'node_type');
SET @v38_ddl = IF(@v38_c = 0, 'SELECT 0',
    'ALTER TABLE t_node_event MODIFY COLUMN node_type VARCHAR(16) DEFAULT NULL COMMENT ''节点类型(类型轴)：START=启动日/DIVERGE=分歧日/SWITCH=切换日(三类统称周期节点),SPLIT_PENDING=接位(老龙断板当天有票接住,方向未定),FILL_SAME=补位(接位票与老龙同属性),SWITCH_CROSS=转切(接位票与老龙异属性),SPACE_BREAK=试探破壁(某票首次追平这条钉住的破壁线,当天观察,成不成看它次日续不续板),SPACE_BREAK_NEXT=破壁成功(那次试探次日续板加高,旧周期那面壁被破掉)；NULL=未识别，UI显示未识别''');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- 2) break_stock_code：是追这条线的那只票，与 node_stock 同一只；不是"破了板的老空间票"，
--    更不是"不进候选池的锚"。
SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_stock_code');
SET @v38_ddl = IF(@v38_c = 0, 'SELECT 0',
    'ALTER TABLE t_node_event MODIFY COLUMN break_stock_code VARCHAR(16) DEFAULT NULL COMMENT ''破壁股代码：试探日首次追平破壁线、成功日续板加高的那一只，与 node_stock 是同一只票（只是这里存裸代码，node_stock 存「名称(代码)」）''');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- 3) break_board：存的是它追的那条线高 L，不是它自己的板高。
--    标签上"破壁 x→y"的 x 就是这一格——写结算日已经抬高的线会出现"破壁 8→6"这种倒挂。
SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_board');
SET @v38_ddl = IF(@v38_c = 0, 'SELECT 0',
    'ALTER TABLE t_node_event MODIFY COLUMN break_board INT DEFAULT NULL COMMENT ''它追的那条破壁线的高度 L（不是它自己的板高：那一格在 node_stock_max_board）。试探日记当天钉着的线，成功日记被追平的那条线——写结算日已经抬高的线会出现「破壁 8→6」这种倒挂''');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- 4) break_form：ZB_BREAK/MILD_BREAK/A_KILL 是断板形态，与他说的盘口形态（一字缩量/换手/烂板）
--    不是一回事；本轮不写值、不渲染，盘口一律按当日明细实时算。列留着别挪用。
SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'break_form');
SET @v38_ddl = IF(@v38_c = 0, 'SELECT 0',
    'ALTER TABLE t_node_event MODIFY COLUMN break_form VARCHAR(16) DEFAULT NULL COMMENT ''断板形态：ZB_BREAK=炸板断板,MILD_BREAK=温和断板,A_KILL=A杀(跌停或跌幅超限)。本轮不写入：它是「断板」的形态，与破壁试探的盘口形态（一字缩量/换手/烂板）不是一回事，别挪用；盘口那些数按当日明细实时算，不入库''');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;

-- 5) repair_status：挂在试探行上的"次日续没续板"，成没成就是这个；成功行这一格留 NULL。
--    值由复算面板算、他点采纳才落库（与高低切同一套指纹闸门），没有任何自动写入。
SET @v38_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_node_event' AND COLUMN_NAME = 'repair_status');
SET @v38_ddl = IF(@v38_c = 0, 'SELECT 0',
    'ALTER TABLE t_node_event MODIFY COLUMN repair_status VARCHAR(16) DEFAULT NULL COMMENT ''试探行的次日续板判定：PENDING=待判定(次一交易日还没盘面明细)/SUCCESS=次日续板(破壁成功,有效)/FAILED=次日没续板(滞涨或掉出名单,失效)；破壁成功行与高低切各行此列恒 NULL''');
PREPARE v38_stmt FROM @v38_ddl; EXECUTE v38_stmt; DEALLOCATE PREPARE v38_stmt;
