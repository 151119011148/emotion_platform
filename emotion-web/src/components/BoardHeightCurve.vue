<template>
  <section class="height-curve">
    <div class="curve-head">
      <h3>{{ name }} <span class="sub">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 点任意一天切日期 · <i class="lk probe">☆</i>试探 <i class="lk break">★</i>破壁成功 <i class="lk line" :style="{ color: focusedColor }">- -</i>破壁线{{ originNote }}</span></h3>
    </div>
    <el-empty v-if="!rows.length" description="暂无连板高度数据" :image-size="60" />
    <div v-else ref="chartRef" class="canvas"></div>
    <AxisZoomBar
      v-if="zoomable"
      :can-zoom-in="zoom.canZoomIn()"
      :can-zoom-out="zoom.canZoomOut()"
      :can-pan-left="zoom.canPanLeft()"
      :can-pan-right="zoom.canPanRight()"
      @zoom-in="zoom.zoomIn"
      @zoom-out="zoom.zoomOut"
      @pan-left="() => zoom.pan(-1)"
      @pan-right="() => zoom.pan(1)"
    />
  </section>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import AxisZoomBar from './AxisZoomBar.vue'
import { useCurveZoom, DEFAULT_SPAN, MIN_SPAN } from '../utils/curveZoom'

/**
 * 连板高度曲线：X 轴日期，Y 轴最高板高度，虚线阶梯 = 当天要追平的破壁线
 * （旧龙断板那天起钉在它的高度上 H−1 个交易日，之后一级一级往下降）。
 * 破壁线按**来源**（哪天哪只票打出这个高度）切成一段一段分色，段首标出来源；
 * 同一条线被人追平续钉不算新来源（还是原来那只票的颜色），只有换了一级才换色。
 * hover 列出当日并列打到这个高度的全部个股。
 * ☆ 试探破壁 = 另一只票追平这条线（空心红星）；★ 破壁成功 = 这只试探股次日继续涨停（实心红，同一次破壁只标首次）。
 * 判定全在后端（要逐票名单与破壁线），组件只读。
 */
const props = defineProps({
  /**
   * [{date, maxHeight, stockCount, stocks:[{code,name}],
   *   ceiling, lineStock, lineOriginDate, lineOriginStock,
   *   isProbe, probeStock, isBreak, prevHigh, breakStock}]
   * 按日期升序、一天一个点。
   */
  rows: { type: Array, default: () => [] },
  /** 当前查看的日期，高亮竖线 */
  selected: { type: String, default: '' },
  /** 标题名 */
  name: { type: String, default: '连板高度' },
  /** 联动组名：同名的曲线共用一份缩放窗口（连板生态页与分数曲线同组） */
  zoomGroup: { type: String, default: '' }
})
const emit = defineEmits(['select'])

const chartRef = ref(null)
let chart = null

/**
 * ECharts 内置符号没有 star（只到 circle/rect/diamond/pin/arrow/triangle），
 * 写 'star' 不报错、静默画成方块 —— 五角星只能自己给 path。
 * 外接圆半径 50、内角半径 20，左上角起算的 100×100 视框。
 */
const STAR_PATH =
  'path://M50,0 L61.76,33.82 L97.55,34.55 L69.02,56.18 L79.39,90.45 ' +
  'L50,70 L20.61,90.45 L30.98,56.18 L2.45,34.55 L38.24,33.82 Z'

const zoom = useCurveZoom(() => (props.rows || []).length, DEFAULT_SPAN, props.zoomGroup)
const zoomable = computed(() => (props.rows || []).length > MIN_SPAN)
const visibleCount = computed(() => zoom.span())

/** 图例报的是「当前这条线」：选中那天优先，选中的日子不在窗口里就按窗口里最新一天。 */
const focusedRow = computed(() => {
  const vis = zoom.visible(props.rows || [])
  if (!vis.length) return null
  return vis.find((r) => r.date === props.selected) || vis[vis.length - 1]
})

/**
 * 「09-07 龙版传媒」——这条线的高度是哪天、哪只票打出来的。
 * 跨年时补上年份，否则看着 2027 年的日子说「来自 05-19」会被读成当年的 5 月。
 */
function originPhrase(r) {
  if (!r || !r.lineOriginDate) return ''
  const full = r.lineOriginDate.slice(0, 4) !== r.date.slice(0, 4)
  const day = full ? r.lineOriginDate : r.lineOriginDate.slice(5)
  const name = r.lineOriginStock ? ` ${r.lineOriginStock.name}` : ''
  return `${day}${name}`
}

