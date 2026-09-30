<template>
  <div class="monitor-heatmap" v-loading="loading">
    <!-- 范围固定为在列的 SEVERE/EXCH（后端 kind 参数仍在，UI 暂不暴露）；
         原先是个只有一项的 radio-group，看着像能选其实没得选。 -->
    <div class="mm-toolbar">
      <span class="mm-scope">范围：严重异动 + 交易所函</span>
      <el-checkbox v-model="onlyAvoid" @change="reload">只看绕异动 🔥</el-checkbox>
      <el-checkbox v-model="includeDone" @change="reload">含已出池</el-checkbox>
      <span class="mm-count">当前 {{ rows.length }} 只</span>
    </div>

    <!-- 图例分两组：底色讲「当天什么股性」，符号讲「当天出了什么事」。
         「跌停/停牌」两套体系各占一次，混在一行读起来像重复项。 -->
    <div class="mm-legend">
      <span class="mm-lg-label">底色</span>
      <span class="lg lg-zt"><i class="sw"></i>涨停</span>
      <span class="lg lg-zb"><i class="sw"></i>炸板</span>
      <span class="lg lg-dt"><i class="sw"></i>跌停</span>
      <span class="lg lg-up"><i class="sw"></i>红盘</span>
      <span class="lg lg-down"><i class="sw"></i>绿盘</span>
      <span class="lg lg-stop"><i class="sw"></i>停牌</span>
      <span class="lg lg-pend"><i class="sw"></i>未到</span>
      <span class="mm-lg-sep"></span>
      <span class="mm-lg-label">符号</span>
      <span class="lg-sym">✂ 断板 · 💀 跌停 · ⏸ 停牌 · 💥 核按钮</span>
    </div>

    <el-empty v-if="!loading && !rows.length" description="该日期已出池或暂无监管全生命周期轨迹" :image-size="60" />

    <div v-if="rows.length" class="mm-table-wrap">
      <table class="mm-table">
        <thead>
          <tr>
            <th rowspan="2" class="th-name">股票</th>
            <th rowspan="2" class="th-meta">进监管</th>
            <th colspan="3" class="th-line">监管期表现</th>
            <th :colspan="colCount" class="th-track">D+{{ firstLive }} → D+{{ lastOffset }}（每日一格）</th>
          </tr>
          <tr>
            <th class="th-meta">累计</th>
            <th class="th-meta">最高板</th>
            <th class="th-meta">拐点</th>
            <th v-for="o in offsets" :key="'h'+o" class="th-cell">D+{{ o }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows" :key="row.code + row.annDate">
            <td class="td-name">
              <div class="nm-line">
                <span class="nm">{{ row.name }}</span>
                <el-tag :type="kindTag(row.kind)" size="small" effect="plain">{{ row.kind }}</el-tag>
              </div>
              <span class="nm-code">{{ row.code }}</span>
            </td>
            <td class="td-meta">
              <span class="ann">{{ fmtDate(row.annDate) }}</span>
              <span class="prog" :class="'tone-' + row.statusTone">
                <span v-if="row.done">已出池</span>
                <span v-else-if="row.currentOffset">D{{ row.currentOffset }}/{{ row.totalDays }}</span>
                <span v-else>—</span>
              </span>
            </td>
            <td class="td-meta num">{{ signedPct(row.cumChg) }}</td>
            <td class="td-meta num">{{ row.maxBoard || '—' }}</td>
            <td class="td-meta num">{{ row.turnDay ? 'D+' + row.turnDay : '—' }}</td>
            <td v-for="o in offsets" :key="'c'+ri+'-'+o" :class="cellClass(cellAt(row, o))">
              <template v-if="colVisible(row, o)">
                <span v-if="cellSuspended(row, o)" class="cc-stop">⏸ 停</span>
                <template v-else>
                  <span class="cc-chg">{{ cellChg(row, o) }}</span>
                  <span v-if="cellBoard(row, o)" class="cc-bd">{{ cellBoard(row, o) }}板</span>
                </template>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 状态脚注：每行为什么定这个状态 -->
    <div v-if="rows.length" class="mm-why-head">状态依据 <span class="sub">与上表同一顺序，逐只给依据</span></div>
    <ul v-if="rows.length" class="mm-why">
      <li v-for="row in rows" :key="row.code + row.annDate + 'w'">
        <!-- 先给票名：脚注与上面的表按顺序对应，但十几只票时靠顺序去猜是哪只太费劲 -->
        <span class="why-name">{{ row.name }}</span>
        <el-tag :type="toneTag(row.statusTone)" size="small" effect="dark">{{ row.status }}</el-tag>
        <span v-if="row.avoid" class="avoid-tag">🔥 绕异动</span>
        <span class="why-text">{{ row.why }}</span>
      </li>
    </ul>

    <!-- ≤5 只时的累计涨幅曲线：所有线共用一套纵轴并画出 0 轴，跨股票才可比 -->
    <div v-if="chartSeries.length" class="mm-chart">
      <h4>累计涨幅对比 <span class="sub">持续向上=监管被无视 · 拐头=监管生效 · 跌破 0 轴=核按钮</span></h4>
      <div class="mm-chart-body">
        <div class="mm-scale">
          <span :style="{ top: yOf(chartScale.max) + '%' }">{{ signedPct(chartScale.max) }}</span>
          <span class="sc-zero" :style="{ top: zeroY + '%' }">0%</span>
          <span :style="{ top: yOf(chartScale.min) + '%' }">{{ signedPct(chartScale.min) }}</span>
        </div>
        <div class="mm-plot">
          <svg class="mm-svg" viewBox="0 0 200 100" preserveAspectRatio="none">
            <line class="mm-zero" x1="0" :y1="zeroY" x2="200" :y2="zeroY" />
            <polyline v-for="s in chartSeries" :key="s.name" :points="linePoints(s.points)"
              fill="none" :stroke="s.color" stroke-width="2"
              vector-effect="non-scaling-stroke" />
            <!-- 每天一个点标记：只走了 D+1 一天的票靠它才在图上看得见 -->
            <g v-for="g in chartDots" :key="g.key">
              <line v-for="(d, i) in g.dots" :key="i" :x1="d.x" :y1="d.y" :x2="d.x + 0.01" :y2="d.y"
                :stroke="g.color" stroke-width="4" stroke-linecap="round"
                vector-effect="non-scaling-stroke" />
            </g>
          </svg>
          <div class="mm-xaxis"><span>D+1</span><span>D+{{ chartMaxLen }}</span></div>
        </div>
        <div class="mm-list">
          <div v-for="s in chartSeries" :key="s.name + '-lg'" class="mm-lg-row">
            <i class="sw" :style="{ background: s.color }"></i>
            <span class="lg-nm">{{ s.name }}</span>
            <b class="lg-val" :class="chgClass(lastPct(s.points))">{{ signedPct(lastPct(s.points)) }}</b>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { reviewApi } from '../api/modules'

const props = defineProps({
  date: { type: String, required: true }
})

const loading = ref(false)
const trackVo = ref({ items: [], chart: [] })
const onlyAvoid = ref(false)
/** 默认只看监控中；勾上则连已出池历史一起给 */
const includeDone = ref(false)

function signedPct(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  return (n > 0 ? '+' : '') + n.toFixed(n % 1 === 0 ? 0 : 1) + '%'
}
function fmtDate(d) {
  if (!d) return '—'
  return String(d).slice(5).replace('-', '/')
}

const rows = computed(() => {
  let list = trackVo.value.items || []
  if (onlyAvoid.value) list = list.filter((i) => i.avoid)
  return list
})

const colCount = computed(() => Math.max(1, offsets.value.length))

/** 全行最大窗口：表头对齐到最长那件的 D+N，短的右侧留空 */
const lastOffset = computed(() => {
  let max = 1
  for (const i of trackVo.value.items || []) if (i.totalDays > max) max = i.totalDays
  return max
})
const offsets = computed(() => {
  const arr = []
  for (let o = 1; o <= lastOffset.value; o++) arr.push(o)
  return arr
})
const firstLive = computed(() => (offsets.value.length ? offsets.value[0] : 1))

function cellOf(row, offset) {
  return (row.daily || []).find((c) => c.offset === offset) || null
}
function cellAt(row, offset) {
  return cellOf(row, offset)
}
function colVisible(row, offset) {
  return offset <= (row.totalDays || 1)
}
function cellSuspended(row, offset) {
  const c = cellOf(row, offset)
  return !!c && c.suspended
}
function cellChg(row, offset) {
  const c = cellOf(row, offset)
  if (!c) return ''
  if (c.suspended) return ''
  if (c.chg == null) return c.event || '·'
  return signedPct(c.chg)
}
function cellBoard(row, offset) {
  const c = cellOf(row, offset)
  return c && c.consecutive ? c.consecutive : ''
}

function cellClass(c) {
  // c 为 null = 监管窗口还没走到那一天（SEVERE/EXCH 是 10 天），与「走完了但没数据」不是一回事，
  // 单独给更暗的底 + 细描边，右侧还剩几天可以一眼数出来
  if (!c) return 'cell cell-pend'
  if (c.suspended) return 'cell cell-stop'
  if (c.pool === 'ZT') return 'cell cell-zt'
  if (c.pool === 'DT') return 'cell cell-dt'
  if (c.pool === 'ZB') return 'cell cell-zb'
  if (c.chg == null) return 'cell cell-flat'
  if (c.chg > 0) return 'cell cell-up'
  if (c.chg < 0) return 'cell cell-down'
  return 'cell cell-flat'
}

function kindTag(k) {
  // 合并后可能是 "EXCH+SEVERE"：含 SEVERE 视为危险，否则含 EXCH 视为警示
  if (!k) return 'info'
  if (k.includes('SEVERE')) return 'danger'
  if (k.includes('EXCH')) return 'warning'
  return 'info'
}
function toneTag(t) {
  return t === '危险' ? 'danger' : t === '警示' ? 'warning' : t === '中性' ? 'info' : 'success'
}

/** 曲线配色（≤5 条）：与页面主色系同族，蓝/红/绿各留一档便于分辨。 */
const SERIES_COLORS = ['#ffd166', '#6fc3ff', '#ff8a80', '#7fd6a8', '#c9a0ff']

const chartSeries = computed(() => (trackVo.value.chart || []).map((s, i) => ({
  ...s,
  color: SERIES_COLORS[i % SERIES_COLORS.length]
})))

/** 横轴按「监管期第几天」对齐（不是各自铺满）：每条线只画到自己走过的天数，
 *  D+3 的票就不会和 D+10 的票一样宽，且曲线与上面表格的列天然对齐。 */
const chartMaxLen = computed(() => {
  let n = 1
  for (const s of chartSeries.value) n = Math.max(n, (s.points || []).length)
  return n
})

/**
 * 纵轴所有线共用，且强制包含 0。累计涨幅是同一量纲，各自归一化会把「+10%」和「−9%」
 * 画成一模一样的高低，跨股票没法比，标题里那句「跌破 0 轴」也没有 0 轴可看。
 */
const chartScale = computed(() => {
  const vals = []
  for (const s of chartSeries.value) for (const p of s.points || []) vals.push(Number(p))
  if (!vals.length) return { min: -1, max: 1 }
  let min = Math.min(0, ...vals)
  let max = Math.max(0, ...vals)
  if (max - min < 1) { max = Math.max(1, max); min = Math.min(-1, min) }
  return { min, max }
})

/** 值 → SVG 纵坐标（viewBox 高 100，上下各留 8 的余量）。 */
function yOf(v) {
  const span = chartScale.value.max - chartScale.value.min || 1
  return 8 + (1 - (Number(v) - chartScale.value.min) / span) * 84
}
const zeroY = computed(() => yOf(0))

function linePoints(pts) {
  if (!pts || !pts.length) return ''
  const step = chartMaxLen.value > 1 ? 200 / (chartMaxLen.value - 1) : 0
  return pts.map((v, i) => `${(i * step).toFixed(1)},${yOf(v).toFixed(1)}`).join(' ')
}

/**
 * 每天一个点标记。单靠 polyline 的话，「刚进监管只走了 D+1」的票只有一个点、连线画不出来，
 * 右侧图例里有它、图上却什么都没有。用零长线段 + 圆头帽当圆点：stroke 宽度吃
 * vector-effect 不随 viewBox 拉伸，所以不会被 preserveAspectRatio="none" 压成扁椭圆。
 */
const chartDots = computed(() => chartSeries.value.map((s, si) => {
  const step = chartMaxLen.value > 1 ? 200 / (chartMaxLen.value - 1) : 0
  return {
    key: si + '-' + s.name,
    color: s.color,
    dots: (s.points || []).map((v, i) => ({
      x: Math.max(1, i * step),
      y: yOf(v)
    }))
  }
}))

function lastPct(pts) {
  return pts && pts.length ? pts[pts.length - 1] : null
}
/** 涨红跌绿（A 股口径），与表格格子的底色同一套语义。 */
function chgClass(v) {
  if (v == null || Number.isNaN(Number(v))) return ''
  return Number(v) > 0 ? 'up' : Number(v) < 0 ? 'down' : ''
}

async function reload() {
  loading.value = true
  try {
    // 第二个参数是后端的 kind 过滤（SEVERE/EXCH/ZD）：UI 已不再暴露，固定用后端默认范围
    const res = await reviewApi.surveillanceTrack(props.date, undefined, !includeDone.value)
    trackVo.value = res?.data || { items: [], chart: [] }
  } finally {
    loading.value = false
  }
}
function init() { reload() }
watch(() => props.date, () => reload())
init()
</script>

<style scoped>
.monitor-heatmap { font-size: 13px; }
.mm-toolbar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 10px; }
.mm-scope { color: #9fb2c6; font-size: 12px; }
.mm-count { margin-left: auto; color: #7f93a6; font-size: 12px; font-variant-numeric: tabular-nums; }
.mm-legend { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; color: #8899a6; font-size: 12px; }
.mm-lg-label { color: #6e7f90; }
.mm-lg-sep { width: 1px; height: 12px; background: #2c3e50; }
.mm-legend .lg { display: inline-flex; align-items: center; gap: 4px; }
.mm-legend .sw { display: inline-block; width: 10px; height: 10px; border-radius: 2px; background: currentColor; }
/* 底色语义（色块取 currentColor，改这里就等于改色块）：涨停红 / 炸板橙 / 跌停绿 / 红盘浅红 / 绿盘绿 / 停牌灰 / 未到暗灰。
   原 lg-dt 用的 #0f5132 在 #1a2332 卡片上几乎看不清，一并提亮。 */
.mm-legend .lg-zt { color: #e05a5a; }
.mm-legend .lg-zb { color: #c8852a; }
.mm-legend .lg-dt { color: #2f7a52; }
.mm-legend .lg-up { color: #c96a63; }
.mm-legend .lg-down { color: #3f9b6b; }
.mm-legend .lg-stop { color: #6b7c8c; }
.mm-legend .lg-pend { color: #3b4a5a; }
.mm-legend .lg-sym { color: #9fb2c6; }

.mm-table-wrap { overflow-x: auto; }
.mm-table { border-collapse: collapse; width: 100%; font-size: 12px; }
.mm-table th, .mm-table td { border: 1px solid #2c3e50; padding: 4px 6px; text-align: center; }
.mm-table thead th { background: #1c2b3a; color: #9fb2c6; font-weight: 600; position: sticky; top: 0; }
.th-name { text-align: left; min-width: 110px; }
.th-track { background: #16222e !important; color: #ffd166 !important; }
.th-cell { min-width: 46px; color: #7fa3bd; font-weight: 400; }

.td-name { text-align: left; }
.nm-line { display: flex; align-items: center; gap: 6px; }
.nm { color: #e1e8ed; font-weight: 600; }
.nm-code { color: #72889b; font-size: 11px; }
.td-meta.num { font-variant-numeric: tabular-nums; }
.ann { display: block; color: #9fb2c6; font-size: 11px; }
.prog { display: inline-block; font-size: 11px; margin-top: 2px; }
.tone-danger { color: #f56c6c; }
.tone-警示,
.tone-warning { color: #e6a23c; }
.tone-neutral { color: #8899a6; }

.cell { height: 30px; color: #e1e8ed; }
.cell-zt { background: #c0392b; color: #fff; }
.cell-zb { background: #805217; color: #ffe7bd; }
.cell-dt { background: #0b4d2e; color: #b7e6cc; }
.cell-up { background: rgba(200, 60, 60, 0.25); color: #ffb3ad; }
.cell-down { background: rgba(40, 120, 90, 0.25); color: #bcecd2; }
.cell-stop { background: #2a3645; color: #7f93a6; }
.cell-flat { background: #1f2e3d; color: #7f93a6; }
/* 未到 = 监管窗口还没走到的格子：留空但描细边，与「出池后的空白」区分开 */
.cell-pend { background: rgba(255, 255, 255, .015); box-shadow: inset 0 0 0 1px #24313f; }
.cc-chg { display: block; font-variant-numeric: tabular-nums; }
.cc-bd { display: block; color: #ffd166; font-size: 10px; line-height: 1.2; }
.cc-stop { color: #a9bac9; }

.mm-why-head { margin-top: 14px; color: #9fb2c6; font-size: 12px; }
.mm-why { margin: 8px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.mm-why li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: #9fb2c6; font-size: 12px; }
.why-name { min-width: 76px; color: #e1e8ed; font-weight: 600; }
.avoid-tag { color: #ffd166; font-weight: 600; }
.why-text { flex: 1; }

.mm-chart { margin-top: 18px; }
.mm-chart h4 { margin: 0 0 10px; color: #e1e8ed; font-size: 13px; }
.mm-chart .sub { font-weight: 400; }
/* 高度定死 110 + 18（刻度行）：纵轴标签、曲线、图例三者都在同一个 110 里，
   之前刻度按 128 定位、曲线只占 110，底部的 -x% 标签会比线低十几像素。 */
.mm-chart-body { display: flex; align-items: flex-start; gap: 10px; }
/* 纵轴刻度：0% 按真实位置绝对定位（不是永远居中），与图里的 0 轴虚线对齐 */
.mm-scale { position: relative; width: 50px; height: 110px; flex: none; }
.mm-scale span { position: absolute; right: 0; transform: translateY(-50%); font-size: 11px; color: #7f93a6; font-variant-numeric: tabular-nums; }
.mm-scale .sc-zero { color: #9fb2c6; }
.mm-plot { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.mm-svg { width: 100%; height: 110px; display: block; }
.mm-zero { stroke: #5b6b7c; stroke-width: 1; stroke-dasharray: 4 4; }
.mm-xaxis { display: flex; justify-content: space-between; height: 18px; margin-top: 2px; font-size: 11px; color: #6e7f90; }
.mm-list { width: 170px; height: 110px; flex: none; display: flex; flex-direction: column; justify-content: center; gap: 5px; }
.mm-lg-row { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.mm-lg-row .sw { width: 10px; height: 10px; border-radius: 2px; flex: none; }
.mm-lg-row .lg-nm { flex: 1; color: #9fb2c6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mm-lg-row .lg-val { font-variant-numeric: tabular-nums; }
/* 末值按涨跌上色（A 股口径：涨红跌绿），与表格格子同一套语义 */
.mm-chart .up { color: #ff8a80; }
.mm-chart .down { color: #3f9b6b; }
@media (max-width: 760px) {
  .mm-chart-body { flex-wrap: wrap; }
  .mm-scale { display: none; }
  .mm-plot { flex: 1 1 100%; }
  .mm-list { width: 100%; height: auto; margin-top: 8px; }
}
</style>