<template>
  <section class="dim-curve">
    <div class="curve-head">
      <h3>{{ name }}分走势 <span class="sub">近 {{ rows.length }} 个交易日 · 空档=未评</span></h3>
      <span class="legend">
        <i class="lg" style="background: rgba(248, 113, 113, .45)"></i>高潮
        <i class="lg" style="background: rgba(251, 191, 36, .45)"></i>发酵
        <i class="lg" style="background: rgba(96, 165, 250, .45)"></i>混沌
        <i class="lg" style="background: rgba(148, 163, 184, .45)"></i>退潮
      </span>
    </div>
    <el-empty v-if="!rows.length" description="近 90 天还没有打分记录（五维打分上线前的日子没有分）" :image-size="60" />
    <div v-else ref="chartRef" class="canvas"></div>
    <p class="hint">点图上任意一天＝把上面那个日期切到那天，下方各块随日期刷新（一天一次请求）。</p>
  </section>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { fiveDimBandOf } from '../utils/scores'

/**
 * 单维分走势（五维模型 0-100 直加权）。
 *
 * <p>背景按五维 4 带（退潮 0-40 / 混沌 40-60 / 发酵 60-85 / 高潮 85-100）铺色，
 * 一眼看清分数落在哪个带位；分数据来自 {@code t_daily_record.score_*} 回填列，
 * 逐日再算一遍只会每天多打一次指数日 K + N 次个股日 K，所以只画已落库的分。
 */
const props = defineProps({
  /** [{date, score}]，score 为 null 表示那天未评（画空档） */
  rows: { type: Array, default: () => [] },
  /** 当前查看的那天，高亮给出来，否则点和页面对不上 */
  selected: { type: String, default: '' },
  /** 维度名，用于标题与 tooltip，如「大盘生态」 */
  name: { type: String, default: '维度' }
})
const emit = defineEmits(['select'])

const chartRef = ref(null)
let chart = null

const BANDS = [
  { label: '高潮', min: 85, max: 100, fill: 'rgba(248, 113, 113, 0.07)' },
  { label: '发酵', min: 60, max: 85, fill: 'rgba(251, 191, 36, 0.07)' },
  { label: '混沌', min: 40, max: 60, fill: 'rgba(96, 165, 250, 0.07)' },
  { label: '退潮', min: 0, max: 40, fill: 'rgba(148, 163, 184, 0.07)' }
]

/**
 * 自适应纵轴：只在分数实际落点上下留一小段空白，让曲线纵向张满画布。
 * 分数若都挤在 0-100 中间某个窄区，固定 0-100 会让折线看着像平线；缩到数据附近后
 * 同一段波动会被放大拉陡，视觉对比明显增强。背景四带仍按真实 0-100 落位绘制（拼可见段）。
 */
function axisRange(rows) {
  const vals = rows
    .map((r) => r.score)
    .filter((v) => v != null && !Number.isNaN(Number(v)))
    .map(Number)
  if (!vals.length) return { min: 0, max: 100 }
  const rawMin = Math.min(...vals)
  const rawMax = Math.max(...vals)
  const pad = Math.max(15, (rawMax - rawMin) * 0.25)
  let min = rawMin - pad
  let max = rawMax + pad
  if (max - min < 30) {
    const extra = (30 - (max - min)) / 2
    min -= extra
    max += extra
  }
  min = Math.max(0, Math.round(min))
  max = Math.min(100, Math.round(max))
  if (min === max) max = Math.min(100, min + 1)
  return { min, max }
}

