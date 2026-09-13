<template>
  <div class="monitor-heatmap" v-loading="loading">
    <!-- 筛选：类型 + 只看绕异动 -->
    <div class="mm-toolbar">
      <el-radio-group v-model="kindFilter" size="small" @change="reload">
        <el-radio-button label="">全部（严重异动+交易所函）</el-radio-button>
      </el-radio-group>
      <el-checkbox v-model="onlyAvoid" @change="reload">只看绕异动 🔥</el-checkbox>
      <el-checkbox v-model="includeDone" @change="reload">含已出池</el-checkbox>
    </div>

    <div class="mm-legend">
      <span class="lg lg-zt">■ 涨停</span>
      <span class="lg lg-zb">■ 炸板</span>
      <span class="lg lg-dt">■ 跌停</span>
      <span class="lg lg-down">■ 绿盘</span>
      <span class="lg lg-up">■ 红盘</span>
      <span class="lg lg-stop">■ 停牌</span>
      <span class="lg-sym">✂断板 💀跌停 ⏸停牌 💥核按钮</span>
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
    <ul v-if="rows.length" class="mm-why">
      <li v-for="row in rows" :key="row.code + row.annDate + 'w'">
        <el-tag :type="toneTag(row.statusTone)" size="small" effect="dark">{{ row.status }}</el-tag>
        <span v-if="row.avoid" class="avoid-tag">🔥 绕异动</span>
        <span class="why-text">{{ row.why }}</span>
      </li>
    </ul>

    <!-- ≤5 只时的累计涨幅曲线 -->
    <div v-if="trackVo.chart && trackVo.chart.length" class="mm-chart">
      <h4>累计涨幅对比 <span class="sub">持续向上=监管被无视 · 拐头=监管生效 · 跌破0轴=核按钮</span></h4>
      <div v-for="s in trackVo.chart" :key="s.name" class="mm-series">
        <span class="s-name">{{ s.name }}</span>
        <svg class="s-line" viewBox="0 0 200 64" preserveAspectRatio="none">
          <polyline :points="linePoints(s.points)"
            fill="none" stroke="#ffd166" stroke-width="2"
            vector-effect="non-scaling-stroke" />
        </svg>
        <span class="s-val">{{ signedPct(lastPct(s.points)) }}</span>
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
const kindFilter = ref('')
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
  if (kindFilter.value) list = list.filter((i) => i.kind === kindFilter.value)
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
  if (!c) return 'cell'
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

function linePoints(pts) {
  if (!pts || !pts.length) return '0,32'
  let min = 0, max = 0
  for (const p of pts) { if (p < min) min = p; if (p > max) max = p }
  const span = max - min || 1
  const n = pts.length, step = 200 / Math.max(n - 1, 1)
  return pts.map((v, idx) => {
    const x = idx * step
    const y = 34 - ((v - min) / span) * 30
    return x.toFixed(1) + ',' + y.toFixed(1)
  }).join(' ')
}
function lastPct(pts) {
  return pts && pts.length ? pts[pts.length - 1] : null
}

async function reload() {
  loading.value = true
  try {
    const res = await reviewApi.surveillanceTrack(props.date,
      kindFilter.value || undefined, !includeDone.value)
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
.mm-legend { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-bottom: 8px; color: #8899a6; font-size: 12px; }
.mm-legend .lg-zt { color: #f56c6c; }
.mm-legend .lg-zb { color: #e6a23c; }
.mm-legend .lg-dt { color: #0f5132; }
.mm-legend .lg-down { color: #2e8b57; }
.mm-legend .lg-up { color: #ff8a80; }
.mm-legend .lg-stop { color: #8899a6; }
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
.cc-chg { display: block; font-variant-numeric: tabular-nums; }
.cc-bd { display: block; color: #ffd166; font-size: 10px; line-height: 1.2; }
.cc-stop { color: #a9bac9; }

.mm-why { margin: 10px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.mm-why li { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: #9fb2c6; font-size: 12px; }
.avoid-tag { color: #ffd166; font-weight: 600; }
.why-text { flex: 1; }

.mm-chart { margin-top: 16px; }
.mm-chart h4 { margin: 0 0 8px; color: #e1e8ed; font-size: 13px; }
.mm-series { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.s-name { width: 130px; color: #9fb2c6; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.s-line { flex: 1; height: 32px; }
.s-val { width: 54px; text-align: right; color: #ffd166; font-variant-numeric: tabular-nums; }
</style>