<template>
  <div ref="chartRef" class="temperature-chart"></div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import { STAGE_COLORS, NO_STAGE_COLOR } from '../utils/stages'

const props = defineProps({
  data: { type: Object, default: () => ({ dates: [], temperatures: [], stages: [] }) },
  /** 当前生效日期（ISO）：仪表盘切到哪天，曲线就把那个点高亮 + 画金色竖线。 */
  activeDate: { type: String, default: '' }
})

const emit = defineEmits(['select-date'])

const chartRef = ref(null)
let chart = null

/**
 * 动态 y 量程：把坐标压到数据附近，日间波动才看得见（0-100 全量程会把 ±8° 的波动压成 8% 画布高度）。
 * 约束：
 *  - 40/60 两条带下界必须留在视野内（min(vMin,38)/max(vMax,62) 兜底），放大波动不能丢位置参照；
 *  - 数据里有负温度（旧引擎每维 -1 分可到 -33.3）时下界必须 ≤0，否则负数段会被裁掉；
 *  - 85 线数据够高才进视野，够不到就不画（markLine 超界自动不渲染）。
 */
function yRange(temperatures) {
  const values = (temperatures || []).filter((t) => t != null)
  if (!values.length) return { yMin: 0, yMax: 100 }
  const vMin = Math.min(...values)
  const vMax = Math.max(...values)
  const hasNegative = values.some((v) => v < 0)
  return {
    yMin: hasNegative
      ? Math.min(0, Math.floor(Math.min(vMin, 38) - 4))
      : Math.max(0, Math.floor(Math.min(vMin, 38) - 4)),
    yMax: Math.min(100, Math.ceil(Math.max(vMax, 62) + 4))
  }
}

/** 值 → 面积渐变 offset（面积顶端=offset 0，底端=offset 1）。 */
function valueToOffset(v, yMin, yMax) {
  return Math.min(1, Math.max(0, 1 - (v - yMin) / (yMax - yMin)))
}

