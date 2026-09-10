<template>
  <div ref="chartRef" class="temperature-chart"></div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { STAGE_COLORS, NO_STAGE_COLOR } from '../utils/stages'

const props = defineProps({
  data: { type: Object, default: () => ({ dates: [], temperatures: [], stages: [] }) }
})

const chartRef = ref(null)
let chart = null

/**
 * y 轴下界。每维允许 -1 分之后温度区间变成 -33.3~100，写死 min:0 会把负数的那段线裁掉。
 * 没有负数时仍然返回 0：现在这 14 天的画面不能因为加了个分支就跟着动。
 */
function axisBottom(temperatures) {
  const values = (temperatures || []).filter((t) => t != null)
  if (!values.length) return 0
  return Math.min(0, Math.floor(Math.min(...values) / 10) * 10)
}

function buildOption() {
  const { dates, temperatures, stages, summaries, dims, labels } = props.data
  if (!dates?.length) return {}

  // 逐点着色而不是 visualMap：阶段是"哪个点属于哪个阶段"，
  // 用 y 值分档去反推阶段，温度相同而阶段不同的两个点就会被画成同一个颜色。
  const points = temperatures.map((t, i) => ({
    value: t,
    itemStyle: { color: stages[i] ? (STAGE_COLORS[stages[i]] || NO_STAGE_COLOR) : NO_STAGE_COLOR }
  }))

  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed' },
      formatter: (params) => {
        const p = params.find((x) => x.seriesName === '温度') || params[0]
        const i = p.dataIndex
        // 子段标签是整串「退潮 · 一阶段」，主阶段已经在里面了，再拼一次就读成「分歧 · 分歧 · 三阶段」
        const stageText = (labels && labels[i]) || stages[i] || '数据不足'
        const lines = [`${p.name}`, `温度: <b>${p.value == null ? '—' : p.value}</b>`, `阶段: ${stageText}`]
        if (!stages[i] && dims && dims[i] != null) lines.push(`仅 ${dims[i]} 维参与打分，不出阶段`)
        if (summaries && summaries[i]) lines.push(`<span style="color:#8899a6">${summaries[i]}</span>`)
        return lines.join('<br/>')
      }
    },
    grid: { left: 50, right: 20, top: 38, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6' }
    },
    yAxis: {
      type: 'value',
      min: axisBottom(temperatures),
      max: 100,
      splitLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6' }
    },
    series: [
      {
        name: '温度',
        type: 'line',
        data: points,
        smooth: true,
        symbol: 'circle',
        symbolSize: 9,
        showSymbol: true,
        // showAllSymbol 默认是 'auto'：x 轴标签一拥挤就跟着把点藏掉，
        // 实测 14 个点只剩 3 个 —— "曲线上看不到实际的点"就是这么来的。
        showAllSymbol: true,
        // 没有读数的日子就断开，不能连过去：连上去的线会被读成"那天也是这个温度附近"
        connectNulls: false,
        label: {
          show: true,
          position: 'top',
          fontSize: 10,
          color: '#8899a6',
          formatter: (p) => (p.value == null ? '' : p.value)
        },
        lineStyle: { width: 2, color: '#a16207' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(245, 158, 11, 0.18)' },
            { offset: 1, color: 'rgba(245, 158, 11, 0.02)' }
          ])
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#2d3748' },
          data: [
            // 五维双层模型 4 带下界：<40 退潮 / ≥40 混沌 / ≥60 发酵 / ≥85 高潮。
            // 标签写的是"跨过这条线就进入哪个带"，与 BoardScoreCalculator.stageOf 一一对应。
            { yAxis: 40, label: { formatter: '混沌 40', color: '#0891b2', position: 'insideEndTop', fontSize: 10 } },
            { yAxis: 60, label: { formatter: '发酵 60', color: '#d97706', position: 'insideEndTop', fontSize: 10 } },
            { yAxis: 85, label: { formatter: '高潮 85', color: '#dc2626', position: 'insideEndTop', fontSize: 10 } }
          ]
        }
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

watch(() => props.data, renderChart, { deep: true })
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
.temperature-chart {
  width: 100%;
  height: 320px;
}
</style>
