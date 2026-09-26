<template>
  <section class="dim-curve">
    <div v-if="!hideHeader" class="curve-head">
      <h3>{{ name }}分走势 <span class="sub">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 空档=未评</span></h3>
      <span class="legend">
        <i class="lg" style="background: #dc2626"></i>高潮
        <i class="lg" style="background: #d97706"></i>发酵
        <i class="lg" style="background: #0891b2"></i>混沌
        <i class="lg" style="background: #4a5568"></i>退潮
      </span>
    </div>
    <el-empty v-if="!rows.length" description="取到的这段区间还没有打分记录（五维打分上线前的日子没有分）" :image-size="60" />
    <div v-else ref="chartRef" class="canvas"></div>
    <AxisZoomBar
      v-if="zoomable"
      :can-zoom-in="zoom.canZoomIn()"
      :can-zoom-out="zoom.canZoomOut()"
      :can-pan-left="zoom.canPanLeft()"
      :can-pan-right="zoom.canPanRight()"
      @zoom-in="run(zoom.zoomIn)"
      @zoom-out="run(zoom.zoomOut)"
      @pan-left="run(() => zoom.pan(-1))"
      @pan-right="run(() => zoom.pan(1))"
    />
    <p v-if="!hideHeader" class="hint">点图上任意一天＝把上面那个日期切到那天，下方各块随日期刷新（一天一次请求）。</p>
  </section>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import AxisZoomBar from './AxisZoomBar.vue'
import { useCurveZoom, MIN_SPAN } from '../utils/curveZoom'
import { fiveDimBandOf } from '../utils/scores'
import { STAGE_COLORS, NO_STAGE_COLOR } from '../utils/stages'

/**
 * 单维分走势（五维模型 0-100 直加权）。
 *
 * <p>视觉与交互全面对齐首页温度曲线（TemperatureChart）：日变化柱、逐点按带位着色、
 * 带下界阈值线标签、最高/最低图钉、选中日期金色竖线、冷→暖面积渐变同一套色值。
 * 分数据来自 {@code t_daily_record.score_*} 回填列，逐日再算一遍只会每天多打一次
 * 指数日 K + N 次个股日 K，所以只画已落库的分。
 */
const props = defineProps({
  /** [{date, score}]，score 为 null 表示那天未评（画空档） */
  rows: { type: Array, default: () => [] },
  /** 当前查看的那天，高亮给出来，否则点和页面对不上 */
  selected: { type: String, default: '' },
  /** 维度名，用于标题与 tooltip，如「大盘生态」 */
  name: { type: String, default: '维度' },
  /** 隐藏头部（标题+图例），用于嵌入折叠块时避免重复 */
  hideHeader: { type: Boolean, default: false }
})
const emit = defineEmits(['select'])

const chartRef = ref(null)
let chart = null

const zoom = useCurveZoom(() => (props.rows || []).length)
const zoomable = computed(() => (props.rows || []).length > MIN_SPAN)
const visibleCount = computed(() => zoom.span())

/** 改窗口 → 重画（option 整份重建，可见切片由 zoom 决定） */
function run(fn) {
  fn()
  renderChart()
}

// 背景四带铺色与首页温度曲线同一套色值（markArea 不带 name：带了会被渲染成带内堆叠乱文）
const BANDS = [
  { label: '高潮', min: 85, max: 100, fill: 'rgba(245,34,45,0.09)' },
  { label: '发酵', min: 60, max: 85, fill: 'rgba(250,173,20,0.07)' },
  { label: '混沌', min: 40, max: 60, fill: 'rgba(148,163,184,0.06)' },
  { label: '退潮', min: 0, max: 40, fill: 'rgba(24,144,255,0.05)' }
]

/**
 * 自适应纵轴：与首页温度曲线 yRange 同一套规则 —— 数据落点上下各留 4 分空白，
 * 且 40/60 两条带下界强制留在视野内（min(vMin,38)/max(vMax,62) 兜底），
 * 放大波动不能丢位置参照，40/60/85 阈值线也才有得画。
 * 传进来的是可见窗口切片，所以按 + 是真放大（纵轴跟着摊开），不是只压横轴。
 */