function buildOption() {
  const { dates, temperatures, stages, summaries, dims, labels } = props.data
  if (!dates?.length) return {}

  const { yMin, yMax } = yRange(temperatures)

  // 日变化：今日-昨日温度差；前后任一为空=该日无柱。正=升温(暖橙) 负=降温(冷蓝)
  const deltas = (temperatures || []).map((t, i) => {
    const prev = i === 0 ? null : temperatures[i - 1]
    if (t == null || prev == null) return null
    return +(t - prev).toFixed(1)
  })
  const dMax = Math.max(...deltas.filter((v) => v != null).map(Math.abs), 5)

  // 阈值背景带：随动态量程裁剪，超界的带（如数据从未到 85）自然消失
  const bands = [
    { label: '退潮', start: yMin, end: 40, color: 'rgba(24,144,255,0.05)' },
    { label: '混沌', start: 40, end: 60, color: 'rgba(148,163,184,0.06)' },
    { label: '发酵', start: 60, end: 85, color: 'rgba(250,173,20,0.07)' },
    { label: '高潮', start: 85, end: yMax, color: 'rgba(245,34,45,0.09)' }
  ].filter((b) => b.end - b.start > 0.5)

  // 面积渐变：低处冷蓝 → 高处暖红（温度语义，不是股价的红涨绿跌），
  // 渐变档位锚在 40/60/85 的实际像素位置，动态量程下不会错位
  const areaStops = [
    { offset: 0, color: 'rgba(245,34,45,0.32)' },
    ...[
      [85, 'rgba(250,173,20,0.24)'],
      [60, 'rgba(250,173,20,0.13)'],
      [40, 'rgba(148,163,184,0.06)']
    ]
      .filter(([v]) => v > yMin && v < yMax)
      .map(([v, c]) => ({ offset: valueToOffset(v, yMin, yMax), color: c })),
    { offset: 1, color: 'rgba(24,144,255,0.04)' }
  ].sort((a, b) => a.offset - b.offset)

  // 当前值 = 最后一个有读数的点，画一条金色横线
  let current = null
  for (let i = temperatures.length - 1; i >= 0; i--) {
    if (temperatures[i] != null) {
      current = temperatures[i]
      break
    }
  }

  // 选中日期高亮：在 x 轴上定位，画金色竖线 + 该点放大描边。
  // dates 是 MM/dd 展示串，匹配/回传一律走 isoDates（ISO），否则 /records/date/09/10 会 404
  const isoDates = props.data?.isoDates || []
  const activeIdx = props.activeDate && isoDates.length ? isoDates.indexOf(props.activeDate) : -1

  // 逐点着色而不是 visualMap：阶段是"哪个点属于哪个阶段"，
  // 用 y 值分档去反推阶段，温度相同而阶段不同的两个点就会被画成同一个颜色。
  const points = temperatures.map((t, i) => ({
    value: t,
    symbolSize: i === activeIdx ? 14 : 9,
    itemStyle: i === activeIdx
      ? { color: '#fbbf24', borderColor: '#fff', borderWidth: 1.5 }
      : { color: stages[i] ? (STAGE_COLORS[stages[i]] || NO_STAGE_COLOR) : NO_STAGE_COLOR }
  }))

  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed' },
      formatter: (params) => {
        const temp = params.find((x) => x.seriesName === '温度')
        const bar = params.find((x) => x.seriesName === '日变化')
        const i = (temp || bar || params[0])?.dataIndex ?? 0
        // 子段标签是整串「退潮 · 一阶段」，主阶段已经在里面了，再拼一次就读成「分歧 · 分歧 · 三阶段」
        const stageText = (labels && labels[i]) || stages[i] || '数据不足'
        const lines = [
          `${params[0]?.name ?? ''}`,
          `温度: <b>${temp && temp.value != null ? temp.value : '—'}</b>`,
          `阶段: ${stageText}`
        ]
        if (bar && bar.value != null) lines.push(`日变化: <b>${bar.value > 0 ? '+' : ''}${bar.value}</b>`)
        if (!stages[i] && dims && dims[i] != null) lines.push(`仅 ${dims[i]} 维参与打分，不出阶段`)
        if (summaries && summaries[i]) lines.push(`<span style="color:#8899a6">${summaries[i]}</span>`)
        return lines.join('<br/>')
      }
    },
    grid: { left: 50, right: 46, top: 38, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: true,
      axisLine: { lineStyle: { color: '#2d3748' } },
      axisTick: { show: false },
      axisLabel: { color: '#8899a6' }
    },
    yAxis: [
      {
        type: 'value',
        name: '温度°',
        nameTextStyle: { color: '#6e7681', fontSize: 11 },
        min: yMin,
        max: yMax,
        splitLine: { lineStyle: { color: '#2d3748' } },
        axisLabel: { color: '#8899a6' }
      },
      {
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
        // 柱在折线下面（z:2 < z:4），透明度压低避免遮挡温度线
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
        name: '温度',
        type: 'line',
        data: points,
        // smooth 0.5
        smooth: 0.5,
        symbol: 'circle',
        showSymbol: true,
        // showAllSymbol 默认是 'auto'：x 轴标签一拥挤就跟着把点藏掉，
        // 实测 14 个点只剩 3 个 —— "曲线上看不到实际的点"就是这么来的。
        showAllSymbol: true,
        // 没有读数的日子就断开，不能连过去：连上去的线会被读成"那天也是这个温度附近"
        connectNulls: false,
        z: 4,
        emphasis: {
          // hover 给金色描边，提示这些点可以点
          itemStyle: { borderColor: '#fbbf24', borderWidth: 1.5 }
        },
        label: {
          show: true,
          position: 'top',
          fontSize: 10,
          color: '#8899a6',
          formatter: (p) => (p.value == null ? '' : p.value)
        },
        lineStyle: { width: 2.4, color: '#a16207' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, areaStops)
        },
        markArea: {
          silent: true,
          // 不带 label：逐带 label 会被 ECharts 渲染成堆叠乱文（与五维曲线同问题），
          // 区间语义由阈值线标签 + 渐变颜色承担
          data: bands.map((b) => [
            { yAxis: b.start, itemStyle: { color: b.color } },
            { yAxis: b.end }
          ])
        },
        markPoint: {
          symbol: 'pin',
          symbolSize: 42,
          data: [
            { type: 'max', itemStyle: { color: '#dc2626' }, label: { formatter: '{c}°', color: '#fff', fontSize: 10 } },
            { type: 'min', itemStyle: { color: '#0891b2' }, label: { formatter: '{c}°', color: '#fff', fontSize: 10 } }
          ]
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
            { yAxis: 85, label: { formatter: '高潮 85', color: '#dc2626', position: 'insideEndTop', fontSize: 10 } },
            ...(activeIdx >= 0
              ? [{
                  // 点击曲线点后：金色竖线标出仪表盘当前看的是哪一天
                  xAxis: dates[activeIdx],
                  lineStyle: { color: '#fbbf24', width: 1.5, type: 'dashed' },
                  label: { formatter: dates[activeIdx], position: 'insideEndTop', color: '#fbbf24', fontSize: 10 }
                }]
              : []),
            ...(current != null
              ? [{
                  yAxis: current,
                  lineStyle: { color: '#fbbf24', width: 1.5 },
                  label: { formatter: `当前 ${current}°`, position: 'insideEndTop', color: '#fbbf24', fontSize: 10 }
                }]
              : [])
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
  // off 再 on，避免重复绑定；click 只认数据图形（markLine/markArea 的 componentType 不是 series）
  chart.off('click')
  chart.on('click', (params) => {
    if (params?.componentType !== 'series') return
    const idx = params.dataIndex
    if (idx == null || idx < 0 || idx >= (props.data?.dates || []).length) return
    // 回传 ISO 日期（isoDates 与 dates 同序），dashboard 才能直接按日期取记录
    emit('select-date', (props.data?.isoDates && props.data.isoDates[idx]) || props.data.dates[idx])
  })
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

watch([() => props.data, () => props.activeDate], renderChart, { deep: true })
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
