# emotion_platform 前端（深色主题 / 表格 / 页面约定）

`MEMORY.md` 的伴生文件：那边只留索引，**动前端样式或 el-table 前先读本文件**。
工程：`emotion-web`（Vue 3 + Vite + Element Plus + Pinia），全站深色。

## 深色主题
- 全站深色（body `#0f1419` / 卡片 `#1a2332` / hover `#22303f` / 边框 `#2d3748`）。**teleport 到 body 的浮层 scoped 够不到** → 写 `App.vue` 非 scoped `<style>`。
- 正解＝整块换语义变量（`--el-text-color-*`/`--el-border-color-*`/`--el-fill-color-*`/`--el-bg-color-overlay`/`--el-dialog-bg-color`）+ 覆盖 `.el-select__popper/.el-dropdown__popper/.el-popover`；硬编码角落（popper 箭头 `#303133`、快捷栏 active `#e6f1fe`）单独压。
- 交易日格子语义 class `day-non-trading`（划掉=休市）/ `day-future`（虚线圈=未到）由 `utils/tradingCalendar.js` 的 `cellClass()` 生成，**每个 el-date-picker 都要显式传 `:cell-class-name="cellClass"`**。
- `el-table` 有两处不走表格自有变量：①**展开行** `--el-table-expanded-cell-bg-color`（已设 `#16202e`，并压 `:hover` 否则闪烁）；②**`stripe` 斑马纹**走 `--el-fill-color-lighter`（默认白，已在 `App.vue` `.el-table` 补 `#1f2b3b`）。实害＝浅色文字掉进白底直接不可读。
- **全站输入框 / 告警条底色仍纯白**（`--el-fill-color-blank`、`--el-color-*-light-9` 未覆盖；`/review`、`/mainline`、`/waverider` 一致）→ 既有全站状态，改它＝全站换观感，需单独决策。

## el-table 列宽：弹性列会按 min-width 比例"吃撑"（2026-09-30 实测）
- 源码 `element-plus/es/components/table/src/table-layout.mjs`：没写 `width` 只写 `min-width` 的列＝flexColumn，
  `realWidth = minWidth + floor(minWidth × (bodyWidth − bodyMinWidth) / ΣminWidth)`，第一个弹性列再吃取整差额。
  **容器越宽，弹性列被放得越大**——宽屏下 min 150/240 的两列实测被撑到 340/544px，而内容只占 130~200px。
- `bodyWidth = table 根元素的 clientWidth` → **修法＝给表格包一层 `max-width` 的 div**（`WaveRiderView.vue` 的 `.tbl{max-width:1400px}`），
  富余留在表右侧，列不再膨胀；窗口窄于该值时自动 100%。**别靠调 min-width 解决**，比例分配下改小 min 只是把它压到窄屏才生效。
- 最小可容宽度＝Σ(固定列 width) + Σ(弹性列 min-width)，小于它才出横向滚动条。

## 登录 / 账号（2026-09-30 起）
- 侧栏「账号管理」`v-if="userStore.isSuperAdmin"`；`role` 存 localStorage，进外壳时 `loadMe()` 刷一次。
- 401/403 拦截器清 `token/username/nickname/role` 再跳 `/login`（被互踢也走这条）。
- `skipAuthRedirect`：退出登录自己发的请求专用，否则 401 会把一次正常退出说成掉线。