/**
 * 图例后缀「 6板 · 来自 09-07 龙版传媒」。来源只在这个板高第一次立起来时记，
 * 之后被人追平续钉、定线票换个名字挂线都不改——追平它的那只票不是它的来源。
 */
const originNote = computed(() => {
  const r = focusedRow.value
  if (!r || r.ceiling == null) return ''
  const o = originPhrase(r)
  return o ? ` ${r.ceiling}板 · 来自 ${o}` : ` ${r.ceiling}板`
})

const LINE_GREY = '#6b7f95'
/** 来源分色的调色板：避开琥珀（最高连板那条线）和红（星） */
const ORIGIN_COLORS = ['#22d3ee', '#a78bfa', '#f472b6', '#34d399', '#60a5fa', '#e879f9']

/** 一条线的身份 = 板高 + 来源那天 + 那只票。追平续钉不改身份，降级才换。 */
function originKey(r) {
  if (!r || r.ceiling == null) return ''
  return `${r.ceiling}|${r.lineOriginDate || '—'}|${(r.lineOriginStock && r.lineOriginStock.code) || '—'}`
}

/** 颜色按**全量**顺序分配，不按可见窗口：平移缩放时同一条线不会变色。 */
const colorByKey = computed(() => {
  const m = new Map()
  for (const r of props.rows || []) {
    const k = originKey(r)
    if (k && !m.has(k)) m.set(k, ORIGIN_COLORS[m.size % ORIGIN_COLORS.length])
  }
  return m
})

function colorOf(r) {
  const k = originKey(r)
  return (k && colorByKey.value.get(k)) || LINE_GREY
}

const focusedColor = computed(() => (focusedRow.value ? colorOf(focusedRow.value) : LINE_GREY))

/** 改窗口 → 重画：buildOption 按新窗口重新切片，Y 轴量程跟着可见数据走。
 *  重画由下面对 zoom.state 的 watch 统一触发，本图的按钮也走同一条路，
 *  这样同组另一条曲线按了 + 这边才会跟着变。 */

/**
 * 破壁点直接读后端判定：判伴生要逐票启动日，曲线这份聚合数据里没有。
 * @returns [{index, prevHigh, board, stock}]
 */
function readBreaks(rows) {
  const out = []
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].isBreak) {
      out.push({
        index: i,
        prevHigh: rows[i].prevHigh,
        board: rows[i].maxHeight,
        stock: rows[i].breakStock || null
      })
    }
  }
  return out
}

/** 试探破壁：追平了当天的破壁线但还没等到次日续板，成败要看后一天。 */
function readProbes(rows) {
  const out = []
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].isProbe) {
      out.push({
        index: i,
        line: rows[i].ceiling,
        stock: rows[i].probeStock || null
      })
    }
  }
  return out
}

/**
 * 把破壁线按来源切成段：连着几天都是同一本账（同一个板高、同一天同一只票打出来的）就是一段，
 * 换账起新段。段与段各自上色，段首那颗点是这段的来源。
 */
function readLineRuns(rows) {
  const runs = []
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]
    if (r.ceiling == null) continue
    const k = originKey(r)
    const cur = runs[runs.length - 1]
    if (cur && cur.key === k && cur.ceiling === r.ceiling) cur.end = i
    else runs.push({ key: k, ceiling: r.ceiling, start: i, end: i, row: r })
  }
  return runs
}

