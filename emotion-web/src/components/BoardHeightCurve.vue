<template>
  <section class="height-curve">
    <div class="curve-head">
      <h3>{{ name }} <span class="sub">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 点任意一天切日期 · <i class="lk break">★</i>破壁 <i class="lk leader">◆</i>总龙头 <i class="lk line">- -</i>破壁线</span></h3>
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
 * 连板高度曲线：X 轴日期，Y 轴最高板高度，灰虚线阶梯 = 当天要捅破的动态破壁线（混沌高+1）。
 * hover 列出当日并列打到这个高度的全部个股。
 * 两种标记：红星「破壁」= 新龙捅破混沌线（同一次破壁只标首次）；紫菱「总龙头」= 有票越过在册
 * 龙头高度、周期确立。判定全在后端（要逐票启动日），组件只读。
 */
const props = defineProps({
  /**
   * [{date, maxHeight, stockCount, stocks:[{code,name}],
   *   ceiling, isBreak, prevHigh, breakStock,
   *   isLeader, leaderStock, cycleTop, cycleLeader}]
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

/**
 * 换龙日：某票把市场最高板抬过在册总龙头的高度，周期从这天起归它。
 * @returns [{index, board, stock}]
 */
function readLeaders(rows) {
  const out = []
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].isLeader) {
      out.push({ index: i, board: rows[i].maxHeight, stock: rows[i].leaderStock || null })
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
  const leaders = readLeaders(rows)
  const breakByIndex = new Map(breaks.map((b) => [b.index, b]))
  const leaderByIndex = new Map(leaders.map((l) => [l.index, l]))

  // Y 轴按可见窗口算：只收窄横轴、纵轴还按全量，放大就只是把线压扁，等于没放大。
  // 破壁线（ceiling）也计入上界——它是从更早的高点继承来的，可能高于当天最高板。
  const yMin = 2
  const yMax = Math.max(...heights, ...ceilings.filter((v) => v != null), 5) + 1

  // 首次破壁的点用红色星标标记
  const breakPoints = breaks.map((b) => ({
    coord: [dates[b.index], heights[b.index]],
    value: heights[b.index],
    symbol: 'star',
    symbolSize: 18,
    itemStyle: { color: '#ef4444' },
    label: {
      show: true,
      formatter: `破壁 ${b.prevHigh}→${b.board}`,
      position: 'top',
      color: '#ef4444',
      fontSize: 10,
      fontWeight: 'bold'
    }
  }))

  // 换龙日标紫菱（点在下方，与上方红星分居两侧）；破壁当天通常同时开周期，同坐标两枚符号会互盖，只留红星
  const leaderPoints = leaders.filter((l) => !breakByIndex.has(l.index)).map((l) => ({
    coord: [dates[l.index], heights[l.index]],
    value: heights[l.index],
    symbol: 'diamond',
    symbolSize: 13,
    itemStyle: { color: '#a78bfa' },
    label: {
      show: true,
      formatter: `总龙头 ${l.stock ? l.stock.name : ''}`,
      position: 'bottom',
      color: '#a78bfa',
      fontSize: 10,
      fontWeight: 'bold'
    }
  }))

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
        const led = leaderByIndex.get(i)
        const heads = []
        if (brk) {
          heads.push(`<span style="color:#ef4444;font-weight:bold">★ 首次破壁 ${brk.prevHigh}→${brk.board}</span>`)
        }
        if (led) {
          heads.push(`<span style="color:#a78bfa;font-weight:bold">◆ 周期换龙</span>`)
        }
        const lines = [heads.length ? `${r.date} ${heads.join(' ')}` : `${r.date}`]
        lines.push(`最高连板: <b>${r.maxHeight} 板</b> · ${r.stockCount} 只并列`)
        lines.push(brk
          ? `破壁股: <b>${brk.stock ? brk.stock.name : '—'}</b>（捅破 ${brk.prevHigh} 板线）`
          : `破壁线: <b>${r.ceiling} 板</b>（需 ≥ ${r.ceiling + 1} 板且新龙）`)
        lines.push(led
          ? `总龙头: <b>${led.stock ? led.stock.name : '—'}</b>（本日越过在册高度，周期确立）`
          : `总龙头: <b>${r.cycleLeader || '—'}</b>（在册 ${r.cycleTop} 板）`)
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
        markPoint: (breakPoints.length || leaderPoints.length)
          ? { data: breakPoints.concat(leaderPoints) }
          : undefined,
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
.lk.break {
  color: #ef4444;
}
.lk.leader {
  color: #a78bfa;
}
.lk.line {
  color: #6b7f95;
  letter-spacing: 1px;
}
</style>
