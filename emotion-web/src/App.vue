<template>
  <router-view />
</template>

<style>
/* ===== 节点类型语义色（破局节点 V34）=====
   类型轴的展示色，NodeView 与后续 NodeSuggestPanel 共用。
   放 :root 而不是页面 scoped：scoped 里的变量别的组件读不到，
   而这些色不止一处要用，硬编码在两处迟早会走轮。 */
:root {
  --node-sb: #a78bfa;
  --node-nxt: #34d399;
  --node-phase: #60a5fa;
  --node-none: #8899a6;
}

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
  /* 展开行（type="expand"）默认取 --el-fill-color-blank = 纯白，卡片内会突兀地白一块；
     这里压成比卡片（#1a2332）更深一档的 #16202e，与表头同色，展开区看着像凹进去的一层 */
  --el-table-expanded-cell-bg-color: #16202e;
  --el-table-fixed-box-shadow: -10px 0 12px -8px rgba(0, 0, 0, .45);
  color: var(--el-table-text-color);
  background-color: transparent;
}
/* 展开单元格：Element 自带 `.el-table__expanded-cell:hover{background-color:transparent!important}`，
   鼠标划过时会透出下层卡片色、和未悬停状态不一致，这里用同源色压回去保持恒定 */
.el-table .el-table__expanded-cell,
.el-table .el-table__expanded-cell:hover {
  background-color: var(--el-table-expanded-cell-bg-color) !important;
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

/* ===== el-dialog 全站深色化 =====
   弹窗 teleport 到 body，页面 scoped 样式够不到，之前只有节点页靠 .dark-node-dialog 单独压，
   其它页面（高位生态的阵眼弹窗等）一开就是白板。这里按同一套色值统一处理，类选择器保留也不冲突。
   dialog 面板背景走 --el-dialog-bg-color（默认 var(--el-bg-color) = 白），整体换变量 + 补关键节点。 */
.el-dialog {
  --el-dialog-bg-color: #1a2332;
  --el-dialog-border-radius: 12px;
  --el-text-color-primary: #e1e8ed;
  --el-text-color-regular: #cbd5e0;
  --el-text-color-secondary: #8899a6;
  --el-text-color-placeholder: #5c6e84;
  --el-border-color: #3a4d63;
  --el-border-color-light: #2d3748;
  --el-border-color-lighter: #263140;
  --el-fill-color: #22303f;
  --el-fill-color-light: #202c3b;
  --el-fill-color-lighter: #1c2634;
  --el-bg-color: #1a2332;
  --el-bg-color-overlay: #1a2332;
  background: #1a2332;
  border: 1px solid #2a3a52;
  color: #cbd5e0;
}
.el-dialog__title { color: #e1e8ed; }
.el-dialog__body { color: #cbd5e0; }
.el-dialog__headerbtn:hover .el-dialog__close,
.el-dialog__headerbtn:focus .el-dialog__close { color: #ffd166; }
.el-dialog .el-form-item__label { color: #9fb2c6; }
.el-dialog .el-input__wrapper,
.el-dialog .el-select__wrapper,
.el-dialog .el-textarea__inner {
  background-color: #0f1720 !important;
  box-shadow: 0 0 0 1px #2a3a52 inset !important;
}
.el-dialog .el-input__inner,
.el-dialog .el-textarea__inner { color: #e1e8ed !important; }
.el-dialog .el-input__inner::placeholder,
.el-dialog .el-textarea__inner::placeholder { color: #5c6e84; }
.el-dialog .el-radio__label,
.el-dialog .el-checkbox__label { color: #c6d2de; }
.el-dialog .el-dialog__footer { border-top: 1px solid #2a3a52; padding-top: 12px; }

/* ===== 浮层（日期面板 / 下拉 / popover）全站深色化 =====
   浮层是 teleport 到 body 的，页面级 scoped 样式够不到，只能在这里全局压。
   做法跟表格一样：整体换掉 Element 的语义色变量（面板/表头/单元格/底部按钮全是 var() 引用），
   而不是逐个节点打补丁——这样月/年面板、快捷栏一并跟随。
   下拉（el-select 远程搜索、el-dropdown）与 popover 走同一套变量，避免弹窗里再冒白框。 */
.el-picker__popper.el-popper,
.el-select__popper.el-popper,
.el-dropdown__popper.el-popper,
.el-popover.el-popper {
  --el-text-color-primary: #e1e8ed;
  --el-text-color-regular: #cbd5e0;
  --el-text-color-secondary: #8899a6;
  --el-text-color-placeholder: #6b7c8c;
  --el-text-color-disabled: #4a5568;
  --el-border-color: #3a4d63;
  --el-border-color-light: #2d3748;
  --el-border-color-lighter: #263140;
  --el-border-color-extra-light: #22303f;
  --el-disabled-border-color: #2d3748;
  --el-fill-color: #22303f;
  --el-fill-color-light: #202c3b;
  --el-fill-color-lighter: #1c2634;
  --el-bg-color: #1a2332;
  --el-bg-color-overlay: #1a2332;
  --el-box-shadow-light: 0 8px 28px rgba(0, 0, 0, .55);
  background: #1a2332;
  border-color: #2d3748;
  color: #cbd5e0;
}
/* 箭头：默认取 --el-text-color-primary（浅色主题下是深色小方块），这里直接给实色 */
.el-picker__popper.el-popper .el-popper__arrow::before,
.el-select__popper.el-popper .el-popper__arrow::before,
.el-dropdown__popper.el-popper .el-popper__arrow::before,
.el-popover.el-popper .el-popper__arrow::before {
  background: #1a2332;
  border-color: #2d3748;
}
/* 下拉候选项：hover / 选中态用 Element 变量没覆盖到的地方，补一层深底 */
.el-select__popper.el-popper .el-select-dropdown__item,
.el-dropdown__popper.el-popper .el-dropdown-menu__item {
  color: #cbd5e0;
}
.el-select__popper.el-popper .el-select-dropdown__item.is-hovering,
.el-dropdown__popper.el-popper .el-dropdown-menu__item:not(.is-disabled):hover {
  background-color: #22303f;
}
.el-select__popper.el-popper .el-select-dropdown__item.is-selected {
  background-color: #22303f;
  color: #ffd166;
}
/* 快捷栏选中态底色是硬编码的浅蓝，单独压 */
.el-picker-panel__shortcut.active {
  background-color: #22303f;
  color: #ffd166;
}

/* ===== 日历格子：非交易日 vs 还没到的交易日 =====
   class 由 utils/tradingCalendar.js 的 cellClass() 打到 td 上（后端只给到今天为止，
   未来某天是否休市无从判断，故未来工作日一律按「未到」处理）。 */
.el-date-table td.day-non-trading .el-date-table-cell {
  background-color: rgba(255, 255, 255, .035);
}
.el-date-table td.day-non-trading .el-date-table-cell__text {
  color: #56657a;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
  text-decoration-color: #56657a;
}
.el-date-table td.day-future .el-date-table-cell {
  background-color: transparent;
}
.el-date-table td.day-future .el-date-table-cell__text {
  color: #5aa9c9;
  border: 1px dashed rgba(90, 169, 201, .55);
  box-sizing: border-box;
  line-height: 22px;
}
/* 面板底部一行图例，省得猜虚线/删除线什么意思（纯 CSS 注入，不动组件） */
.el-date-picker .el-picker-panel__content::after {
  content: '虚线圈 = 未到的交易日（暂无数据） ·  划掉的 = 休市 / 周末';
  display: block;
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.5;
  color: #6b7c8c;
  text-align: center;
}
</style>
