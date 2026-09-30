-- Flyway migration V43: 账号角色（t_user.role）+ 单点登录令牌版本号（t_user.token_version）
-- -----------------------------------------------------------------------------
-- 背景（两件事，都落在 t_user 上）：
--   ① 角色：建表 V1 时 t_user 只有 id/username/password/nickname/created_at，
--      「谁能加账号」这件事根本没有落点——/api/auth/register 是对外全开的，
--      任何人拿到地址就能给自己开号，页面上有没有入口都一样。
--      于是加 role：USER=普通用户（默认）、SUPER_ADMIN=超级管理员，
--      后者独占「新增/改角色/重置密码/强制下线/删除账号」。
--   ② 单点登录（互踢）：JWT 是无状态的，同一个账号在两台机器上登录会拿到两个
--      都合法的 token，谁也不会掉线。要「后登录的生效、先登录的失效」就得给账号
--      一个版本号：token 里带上签发时的版本，请求时与账号当前版本比对，
--      不等就是「这个号已经不在这台机器上了」。
--      不建在线会话表：只需要一个整数就能表达「哪次登录才是最新的」，
--      会话表反而要在退出/超时/进程重启时收拾一堆脏行。
--
-- 口径（与用户确认）：
--   · 单点登录 = 账号互踢，不是跨系统 SSO。新登录让旧 token 立刻失效，
--     旧浏览器下一个请求收到 401，前端清 token 并弹回登录页。
--   · 存量账号一律 USER，只把 gaofeng 提为 SUPER_ADMIN（库里已知的运维账号）。
--     迁移不 INSERT 新超管：拿不到初始密码，硬塞一个等于开一个谁都知道的后门。
--   · token_version 从 1 起。V43 之前签发的 token 根本没有 tv 声明，
--     一律按「版本号不匹配」处理 → 全员重新登录一次，顺带把 role 领走。
--     这是有意的：不刷新就没法拿到角色，管理页就永远进不去。
--   · 改角色/重置密码会顺带 +1 版本号：被改的人必须重新登录才能领到新 role，
--     否则旧 token 里的 role 声明会和库里对不上，权限判断出现两个真相。
--
-- 写法沿用 V28/V38：information_schema 判存在 → IF() 选真 DDL 或 'SELECT 0'
--       → PREPARE/EXECUTE。全程不用复合语句——Flyway 6.5.7 的 MySQL 解析器
--       按分号切语句，CREATE PROCEDURE / BEGIN...END 会被切碎报 ERROR 1064。
-- -----------------------------------------------------------------------------

-- role 落在 nickname 之后：读表时「登录名 昵称 角色」连成一组看最顺。
SET @v43_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'role');
SET @v43_ddl = IF(@v43_c = 0,
    'ALTER TABLE t_user ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER=普通用户；SUPER_ADMIN=超级管理员（独占增删账号、改角色、重置密码、强制下线）'' AFTER nickname',
    'SELECT 0');
PREPARE v43_stmt FROM @v43_ddl; EXECUTE v43_stmt; DEALLOCATE PREPARE v43_stmt;

-- token_version 紧跟 role。NOT NULL DEFAULT 1：老账号也必须有值，
-- NULL 会让「版本号比对」退化成三态判断（空 vs 空 vs 不匹配），多一处分支就多一处漏判。
SET @v43_c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'token_version');
SET @v43_ddl = IF(@v43_c = 0,
    'ALTER TABLE t_user ADD COLUMN token_version INT NOT NULL DEFAULT 1 COMMENT ''令牌版本号：每次登录 +1，旧 token 立即失效（单点登录互踢）；改角色/重置密码也 +1'' AFTER `role`',
    'SELECT 0');
PREPARE v43_stmt FROM @v43_ddl; EXECUTE v43_stmt; DEALLOCATE PREPARE v43_stmt;

-- 存量值归一：列已存在但值非法的（历史脏数据、手工改过），一律回落 USER，
-- 免得「是不是超管」在库里和代码里两个判据给出不同答案。
UPDATE t_user SET `role` = 'USER' WHERE `role` IS NULL OR `role` NOT IN ('USER', 'SUPER_ADMIN');

-- gaofeng 提为超级管理员。没有它就没有任何账号能进管理页，
-- 而注册入口已经收拢——等于把自己锁在门外，只能再写一条迁移救回来。
UPDATE t_user SET `role` = 'SUPER_ADMIN' WHERE username = 'gaofeng';
