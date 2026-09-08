<template>
  <div class="surv-curve">
    <div ref="chartRef" class="canvas"></div>
    <p class="hint">点图上任意一天＝把上面那个日期切到那天，下方「当日在列」跟着换成那天的名单（一天一次请求）。</p>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'

/**
 * 第 9 维的走势：只画进分溢价那一条线。
 *
 * <p>进分家数从图上省略了（原来占左轴、和右轴的溢价挤成两个刻度），但它在 tooltip 里
 * 还留着一行——"0 家"和"那天根本没拉过"都得靠这句话分开。
 *
 * <p>两个 y 值都不打网络——它们就是 {@code t_daily_record.surv_count / surv_premium}，
 * 逐日再算一遍只会每天多打一次指数日 K + N 次个股日 K。
 */
const props = defineProps({
  /** [{date, count, avg}]，count 为 null 表示那天从没拉过 */
  rows: { type: Array, default: () => [] },
  /** 当前查看的那天，高亮给出来，否则点和页面对不上 */
  selected: { type: String, default: '' }
})
const emit = defineEmits(['select'])

const chartRef = ref(null)
let chart = null

/** 右轴上下界跟着实际读数走：写死 ±10 会把 -10.73% 那种单日裁掉半根线。 */
function axisRange(values) {
  const nums = values.filter((v) => v != null).map(Number)
  if (!nums.length) return { min: -10, max: 10 }
  const lo = Math.min(0, Math.floor(Math.min(...nums) / 5) * 5)
  const hi = Math.max(5, Math.ceil(Math.max(...nums) / 5) * 5)
  return { min: lo, max: hi }
}

function buildOption() {
  const rows = props.rows || []
  if (!rows.length) return {}
  const dates = rows.map((r) => r.date)
  const range = axisRange(rows.map((r) => r.avg))

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
        lines.push(r.count == null
          ? '进分家数: <b>未拉取</b>'
          : `进分家数: <b>${r.count}</b> 家`)
        lines.push(`进分溢价: <b>${r.avg == null ? '—' : (Number(r.avg) > 0 ? '+' : '') + r.avg + '%'}</b>`)
        return lines.join('<br/>')
      }
    },
    grid: { left: 52, right: 20, top: 30, bottom: 26 },
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
      axisLabel: { color: '#8899a6', formatter: '{value}%' },
      name: '溢价',
      nameTextStyle: { color: '#8899a6', fontSize: 10 }
    },
    series: [
      {
        name: '进分溢价',
        type: 'line',
        data: rows.map((r) => {
          if (r.avg == null) return null
          const selected = r.date === props.selected
          return {
            value: Number(r.avg),
            itemStyle: selected
              ? { color: '#fbbf24', borderColor: '#e1e8ed', borderWidth: 1 }
              : { color: '#d97706' },
            symbolSize: selected ? 11 : 7
          }
        }),
        smooth: true,
        symbol: 'circle',
        showAllSymbol: true,
        connectNulls: false,
        lineStyle: { width: 2, color: '#d97706' },
        itemStyle: { color: '#d97706' }
      }
    ]
  }
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    // 只监听画布、不再单独挂 series click：柱子撤了以后圆点是唯一命中区，
    // 挂在点上等于把"点一天换一天名单"这条主交互退化成碰运气。
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
.surv-curve {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
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