function axisRange(rows) {
  const vals = rows
    .map((r) => r.score)
    .filter((v) => v != null && !Number.isNaN(Number(v)))
    .map(Number)
  if (!vals.length) return { min: 0, max: 100 }
  const vMin = Math.min(...vals)
  const vMax = Math.max(...vals)
  return {
    min: Math.max(0, Math.floor(Math.min(vMin, 38) - 4)),
    max: Math.min(100, Math.ceil(Math.max(vMax, 62) + 4))
  }
}

function buildOption() {
  const all = props.rows || []
  if (!all.length) return {}
  // 按 ±/≪≫ 的窗口切片：轴类目、系列数据、tooltip 与点击换算的下标一律走同一份可见数组
  const rows = zoom.visible(all)
  const dates = rows.map((r) => r.date)
  const range = axisRange(rows)

  // 日变化：今日-昨日分差；前后任一未评=该日无柱（与首页温度曲线同一套柱样式）
  // 先按全量算再切片，否则缩放后最左那天的柱子会因为邻居被切掉而凭空消失
  const deltas = zoom.visible(all.map((r, i) => {
    const prev = i === 0 ? null : all[i - 1].score
    if (r.score == null || prev == null) return null
    return +(Number(r.score) - Number(prev)).toFixed(1)
  }))
  const dMax = Math.max(...deltas.filter((v) => v != null).map(Math.abs), 5)

  // 只画与可见纵轴重叠的带（自适应缩放后越界的带不铺，避免一大片无意义底色）。
  // 不渲染带名：markArea 数据项一旦带 name 就会被 ECharts 渲染成带内堆叠文字（实测四带名
  // 挤成一团乱文）。因此这里不传 name，并在 markArea 上显式 label.show=false 双保险。
  const bands = BANDS
    .filter((b) => b.max > range.min && b.min < range.max)
    .map((b) => [
      { yAxis: Math.max(b.min, range.min), itemStyle: { color: b.fill } },
      { yAxis: Math.min(b.max, range.max), itemStyle: { color: b.fill } }
    ])

  // 带下界虚线：只在可见范围内画（自适应缩放后 40/60/85 可能落在轴外，画了也看不见）；
  // 标签与首页温度曲线同款："跨过这条线就进入哪个带"
  const THRESHOLDS = [
    { v: 40, label: '混沌 40', color: '#0891b2' },
    { v: 60, label: '发酵 60', color: '#d97706' },
    { v: 85, label: '高潮 85', color: '#dc2626' }
  ]
  const thrLines = THRESHOLDS
    .filter((t) => t.v > range.min && t.v < range.max)
    .map((t) => ({
      yAxis: t.v,
      label: { formatter: t.label, color: t.color, position: 'insideEndTop', fontSize: 10 },
      lineStyle: { color: '#2d3748', type: 'dashed', width: 1 }
    }))

  // 最新一条已评分的横线：一眼看出当前分落在哪；样式对齐首页"当前温度"金线。
  // 取全量最后一天而不是窗口最后一天：缩放看历史时"最新"仍然指当下。
  let latest = null
  all.forEach((r) => { if (r.score != null) latest = Number(r.score) })
  const latestLine = latest != null
    ? [{
        yAxis: latest,
        label: { formatter: `最新 ${latest.toFixed(1)}`, color: '#fbbf24', fontSize: 10, position: 'insideEndTop' },
        lineStyle: { color: '#fbbf24', width: 1.5 }
      }]
    : []

  // 选中日期：金色竖线标出当前看的是哪一天（与首页 activeDate 竖线同款）
  const selIdx = props.selected ? dates.indexOf(props.selected) : -1
  const selectedLine = selIdx >= 0
    ? [{
        xAxis: dates[selIdx],
        lineStyle: { color: '#fbbf24', width: 1.5, type: 'dashed' },
        label: { formatter: dates[selIdx].slice(5), position: 'insideEndTop', color: '#fbbf24', fontSize: 10 }
      }]
    : []

  // 渐变按"分数越高越暖"铺：把 0/40/60/85/100 锚点映射到像素 offset
  //（纵轴自适应，offset 写死会与真实分位错位；与首页温度曲线同一套冷→暖语义、同一套色值）
  const span = range.max - range.min || 1
  const off = (v) => Math.max(0, Math.min(1, (range.max - v) / span))
  const areaStops = [
    { offset: off(100), color: 'rgba(245,34,45,0.32)' },   // 顶部=高分 热红
    { offset: off(85),  color: 'rgba(250,173,20,0.24)' },  // 发酵 暖橙
    { offset: off(60),  color: 'rgba(250,173,20,0.13)' },  // 混沌上沿 淡橙
    { offset: off(40),  color: 'rgba(148,163,184,0.06)' }, // 混沌下沿 中性
    { offset: off(0),   color: 'rgba(24,144,255,0.04)' }   // 底部=低分 冷蓝
  ]
    .sort((a, b) => a.offset - b.offset)
    .filter((s, i, arr) => i === 0 || Math.abs(s.offset - arr[i - 1].offset) > 0.001)

  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed' },
      formatter: (params) => {
        // 双 series（柱+线）后 params 顺序不固定，按名字各取各的，与首页温度曲线同思路
        const line = params.find((x) => x.seriesName === props.name + '分')
        const bar = params.find((x) => x.seriesName === '日变化')
        const i = (line || bar || params[0])?.dataIndex ?? 0
        const r = rows[i]
        if (!r) return ''
        const lines = [`${r.date}`]
        if (r.score == null) {
          lines.push(`得分: <b>未评</b>（该日无五维打分）`)
        } else {
          const v = Number(r.score)
          const band = fiveDimBandOf(v)
          lines.push(`得分: <b>${v.toFixed(1)} / 100</b>`)
          if (band) lines.push(`带位: <b>${band}</b>`)
        }
        if (bar && bar.value != null) lines.push(`日变化: <b>${bar.value > 0 ? '+' : ''}${bar.value}</b>`)
        return lines.join('<br/>')
      }
    },
    grid: { left: 50, right: 46, top: 30, bottom: 26 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#2d3748' } },
      // 轴本体是 ISO 串（点选要把它原样传回去），标签只给人看月/日
      axisLabel: { color: '#8899a6', formatter: (v) => (v || '').slice(5) }
    },
    yAxis: [
      {
        type: 'value',
        min: range.min,
        max: range.max,
        splitLine: { lineStyle: { color: '#2d3748' } },
        axisLabel: { color: '#8899a6' },
        name: '分',
        nameTextStyle: { color: '#8899a6', fontSize: 10 }
      },
      {
        // 日变化副轴：对称量程，柱的升降幅度一眼可比（与首页同款）
        type: 'value',
        name: '日变化',
        nameTextStyle: { color: '#6e7681', fontSize: 10 },
        min: -dMax,
        max: dMax,
        splitNumber: 4,
        splitLine: { show: false },
        axisLabel: { color: '#6e7681', fontSize: 10 }
      }
    ],
    series: [
      {
        // 柱在折线下面（z:2 < z:4），透明度压低避免遮挡分数线；正=升分(暖橙) 负=降分(冷蓝)
        name: '日变化',
        type: 'bar',
        yAxisIndex: 1,
        data: deltas,
        barWidth: '34%',
        z: 2,
        itemStyle: {
          color: (p) => (p.value >= 0 ? 'rgba(250,173,20,0.42)' : 'rgba(24,144,255,0.38)')
        }
      },
      {
        name: props.name + '分',
        type: 'line',
        data: rows.map((r) => {
          if (r.score == null) return null
          const selected = r.date === props.selected
          if (selected) {
            return {
              value: Number(r.score),
              itemStyle: { color: '#fbbf24', borderColor: '#fff', borderWidth: 1.5 },
              symbolSize: 14
            }
          }
          // 逐点按带位着色而不是统一金色：分→带→STAGE_COLORS，与首页逐点按阶段着色同一思路
          const band = fiveDimBandOf(r.score)
          return {
            value: Number(r.score),
            itemStyle: { color: band ? (STAGE_COLORS[band] || NO_STAGE_COLOR) : NO_STAGE_COLOR },
            symbolSize: 9
          }
        }),
        smooth: 0.5,
        symbol: 'circle',
        showAllSymbol: true,
        connectNulls: false,
        z: 4,
        emphasis: {
          // hover 给金色描边，提示这些点可以点
          itemStyle: { borderColor: '#fbbf24', borderWidth: 1.5 }
        },
        // 点位数值标签只在可见窗口点数不多时开（首页曲线数据量小开标签没问题；
        // 五维曲线 − 到底能铺几百天，全开会在 220px 高度里挤成一团）
        label: rows.length <= 31
          ? {
              show: true,
              position: 'top',
              fontSize: 10,
              color: '#8899a6',
              formatter: (p) => (p.value == null ? '' : p.value)
            }
          : { show: false },
        lineStyle: { width: 2.4, color: '#a16207' },
        areaStyle: {
          opacity: 1,
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, areaStops)
        },
        markArea: {
          silent: true,
          label: { show: false },
          data: bands
        },
        markPoint: {
          symbol: 'pin',
          symbolSize: 42,
          data: [
            // 最高/最低图钉配色与首页温度曲线一致（max 红 / min 青蓝）
            { type: 'max', itemStyle: { color: '#dc2626' }, label: { formatter: '{c}', color: '#fff', fontSize: 10 } },
            { type: 'min', itemStyle: { color: '#0891b2' }, label: { formatter: '{c}', color: '#fff', fontSize: 10 } }
          ]
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#2d3748' },
          data: [...thrLines, ...selectedLine, ...latestLine]
        }
      }
    ]
  }
}

