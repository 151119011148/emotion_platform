<template>
  <router-view />
</template>

<style>
body {
  margin: 0;
  padding: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #0f1419;
  color: #e1e8ed;
}

/* ===== Element Plus 表格全站深色化 =====
   el-table 默认是浅色主题，逐页覆盖会漏（历史上已反复出现白底表格）。
   这里在组件库样式之后统一重设表格 CSS 变量，所有页面一次生效；
   变量放在 .el-table 本体上（Element 内部全部 var() 引用），透明背景让卡片底色透上来。 */
.el-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #16202e;
  --el-table-border-color: #2d3748;
  --el-table-text-color: #cbd5e0;
  --el-table-header-text-color: #8899a6;
  --el-table-row-hover-bg-color: #22303f;
  --el-table-current-row-bg-color: #22303f;
  --el-table-fixed-box-shadow: -10px 0 12px -8px rgba(0, 0, 0, .45);
  color: var(--el-table-text-color);
  background-color: transparent;
}
/* 内部容器与滚动区：变量不覆盖 background 简写的地方，逐节点压透明 */
.el-table .el-table__inner-wrapper,
.el-table .el-table__header-wrapper,
.el-table .el-table__body-wrapper,
.el-table .el-table__footer-wrapper,
.el-table .el-scrollbar__wrap,
.el-table .el-table__empty-block {
  background-color: transparent;
}
/* 表格上下边框线（::before/::after）默认取固定浅色，单独压 */
.el-table::before,
.el-table--border::after {
  background-color: var(--el-table-border-color);
}
/* 空数据文案颜色 */
.el-table .el-table__empty-text {
  color: #6b7c8c;
}
/* 排序箭头跟随表头文字色 */
.el-table .caret-wrapper .sort-caret.ascending {
  border-bottom-color: var(--el-table-header-text-color);
}
.el-table .caret-wrapper .sort-caret.descending {
  border-top-color: var(--el-table-header-text-color);
}
/* 固定列是 sticky 定位，背景不能 transparent（否则横向滚动时下层内容透过来），给卡片实色 */
.el-table .el-table-fixed-column--left,
.el-table .el-table-fixed-column--right {
  background: #1a2332;
}
.el-table__body tr:hover > td.el-table-fixed-column--left,
.el-table__body tr:hover > td.el-table-fixed-column--right,
.el-table__body tr.current-row > td.el-table-fixed-column--left,
.el-table__body tr.current-row > td.el-table-fixed-column--right {
  background: #22303f;
}

/* ===== el-collapse 折叠面板全站深色化 =====
   面板 wrap 默认白底，天梯/首板页把 el-table 放进折叠项后，透明表格会透出这块白底。 */
.el-collapse {
  border-top-color: #2d3748;
}
.el-collapse-item__header,
.el-collapse-item__wrap,
.el-collapse-item__content {
  background-color: transparent;
}
.el-collapse-item__header {
  color: #e1e8ed;
  border-bottom-color: #2d3748;
}
.el-collapse-item__wrap {
  border-bottom-color: #2d3748;
}

/* ===== el-card 全站深色化（目前仅打分配置页使用，卡片是表格的承载容器，白卡会让透明表格不可读）===== */
.el-card {
  --el-card-bg-color: #1a2332;
  --el-card-border-color: #2d3748;
  color: #cbd5e0;
}
</style>
