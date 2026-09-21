# emotion_platform 项目长期约定

## 数据库变更：一律走 Flyway
- 迁移脚本目录：`emotion-server/src/main/resources/db/migration/`，命名 `V{版本}__{英文下划线描述}.sql`。
- **已应用的版本严禁修改**（`validate-on-migrate` 会对 checksum，改历史会让所有环境启动失败）；新变更写下一个版本号。
- 历史表 `t_flyway_history`；`clean-disabled: true`；`baseline-version: 24` 给存量库打基线（不重放 V1–V24，那批有 DELETE+INSERT 种子收敛，会冲掉手工改过的权重）。
- `schema.sql` 已被切成 V1–V24，仅作历史参考，**不再往里加内容**。
- 依赖 `flyway-core` + `flyway-mysql` 必须同版本（Flyway 10 起 MySQL 支持拆成了独立模块），当前 8.5.13。

## 定时任务：进程内 Quartz，排班在库里
- 三层：`t_scheduler_job`（人维护：bean/cron/启用/错过策略）→ `QRTZ_*`（Quartz 自己的账，**不要手改**）→ `t_scheduler_run`（执行留痕）。
- 加任务 = 写实现 `ManagedTask` 的 Spring bean + 往 `t_scheduler_job` 插一行（建议写成下一个 Flyway 版本而不是手工 INSERT），重启或 `POST /api/scheduler/reload` 生效。
- 不要用抛异常表达「这次不用做」——那是正常分支，返回 `TaskResult.skip(原因)`。
- 不要配 `spring.quartz.startup-delay`：ApplicationRunner 会先于 Quartz 起来导致装载失败。
- 任务在后端进程内跑，**到点时后端必须开着**；没跑先看 `t_scheduler_run` 有没有那天的行。

## 行情取数的一个事实
`MarketDataService.snapshot(date, true)` 负责写 `t_market_stock / t_premium_tier / t_zt_perf / t_index_close`，
但 **`t_market_daily` 的 upsert 原本挂在 `MarketController` 里**——任何不走 HTTP 的调用（定时任务这类）必须自己补这一步，
否则拉到了数据但全局客观九数那张表是空的。

## 前端深色主题：统一在 App.vue 全局段压 Element 变量
- 全站是深色页（body #0f1419 / 卡片 #1a2332 / hover #22303f / 边框 #2d3748），但 Element Plus 走默认浅色，
  逐页 scoped 覆盖会漏——**凡是 teleport 到 body 的浮层（date-picker 面板、下拉）scoped 样式根本够不到**，只能写在 App.vue 的非 scoped `<style>` 里。
- 正确姿势是整块换语义色变量（`--el-text-color-* / --el-border-color-* / --el-fill-color-* / --el-bg-color-overlay`），
  而不是逐个节点打补丁；硬编码色的角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）要单独压。
- 交易日日期面板的格子语义 class：`day-non-trading`（划掉=休市/周末）、`day-future`（虚线圈=未到），
  由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**（没有全局注入点）。

## 本机构建验证
- Maven：`C:\Program Files\apache-maven-3.9.4\bin\mvn.cmd`；JDK `jdk1.8.0_251`。
- `cd emotion-server && mvn -DskipTests compile && mvn test`（既有 407 个用例全离线不连库）。
- 本机 Bash 的 PATH 是坏的（`ls/head/find/grep` 都没有，只有内置命令）、PowerShell 输出不回显：要跑命令就用绝对路径的 node/python，详见当日日志「本机环境」一节。
- 前端校验**别用** `node -e "import('vite').build()"`（会挂住十几分钟无输出）；用 `@vue/compiler-sfc` 的 `parse + compileTemplate` 逐个编译 .vue，秒级出结果。
- Edit 工具同一条消息里对**同一个文件**连发两次会互相覆盖，改多处的同一文件必须串行改完再 grep 复核。
