<template>
  <router-view />
  <!-- 个股分时·竞价：全站只挂这一份，任何页面点到个股都由 utils/stockTape 把它打开。
       它必须在全局样式层之后、且只此一处——十四个页面各挂一份就会有十四份图表实例和十四种空态长相。 -->
  <StockTapeDialog />
</template>

<script setup>
import StockTapeDialog from './components/StockTapeDialog.vue'
</script>

<style>
/* ===== 节点类型语义色（破局节点 V34 ＋ 高低切分流）=====
   类型轴的展示色，NodeView 与后续 NodeSuggestPanel 共用。
   放 :root 而不是页面 scoped：scoped 里的变量别的组件读不到，
   而这些色不止一处要用，硬编码在两处迟早会走轮。 */
:root {
  --node-sb: #a78bfa;
  --node-nxt: #34d399;
  --node-phase: #60a5fa;
  --node-none: #8899a6;
  /* 高低切一族：接位是"方向未定"的黄，补位/转切是分流后的两个去向，
     两者判据相同、只差题材同不同属性，所以给同明度不同色相的一对。 */
  --node-pending: #fbbf24;
  --node-fill: #22d3ee;
  --node-cross: #f472b6;
}

/* ===== 节点类型标签 =====
   放在全局是必须的：NodeView 的 scoped 副本 NodeSuggestPanel 读不到，
   而"建议是哪个类型"必须在采纳之前就看得见——指纹里带着它，
   看不见就点采纳等于点了个假的采纳。所以整份配色只留这一处。 */