function renderChart() {
  if (!chartRef.value) {
    chart?.dispose()
    chart = null
    return
  }
  // canvas 会被 v-if/v-else 销毁重建，缓存的实例还指着已脱离文档的旧节点；
  // 只判 !chart 会让重画静默落到旧节点上，曲线就永久空白
  if (!chart || chart.getDom() !== chartRef.value) {
    chart?.dispose()
    chart = echarts.init(chartRef.value)
    // 只监听画布、不再单独挂 series click：点挂在点上等于把"点一天换一天"这条主交互退化成碰运气。
    // 坐标换算按 grid 定位（加了日变化柱后 seriesIndex 0 变成了柱系列，按 series 找会找错）
    chart.getZr().on('click', (e) => {
      const rows = zoom.visible(props.rows || [])
      const pos = [e.offsetX, e.offsetY]
      if (!rows.length || !chart.containPixel({ gridIndex: 0 }, pos)) return
      const i = Math.round(chart.convertFromPixel({ gridIndex: 0 }, pos)[0])
      const date = rows[i]?.date
      if (date) emit('select', date)
    })
  }
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

// 用 flush:'post'：props.rows 从空数组变成有数据的那一次，canvas 是"这一帧渲染才挂上的"，
// 默认的 pre-flush 会在 DOM 更新前就跑到 → chartRef.value 还是 null，整条曲线永远不会 init。
// post-flush 保证 ref 已绑上真实节点，echarts.init 才拿得到容器尺寸。
watch(() => [props.rows, props.selected], renderChart, { deep: true, flush: 'post' })
onMounted(() => {
  renderChart()
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.dim-curve {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.curve-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}
.curve-head h3 {
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
}
.sub {
  font-size: 12px;
  color: #8899a6;
  font-weight: 400;
  margin-left: 6px;
}
.legend {
  font-size: 12px;
  color: #8899a6;
}
.legend .lg {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  margin: 0 4px 0 10px;
  vertical-align: -1px;
}
.canvas {
  width: 100%;
  height: 220px;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #8899a6;
}
</style>
