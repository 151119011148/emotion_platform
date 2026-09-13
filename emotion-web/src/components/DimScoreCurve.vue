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
  // 只画与可见纵轴重叠的带（自适应缩放后越界的带不铺，避免一大片无意义底色）
  const bands = BANDS
    .filter((b) => b.max > range.min && b.min < range.max)
    .map((b) => [
      { name: b.label, yAxis: Math.max(b.min, range.min), itemStyle: { color: b.fill } },
      { yAxis: Math.min(b.max, range.max), itemStyle: { color: b.fill } }
    ])

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
              ? { color: '#fbbf24', borderColor: '#e1e8ed', borderWidth: 1 }
              : { color: '#fbbf24' },
            symbolSize: selected ? 11 : 6
          }
        }),
        smooth: true,
        symbol: 'circle',
        showAllSymbol: true,
        connectNulls: false,
        lineStyle: { width: 3, color: '#fbbf24' },
        itemStyle: { color: '#fbbf24' },
        areaStyle: {
          opacity: 1,
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(251, 191, 36, 0.25)' },
            { offset: 1, color: 'rgba(251, 191, 36, 0.02)' }
          ])
        },
        markArea: {
          silent: true,
          data: bands
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