function buildOption() {
  const all = props.rows || []
  if (!all.length) return {}
  // 按 ±/≪≫ 的窗口切片：轴类目、系列数据、标记坐标、tooltip 与点击换算的下标必须是同一份数组
  const rows = zoom.visible(all)
  const dates = rows.map((r) => r.date)
  const heights = rows.map((r) => r.maxHeight)
  const ceilings = rows.map((r) => (r.ceiling == null ? null : r.ceiling))
  const breaks = readBreaks(rows)
  const probes = readProbes(rows)

  // Y 轴按可见窗口算：只收窄横轴、纵轴还按全量，放大就只是把线压扁，等于没放大。
  // 破壁线（ceiling）也计入上界——它是从更早的高点继承来的，可能高于当天最高板。
  const yMin = 2
  const yMax = Math.max(...heights, ...ceilings.filter((v) => v != null), 5) + 1

  // 试探 = 同款空心红星（不填红），次日续板兑现才填成实心★
  // 内填按画布底色而不是 transparent：压在下面的那颗琥珀圆点会从星形中间露出来，空心就读成了糊
  // 试探在点下方、成功在点上方：这两天天然相邻，都朝上就会互相压住。
  // 但退潮期线可以低到 2 板 = Y 轴底，标签朝下就压进日期刻度行，这时翻上去。
  const probePoints = probes.map((q) => ({
    coord: [dates[q.index], heights[q.index]],
    value: heights[q.index],
    symbol: STAR_PATH,
    symbolSize: 16,
    symbolKeepAspect: true,
    itemStyle: { color: '#1a2332', borderColor: '#ef4444', borderWidth: 2 },
    label: {
      show: true,
      formatter: `试探 ${q.line}板${q.stock ? '\n' + q.stock.name : ''}`,
      lineHeight: 12,
      position: heights[q.index] <= yMin ? 'top' : 'bottom',
      color: '#ef4444',
      fontSize: 10
    }
  }))

  // 破壁成功：把试探那颗空心星填成实心红
  const breakPoints = breaks.map((b) => ({
    coord: [dates[b.index], heights[b.index]],
    value: heights[b.index],
    symbol: STAR_PATH,
    symbolSize: 18,
    symbolKeepAspect: true,
    itemStyle: { color: '#ef4444' },
    label: {
      show: true,
      formatter: `破壁 ${b.prevHigh}→${b.board}${b.stock ? '\n' + b.stock.name : ''}`,
      lineHeight: 12,
      position: 'top',
      color: '#ef4444',
      fontSize: 10,
      fontWeight: 'bold'
    }
  }))
  const marks = probePoints.concat(breakPoints)

  // 选中日期竖线：必须两点式。单点 {xAxis} 的端点贴着网格底，标签会被钳进 X 轴刻度行
  // （选最左一天时实测 y182-194，与首个刻度标签正面重叠）；锚到 yMax 后恒在 y157-167。
  const selIdx = props.selected ? dates.indexOf(props.selected) : -1
  const selectedLine = selIdx >= 0
    ? [[
        { xAxis: dates[selIdx], yAxis: yMin },
        {
          xAxis: dates[selIdx],
          yAxis: yMax,
          // rotate:0 关掉沿竖线旋转标签的默认行为，否则日期竖排压住刻度；
          // 底色：标签落在网格内、压在琥珀色面积上，不铺底读不出来
          label: {
            formatter: dates[selIdx].slice(5),
            position: 'insideEndTop',
            rotate: 0,
            color: '#fbbf24',
            fontSize: 10,
            backgroundColor: '#1a2332',
            padding: [2, 3],
            borderRadius: 3
          }
        }
      ]]
    : []

  /**
   * 破壁线：一段一段画，颜色 = 这段的来源（同一本账连续的日子并成一段）。每段是一条等高虚线，
   * 段首多点一笔落在上一级高度上，用它补出阶梯下落的那一竖（不这么补，两段之间是两根悬浮的横线）。
   * 段首标出来源（「08-28 深中华A」）：段宽放得下、且不压到已经占位的标签才标，
   * 放大到全量 59 个来源时只留颜色＋那颗点；选中那天所在的那段和窗口尾段永远标，读者靠它认色。
   */
  const lineRuns = readLineRuns(rows)
  const innerW = Math.max((chartRef.value ? chartRef.value.clientWidth : 0) - 70, 120)
  const pxPerDay = innerW / Math.max(rows.length - 1, 1)
  const meta = lineRuns.map((run, n) => {
    const text = originPhrase(run.row)
    const cjk = (text.match(/[^\x00-\xff]/g) || []).length
    const needW = cjk * 10 + (text.length - cjk) * 5.5 + 16
    const lastIdx = n + 1 < lineRuns.length ? lineRuns[n + 1].start : run.end
    const x0 = 40 + run.start * pxPerDay
    return {
      run,
      n,
      lastIdx,
      text,
      needW,
      x0,
      x1: x0 + needW,
      // 选中那天所在的那段（以及窗口尾段）一定要标，读者靠它认色
      forced: (selIdx >= run.start && selIdx <= lastIdx) || n === lineRuns.length - 1
    }
  })
  const taken = meta.filter((m) => m.forced && m.text).map((m) => [m.x0 - 4, m.x1 + 4])
  meta.forEach((m) => {
    if (!m.text) { m.show = false; return }
    if (m.forced) { m.show = true; return }
    const days = (m.lastIdx - m.run.start + 1) * pxPerDay
    // 段太窄放不下就退成"只有颜色＋那颗点"；放得下也不许压到别人已经占住的位置
    const clash = taken.some(([a, b]) => m.x0 < b && m.x1 > a)
    m.show = days >= m.needW && !clash
    if (m.show) taken.push([m.x0 - 4, m.x1 + 4])
  })
  const lineSeries = meta.map((m) => {
    const run = m.run
    const prev = m.n > 0 && lineRuns[m.n - 1].end === run.start - 1 ? lineRuns[m.n - 1] : null
    const pts = []
    if (prev) pts.push([dates[run.start], prev.ceiling])
    for (let i = run.start; i <= m.lastIdx; i++) pts.push([dates[i], run.ceiling])
    const col = colorOf(run.row)
    return {
      name: '破壁线',
      type: 'line',
      data: pts,
      symbol: 'none',
      silent: true,
      lineStyle: { width: 1, type: 'dashed', color: col },
      z: 3,
      tooltip: { show: false },
      markPoint: {
        silent: true,
        data: [{
          coord: [dates[run.start], run.ceiling],
          symbol: 'circle',
          symbolSize: 5,
          itemStyle: { color: col, borderColor: '#1a2332', borderWidth: 1 },
          label: {
            show: m.show,
            formatter: m.text,
            position: run.ceiling >= yMax - 0.6 ? 'bottom' : 'top',
            distance: 5,
            color: col,
            fontSize: 9,
            backgroundColor: 'rgba(26,35,50,0.88)',
            padding: [2, 4],
            borderRadius: 3
          }
        }]
      }
    }
  })

  return {
    tooltip: {
      trigger: 'axis',
      confine: true,
      // 退潮期最高板掉到 2 板时并列能到几十只，不滚动就会被 confine 裁掉
      enterable: true,
      extraCssText: 'max-width:460px;max-height:300px;overflow:auto',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed' },
      formatter: (params) => {
        const p = params[0]
        if (!p) return ''
        const r = rows[p.dataIndex]
        if (!r) return ''
        // 只报日期和当天并列打到最高板的票：破壁线、试探/破壁判定这些图上已有点和标签，
        // 再在 hover 里铺一遍就把这块 300px 高的浮层撑成一屏说明文
        const lines = [r.date]
        const stocks = r.stocks || []
        for (let k = 0; k < stocks.length; k += 4) {
          const cells = stocks.slice(k, k + 4)
            .map((s) => `<b>${s.name}</b>(${s.code})`)
            .join(' ')
          // 首行带标签，续行用全角空格缩进对齐
          lines.push(`${k === 0 ? '最高板个股: ' : '　　　　　　'}${cells}`)
        }
        return lines.join('<br/>')
      }
    },
    grid: { left: 40, right: 30, top: 30, bottom: 26 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6', formatter: (v) => (v || '').slice(5) }
    },
    yAxis: {
      type: 'value',
      min: yMin,
      max: yMax,
      // 一格一板：板高是整数刻度，"6 板半"没有意义。缩到十几板时让标签自己避让，
      // 网格线还是每板一条，只是挤不动的标签不硬贴
      interval: 1,
      axisLabel: { color: '#8899a6', hideOverlap: true },
      splitLine: { lineStyle: { color: '#2d3748' } },
      name: '板高',
      nameTextStyle: { color: '#8899a6', fontSize: 10 }
    },
    series: [
      {
        name: '最高连板',
        type: 'line',
        data: heights,
        smooth: 0.3,
        symbol: 'circle',
        symbolSize: 8,
        showAllSymbol: true,
        lineStyle: { width: 2.5, color: '#fbbf24' },
        itemStyle: { color: '#fbbf24', borderColor: '#1a2332', borderWidth: 2 },
        emphasis: {
          itemStyle: { borderColor: '#fbbf24', borderWidth: 2 }
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(251,191,36,0.25)' },
            { offset: 1, color: 'rgba(251,191,36,0.02)' }
          ])
        },
        markPoint: marks.length ? { data: marks } : undefined,
        markLine: selectedLine.length ? {
          silent: true,
          symbol: 'none',
          lineStyle: { type: 'dashed', color: '#fbbf24', width: 1.5 },
          data: selectedLine
        } : undefined
      },
      ...lineSeries
      // 上面按来源切的每一段各一条；这里不再有一条全量的破壁线系列
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

watch(() => [props.rows, props.selected], renderChart, { deep: true, flush: 'post' })
// 窗口状态可能在同组另一条曲线的按钮上被改，所以重画挂在状态上，不挂在本图的点击上
watch(() => [zoom.state.back, zoom.state.span], renderChart, { flush: 'post' })
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
.height-curve {
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
.canvas {
  width: 100%;
  height: 200px;
}
.lk {
  font-style: normal;
  font-size: 11px;
  letter-spacing: 0;
}
.lk.probe {
  color: #ef4444;
}
.lk.break {
  color: #ef4444;
}
.lk.line {
  color: #6b7f95;
  letter-spacing: 1px;
}
</style>