function buildOption() {
  const rows = props.rows || []
  if (!rows.length) return {}
  const dates = rows.map((r) => r.date)
  const range = axisRange(rows)
  // 只画与可见纵轴重叠的带（自适应缩放后越界的带不铺，避免一大片无意义底色）。
  // 带名不再标在图上：markArea 逐带 label 会被渲染成堆叠乱文（实测四带名字挤成一团），
  // 带义由右上角图例 + 阈值虚线承担
  const bands = BANDS
    .filter((b) => b.max > range.min && b.min < range.max)
    .map((b) => [
      { name: b.label, yAxis: Math.max(b.min, range.min), itemStyle: { color: b.fill } },
      { yAxis: Math.min(b.max, range.max), itemStyle: { color: b.fill } }
    ])

  // 阈值虚线只在可见范围内画（自适应缩放后 40/60/85 可能落在轴外，画了也看不见）；
  // 颜色提亮一点（原 #2d3748 在深底上几乎隐形），不带文字标签
  const THRESHOLDS = [
    { v: 40 },
    { v: 60 },
    { v: 85 }
  ]
  const thrLines = THRESHOLDS
    .filter((t) => t.v > range.min && t.v < range.max)
    .map((t) => ({
      yAxis: t.v,
      label: { show: false },
      lineStyle: { color: 'rgba(136, 153, 166, 0.5)', type: 'dashed', width: 1 }
    }))

  // 最新一条已评分的横线：一眼看出当前分落在哪；标签挂左端，不挤在右端
  let latest = null
  rows.forEach((r) => { if (r.score != null) latest = Number(r.score) })
  const latestLine = latest != null
    ? [{
        yAxis: latest,
        label: { formatter: `最新 ${latest.toFixed(1)}`, color: '#fbbf24', fontSize: 10, position: 'start' },
        lineStyle: { color: '#fbbf24', width: 1.5, type: 'dashed' }
      }]
    : []

  // 渐变按"分数越高越暖"铺：把 0/40/60/85/100 锚点映射到像素 offset
  //（纵轴自适应，offset 写死会与真实分位错位；与仪表盘温度曲线同一套冷→暖语义）
  const span = range.max - range.min || 1
  const off = (v) => Math.max(0, Math.min(1, (range.max - v) / span))
  const areaStops = [
    { offset: off(100), color: 'rgba(245, 34, 45, 0.30)' },   // 顶部=高分 热红
    { offset: off(85),  color: 'rgba(250, 173, 20, 0.18)' },  // 发酵 暖橙
    { offset: off(60),  color: 'rgba(250, 173, 20, 0.10)' },  // 混沌上沿 淡橙
    { offset: off(40),  color: 'rgba(148, 163, 184, 0.08)' }, // 混沌下沿 中性
    { offset: off(0),   color: 'rgba(59, 130, 246, 0.03)' }   // 底部=低分 冷蓝
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
        const i = params[0]?.dataIndex ?? 0
        const r = rows[i]
        const lines = [`${r.date}`]
        if (r.score == null) {
          lines.push(`得分: <b>未评</b>（该日无五维打分）`)
        } else {
          const v = Number(r.score)
          const band = fiveDimBandOf(v)
          lines.push(`得分: <b>${v.toFixed(1)} / 100</b>`)
          if (band) lines.push(`带位: <b>${band}</b>`)
          if (i > 0 && rows[i - 1]?.score != null) {
            const d = +(v - Number(rows[i - 1].score)).toFixed(1)
            lines.push(`日变化: <b style="color:${d >= 0 ? '#fbbf24' : '#60a5fa'}">${d >= 0 ? '+' : ''}${d}</b>`)
          }
        }
        return lines.join('<br/>')
      }
    },
    grid: { left: 46, right: 18, top: 30, bottom: 26 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#2d3748' } },
      // 轴本体是 ISO 串（点选要把它原样传回去），标签只给人看月/日
      axisLabel: { color: '#8899a6', formatter: (v) => (v || '').slice(5) }
    },
    yAxis: {
      type: 'value',
      min: range.min,
      max: range.max,
      splitLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6' },
      name: '分',
      nameTextStyle: { color: '#8899a6', fontSize: 10 }
    },
    series: [
      {
        name: props.name + '分',
        type: 'line',
        data: rows.map((r) => {
          if (r.score == null) return null
          const selected = r.date === props.selected
          return {
            value: Number(r.score),
            itemStyle: selected
              ? { color: '#fbbf24', borderColor: '#fff', borderWidth: 1.5 }
              : { color: '#fbbf24' },
            symbolSize: selected ? 14 : 9
          }
        }),
        smooth: 0.5,
        symbol: 'circle',
        showAllSymbol: true,
        connectNulls: false,
        lineStyle: { width: 2.6, color: '#fbbf24' },
        emphasis: {
          // hover 给金色描边，提示这些点可以点
          itemStyle: { borderColor: '#fbbf24', borderWidth: 1.5 }
        },
        areaStyle: {
          opacity: 1,
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, areaStops)
        },
        markArea: {
          silent: true,
          data: bands
        },
        markPoint: {
          symbol: 'pin',
          symbolSize: 40,
          data: [
            // 用金/蓝而非红/蓝：最高分常不到 85，标红会被误读成"高潮"
            { type: 'max', itemStyle: { color: '#fbbf24' }, label: { formatter: '{c}', color: '#1a2332', fontSize: 9 } },
            { type: 'min', itemStyle: { color: '#60a5fa' }, label: { formatter: '{c}', color: '#1a2332', fontSize: 9 } }
          ]
        },
        markLine: {
          silent: true,
          symbol: 'none',
          data: [...thrLines, ...latestLine]
        }
      }
    ]
  }
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    // 只监听画布、不再单独挂 series click：点挂在点上等于把"点一天换一天"这条主交互退化成碰运气
    chart.getZr().on('click', (e) => {
      const rows = props.rows || []
      const pos = [e.offsetX, e.offsetY]
      if (!rows.length || !chart.containPixel({ gridIndex: 0 }, pos)) return
      const i = Math.round(chart.convertFromPixel({ seriesIndex: 0 }, pos)[0])
      const date = rows[i]?.date
      if (date) emit('select', date)
    })
  }
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

watch(() => [props.rows, props.selected], renderChart, { deep: true })
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
