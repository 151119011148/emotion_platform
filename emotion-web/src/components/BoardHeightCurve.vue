<template>
  <section class="height-curve">
    <div class="curve-head">
      <h3>{{ name }} <span class="sub">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 点任意一天切日期 · <i class="lk probe">☆</i>试探 <i class="lk break">★</i>破壁成功 <i class="lk line">- -</i>破壁线</span></h3>
    </div>
    <el-empty v-if="!rows.length" description="暂无连板高度数据" :image-size="60" />
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
  </section>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'
import AxisZoomBar from './AxisZoomBar.vue'
import { useCurveZoom, MIN_SPAN } from '../utils/curveZoom'

/**
 * 连板高度曲线：X 轴日期，Y 轴最高板高度，灰虚线阶梯 = 当天要追平的破壁线
 * （旧龙断板那天起钉在它的高度上 H−1 个交易日，之后一级一级往下降）。
 * hover 列出当日并列打到这个高度的全部个股。
 * ☆ 试探破壁 = 另一只票追平这条线（空心红星）；★ 破壁成功 = 这只试探股次日继续涨停（实心红，同一次破壁只标首次）。
 * 判定全在后端（要逐票名单与破壁线），组件只读。
 */
const props = defineProps({
  /**
   * [{date, maxHeight, stockCount, stocks:[{code,name}],
   *   ceiling, lineStock, isProbe, probeStock, isBreak, prevHigh, breakStock}]
   * 按日期升序、一天一个点。
   */
  rows: { type: Array, default: () => [] },
  /** 当前查看的日期，高亮竖线 */
  selected: { type: String, default: '' },
  /** 标题名 */
  name: { type: String, default: '连板高度' }
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

const zoom = useCurveZoom(() => (props.rows || []).length)
const zoomable = computed(() => (props.rows || []).length > MIN_SPAN)
const visibleCount = computed(() => zoom.span())

/** 改窗口 → 重画：buildOption 按新窗口重新切片，Y 轴量程跟着可见数据走 */
function run(fn) {
  fn()
  renderChart()
}

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

function buildOption() {
  const all = props.rows || []
  if (!all.length) return {}
  // 按 ±/≪≫ 的窗口切片：轴类目、系列数据、标记坐标、tooltip 与点击换算的下标必须是同一份数组
  const rows = zoom.visible(all)
  const dates = rows.map((r) => r.date)
  const heights = rows.map((r) => r.maxHeight)
  const ceilings = rows.map((r) => (r.ceiling == null ? null : r.ceiling))
  const breaks = readBreaks(rows)
  const breakByIndex = new Map(breaks.map((b) => [b.index, b]))
  const probes = readProbes(rows)
  const probeByIndex = new Map(probes.map((q) => [q.index, q]))

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
        const i = p.dataIndex
        const r = rows[i]
        if (!r) return ''
        const brk = breakByIndex.get(i)
        const prb = probeByIndex.get(i)
        const heads = []
        if (prb) {
          heads.push(`<span style="color:#ef4444;font-weight:bold">☆ 试探追平 ${prb.line} 板线</span>`)
        }
        if (brk) {
          heads.push(`<span style="color:#ef4444;font-weight:bold">★ 破壁成功 ${brk.prevHigh}→${brk.board}</span>`)
        }
        const lines = [heads.length ? `${r.date} ${heads.join(' ')}` : `${r.date}`]
        lines.push(`最高连板: <b>${r.maxHeight} 板</b> · ${r.stockCount} 只并列`)
        lines.push(`破壁线: <b>${r.ceiling} 板</b>（定线票 ${r.lineStock ? r.lineStock.name : '—'}，另一只票追平才算试探）`)
        if (brk) {
          lines.push(`破壁股: <b>${brk.stock ? brk.stock.name : '—'}</b>（捅破 ${brk.prevHigh} 板线）`)
        } else if (prb) {
          lines.push(`试探股: <b>${prb.stock ? prb.stock.name : '—'}</b>（明天继续涨停才算破壁）`)
        }
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
      splitLine: { lineStyle: { color: '#2d3748' } },
      axisLabel: { color: '#8899a6' },
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
      {
        // 动态破壁线：不画出来，红星标为什么有时在 5 板、有时在 7 板就无从核对
        name: '破壁线',
        type: 'line',
        data: ceilings,
        step: 'end',
        symbol: 'none',
        connectNulls: true,
        lineStyle: { width: 1, type: 'dashed', color: '#6b7f95' },
        z: 3,
        tooltip: { show: false }
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
