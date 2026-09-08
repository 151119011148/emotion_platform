<template>
  <div class="anchor-span-chart">
    <div ref="chartRef" class="canvas"></div>
    <p v-if="outOfWindow.length" class="tail">
      窗口之外还有 {{ outOfWindow.length }} 段跨度未画：{{ outOfWindow.join('、') }}
    </p>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'

/**
 * 主线龙头页那条：跨度当底色、阵眼当日涨跌当柱子。
 * 仪表盘上原来也画这一块，但"哪条主线在领涨"是主题页的问题，两边各一份只会互相打脸。
 */
const props = defineProps({
  /** 曲线窗口的交易日（ISO 串，升序）。轴用它而不是 series 自己的日期：
      AnchorMetricsService.dailySeries 拉不到的那天直接没有点，用它当轴会把曲线压短。 */
  isoDates: { type: Array, default: () => [] },
  /** GET /api/anchors/spans */
  spans: { type: Array, default: () => [] },
  /** GET /api/anchors/series */
  series: { type: Array, default: () => [] }
})

const chartRef = ref(null)
let chart = null

/** 起点向前贴到窗口内第一个交易日；整个跨度都在窗口右边就没有。 */
function firstAtOrAfter(keys, iso) {
  if (!iso) return -1
  for (let i = 0; i < keys.length; i++) {
    if (keys[i] >= iso) return i
  }
  return -1
}

/** 终点向后贴：阵眼 09-05（周日）出窗时，最后一根柱子只能是 09-04，不能倒着把 09-06 也算进去。 */
function lastAtOrBefore(keys, iso) {
  if (!iso) return -1
  for (let i = keys.length - 1; i >= 0; i--) {
    if (keys[i] <= iso) return i
  }
  return -1
}

/** 轴上就是 ISO 串，色带直接写 ISO，不需要再跟显示串做一次对齐——这一步以前年年要在跨年上翻车。 */
function spanAreas(keys) {
  const areas = []
  for (const span of props.spans || []) {
    const from = firstAtOrAfter(keys, span.chartFrom)
    const to = lastAtOrBefore(keys, span.chartTo || span.chartFrom)
    if (from < 0 || to < from) continue
    areas.push([{
      xAxis: keys[from],
      itemStyle: { color: 'rgba(217, 119, 6, 0.10)' },
      label: {
        show: true,
        position: 'insideTop',
        distance: 4,
        align: 'left',
        fontSize: 10,
        color: '#8899a6',
        formatter: `${span.name} · 跨度 ${span.tradeDays ?? '—'} 日 · 最高 ${span.maxBoard ?? '—'} 板`
      }
    }, { xAxis: keys[to] }])
  }
  return areas
}

/** 整段都在窗口左边的跨度不画（图上没有它的位置），但要在下面说一句，否则他会以为登记丢了。 */
const outOfWindow = computed(() => {
  const keys = props.isoDates || []
  if (!keys.length) return []
  return (props.spans || [])
    .filter((s) => s.chartTo && s.chartTo < keys[0])
    .map((s) => `${s.name} ${String(s.startDate).slice(5)}`)
})

/** 阵眼柱子：红涨蓝跌与全站一致，未评的那天直接没有柱子而不是 0。 */
function anchorBars(keys) {
  const byKey = {}
  for (const point of props.series || []) {
    byKey[point.date] = point
  }
  return keys.map((d) => {
    const point = byKey[d]
    if (!point || point.pct == null) return null
    const v = Number(point.pct)
    return {
      value: v,
      anchor: point,
      itemStyle: { color: v > 0 ? 'rgba(239,68,68,0.45)' : v < 0 ? 'rgba(59,130,246,0.5)' : 'rgba(136,153,166,0.35)' }
    }
  })
}

/** 涨跌%的上下界都跟着读数走：写死 ±10 会把创业板阵眼的 +20% 直接裁掉。 */
function axisRange(values) {
  const nums = values.filter((v) => v != null).map((b) => b.value)
  if (!nums.length) return { min: -10, max: 10 }
  return {
    min: Math.min(-10, Math.floor(Math.min(...nums) / 5) * 5),
    max: Math.max(10, Math.ceil(Math.max(...nums) / 5) * 5)
  }
}

function buildOption() {
  const keys = props.isoDates || []
  if (!keys.length) return {}
  const bars = anchorBars(keys)
  const spanData = spanAreas(keys)
  const range = axisRange(bars)

  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed' },
      formatter: (params) => {
        const p = params.find((x) => x.seriesName === '阵眼')
        if (!p || !p.data) return ''
        const a = p.data.anchor
        return [
          `${a.date}`,
          `阵眼 ${a.name}: <b>${a.pct > 0 ? '+' : ''}${a.pct}%</b>`,
          `第8维 ${a.score == null ? '未评' : a.score + ' 分'}`,
          a.lowPct != null ? `<span style="color:#8899a6">最低 ${a.lowPct > 0 ? '+' : ''}${a.lowPct}%</span>` : ''
        ].filter(Boolean).join('<br/>')
      }
    },
    grid: { left: 50, right: 20, top: 30, bottom: 30 },
    xAxis: {
      type: 'category',
      data: keys,
      axisLine: { lineStyle: { color: '#2d3748' } },
      // 轴的数据本体是 ISO 串（跨度要对它取值），标签只给人看月/日
      axisLabel: { color: '#8899a6', formatter: (v) => (v || '').slice(5) }
    },
    yAxis: {
      type: 'value',
      min: range.min,
      max: range.max,
      splitLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6', formatter: '{value}%' },
      name: '阵眼涨跌',
      nameTextStyle: { color: '#8899a6', fontSize: 10 }
    },
    series: [
      {
        name: '阵眼',
        type: 'bar',
        data: bars,
        barWidth: '46%',
        z: 2,
        // markArea 必须挂在某条 series 上，这张图只有柱子这一条，所以底色跟着它、silent 掉不参与 tooltip
        markArea: { silent: true, data: spanData }
      }
    ]
  }
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

watch(() => [props.isoDates, props.spans, props.series], renderChart, { deep: true })
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
.anchor-span-chart {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
}
.canvas {
  width: 100%;
  height: 200px;
}
.tail {
  margin: 6px 0 0;
  font-size: 12px;
  color: #8899a6;
}
</style>