.ntag {
  display: inline-flex; align-items: center; align-self: center; padding: 1px 8px; border-radius: 4px;
  font-size: 12px; line-height: 1.7; white-space: nowrap; border: 1px solid transparent;
}
.nt-SPACE_BREAK { color: var(--node-sb, #a78bfa); background: rgba(167, 139, 250, .14); border-color: rgba(167, 139, 250, .35); }
.nt-SPACE_BREAK_NEXT { color: var(--node-nxt, #34d399); background: rgba(52, 211, 153, .14); border-color: rgba(52, 211, 153, .35); }
.nt-START, .nt-SWITCH, .nt-DIVERGE { color: var(--node-phase, #60a5fa); background: rgba(96, 165, 250, .14); border-color: rgba(96, 165, 250, .35); }
/* 高低切一族：接位是还没定性的黄；补位/转切是同一判据分流出来的两个去向，同明度异色相。 */
.nt-SPLIT_PENDING { color: var(--node-pending, #fbbf24); background: rgba(251, 191, 36, .14); border-color: rgba(251, 191, 36, .35); }
.nt-FILL_SAME { color: var(--node-fill, #22d3ee); background: rgba(34, 211, 238, .14); border-color: rgba(34, 211, 238, .35); }
.nt-SWITCH_CROSS { color: var(--node-cross, #f472b6); background: rgba(244, 114, 182, .14); border-color: rgba(244, 114, 182, .35); }
.nt-none { color: var(--node-none, #8899a6); background: rgba(136, 153, 166, .12); border-color: rgba(136, 153, 166, .28); }

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
  /* 斑马纹（el-table 加 stripe）走的是 --el-fill-color-lighter，默认 #fafafa —— 纯白。
     只压 --el-table-* 系列挡不住它：那条规则写的是 background:var(--el-fill-color-lighter)，
     压根不经过表格自己的变量（和「展开行」是同一个坑，都在借浅色主题的兜底变量）。
     全库里这个变量只有两处引用：.el-table--striped 的斑马纹行、以及 .el-upload--picture-card，
     所以限定在 .el-table 作用域内改不会外溢。
     取值约束：要在卡片底色 #1a2332 与 hover #22303f 之间找一档，色板只剩 ~7 级空间，
     所以只能取偏浅的一档——斑马纹看得见，鼠标划过的行（hover 会覆盖斑马纹，实测有效）
     仍比它更亮一档。 */
  --el-fill-color-lighter: #1f2b3b;
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

/* ===== 日历格子：两种不可选日，两种标识 =====
   class 由 utils/tradingCalendar.js 的 cellClass() 打到 td 上。
   休市=实心灰球＋划掉（周末或官方节假日，这一天永远不会有数据）；
   未到=虚线空心球（今天之后的工作日，会有，只是还没到）。
   EP 给 disabled 格子的整片浅灰底会把两种标记糊成一片，先清掉。 */
.el-date-table td.day-non-trading .el-date-table-cell,
.el-date-table td.day-future .el-date-table-cell {
  background-color: transparent;
}
.el-date-table td.day-non-trading .el-date-table-cell__text {
  color: #5f6e80;
  background-color: #2b3648;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
  text-decoration-color: rgba(203, 213, 224, .5);
}
.el-date-table td.day-future .el-date-table-cell__text {
  color: #6f8ba3;
  border: 1px dashed rgba(111, 139, 163, .7);
  box-sizing: border-box;
  line-height: 22px;
}
/* 跨月溢出的那几行 EP 已压暗表示「不在这个月」，再套标识会让面板凭空多出十来个格子 */
.el-date-table td.prev-month.day-non-trading .el-date-table-cell__text,
.el-date-table td.next-month.day-non-trading .el-date-table-cell__text {
  background-color: transparent;
  text-decoration: none;
}
.el-date-table td.prev-month.day-future .el-date-table-cell__text,
.el-date-table td.next-month.day-future .el-date-table-cell__text {
  border: none;
  line-height: 24px;
}
/* 面板底部一行图例，省得猜实心/虚线什么意思（纯 CSS 注入，不动组件） */
.el-date-picker .el-picker-panel__content::after {
  content: '实心划掉 = 休市（周末/节假日） 虚线圈 = 未到（今日之后） 两种都点不了';
  display: block;
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.5;
  color: #6b7c8c;
  text-align: center;
}

/* ===== 手机窄屏（≤768px）：只抬触控命中区 =====
   这一条是量出来的，不是猜的：把 dist 挂进同源 iframe、iframe 宽度给成 390/320
   （真实视口，matchMedia 与布局都按那档走），用生产现读的 JSON 喂 D1 仪表盘与 D9 策略选股，
   两页横向零溢出、图表跟着视口重画——布局本身扛得住手机。
   剩下唯一手机独有的问题是命中区：Element 的 small 档控件高 24px（日期输入里面那根 22px），
   鼠标点着正合适，手指在地铁里点就是误触。实测 D9 页面上 21 个可点控件里 9 个不足 32px。
   抬到 34px 是折中：Apple HIG 的 44pt 会把页头那排按钮顶变形，34 已够拇指第一指腹。
   --el-component-size-small 管输入框/日期/选择器那一族；按钮不吃这个变量，得单独给 min-height。
   只写在 @media 里，宽屏一行都不生效（1440 已回归：侧边栏回来、两张表 17 列照旧、卡片不出）。 */
@media (max-width: 768px) {
  :root {
    --el-component-size-small: 34px;
  }

  .el-button--small,
  .el-select--small {
    min-height: 34px;
  }

  /* 弹窗与提问框：宽度是写死的（D10「编辑卖出」420px、ElMessageBox 默认 420px），
     390 视口上实测右边缘画到 420——整块顶出屏幕，右上角的关闭按钮在 419 那个位置，手指够不着。
     改 max-width 而不是改 width：Element 把宽度挂在 .el-dialog 的行内 CSS 变量上
     （现读 --el-dialog-width: 420px），行内变量赢过样式表里的 width，但赢不过 max-width，
     所以这一条不用 !important，也不会打到别的属性。
     用百分比而不是 100vw：100vw **含滚动条宽**，390 视口上 dialog 算出 366、居中后右边缘 378，
     而可视区只有 375（余 15px 给滚动条）——右上角关闭按钮会被压在滚动条下面 3px。
     .el-dialog 的包含块 .el-overlay-dialog 是那个 overflow:auto 的容器，100% 已扣掉滚动条，
     实测 351 宽、左右各 12，关闭按钮整体落在 363 以内。 */
  .el-dialog,
  .el-message-box {
    max-width: calc(100% - 24px);
  }
}

/* ===== 手机档卡片流：窄屏表格的替身（全站共用一份） =====
   这些类只在 `useNarrow()` 为真的那一路渲染（模板分叉），宽屏永远不会走到，
   所以放在全局而不是逐页 scoped：D9 策略选股先写了一份，D4 日内核心、D10 持仓与台账
   接着要同一套长相——三页各抄一遍迟早会走轮（改一页忘两页）。
   类名以 `.m-` / `.mc-` 起头，全库只在这里定义，不与任何页面 scoped 类重名。
   卡片底色 #16202e 沿用全站「嵌套块比卡片深一档」的既有档位（.blk 是 #1a2332，
   表格展开行也是这一档），不是随手挑的数。
   断点必须与 utils/narrowScreen.js 的 NARROW_MAX_PX 同数：模板分叉读那边、
   媒体查询读这里，两个数不一样就会出现「卡片视图配桌面内边距」的半套状态。 */
.m-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.m-card {
  background: #16202e;
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 10px 12px;
}
.mc-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.mc-rank {
  flex: none;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  font-weight: 700;
  color: #0b0f14;
  background: #cbd5e0;
}
.mc-name {
  flex: 1;
  min-width: 0;
}
.mc-name .nm-text {
  display: block;
  font-size: 14.5px;
  font-weight: 600;
}
.mc-code {
  display: block;
  font-style: normal;
  font-size: 11px;
  color: #6b7c8c;
  letter-spacing: .02em;
}
.mc-board {
  flex: none;
  font-size: 12px;
  color: #fbbf24;
  background: rgba(251, 191, 36, .12);
  border: 1px solid rgba(251, 191, 36, .3);
  border-radius: 4px;
  padding: 1px 6px;
}
.mc-score {
  flex: none;
  font-size: 16px;
  font-weight: 700;
  color: #e1e8ed;
  min-width: 34px;
  text-align: right;
}
.mc-wait {
  flex: none;
  font-size: 12px;
  color: #6b7c8c;
  padding: 2px 6px;
  border: 1px dashed #2d3748;
  border-radius: 4px;
}
.mc-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 7px;
}
.mc-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  margin-top: 9px;
  padding-top: 9px;
  border-top: 1px solid #22303f;
}
/* 三列给「数字多、字短」的那类（持仓的成本/现价/浮动）；两列会撑成六行，卡片就散了 */
.mc-grid.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.mc-cell {
  min-width: 0;
}
/* 跨满整行：次日决策这种一句话的字段，塞进半列会折成三行 */
.mc-cell.wide {
  grid-column: 1 / -1;
}
.mc-cell i {
  display: block;
  font-style: normal;
  font-size: 11px;
  color: #6b7c8c;
  margin-bottom: 2px;
}
.mc-cell b {
  font-size: 13.5px;
  font-weight: 600;
  color: #cbd5e0;
}
.mc-cell b.mc-strong {
  font-weight: 700;
  color: #e1e8ed;
}
/* 「这一格确实没有数」的灰：与 .mc-strong 相反。写在 cell 之外，
   因为 D10 的卡里 .mc-score 也要用它（空持仓的空浮动）。 */
.mc-cell .mut,
.m-card .mut {
  color: #6b7c8c;
}
.mc-queue {
  font-style: normal;
  font-size: 11px;
  color: #fbbf24;
  background: rgba(251, 191, 36, .12);
  border-radius: 3px;
  padding: 0 4px;
  margin-left: 4px;
}
.mc-alerts {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}
.mc-foot {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: #6b7c8c;
}
/* 行内编辑的格子：标签在上、控件占满整格宽。控件本身是 Element 的 small 档，
   高度由上面那条窄屏命中区规则抬到 34px，这里只管摆位。 */
.mc-edit {
  min-width: 0;
}
.mc-edit > i {
  display: block;
  font-style: normal;
  font-size: 11px;
  color: #6b7c8c;
  margin-bottom: 3px;
}
.mc-edit .el-input-number,
.mc-edit .el-select,
.mc-edit .el-input {
  width: 100%;
}
/* 「一句话说清一只票」的轻行（D4 中军、D10 卖出流水那种字段少的表），
   不值得给它一套 grid，一行三段更贴近原来表格的读法 */
.mc-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid #22303f;
  font-size: 13px;
}
.mc-line:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.mc-line .nm {
  flex: 1;
  min-width: 0;
  font-weight: 600;
}
.mc-line .cd {
  font-style: normal;
  font-size: 11px;
  font-weight: 400;
  color: #6b7c8c;
  margin-left: 6px;
}
.mc-line .n {
  flex: none;
  font-variant-numeric: tabular-nums;
}
/* 卡片底部那排操作（D4 的「升级到主线」、D10 的「编辑卖出」）：
   放在卡里而不是浮在右下角，是因为窄屏命中区已抬到 34px，压住数字会看不清那一格 */
.mc-act {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px solid #22303f;
}

/* ===== 个股 → 分时弹窗的入口长相（全站一份） =====
   十四个页面里"这是一只可以点的票"的提示必须同一个样子：光标、悬停下划线、颜色。
   逐页 scoped 抄一遍就会有第十四种 hover 反馈，而且新页面一定会忘。
   颜色沿用票名本身的颜色，只加下划线与光标——把它染成链接蓝，
   读起来就像"点了会跳走"，而它打开的是同一只票的分时。 */
.tape-link {
  cursor: pointer;
}
.tape-link:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}
</style>
