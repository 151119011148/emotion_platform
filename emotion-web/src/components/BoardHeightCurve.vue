<template>
  <section class="height-curve">
    <div class="curve-head">
      <h3>{{ name }}
        <span class="sub" v-if="isNodeMode">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 ·
          <i class="lk anchor">▼</i>锚定龙头 <i class="lk stock">◆</i>节点票 ·
          实心＝有效 虚线＝待验证 灰＝失效 · 点标切节点</span>
        <span class="sub" v-else>近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 点任意一天切日期 · <i class="lk probe">☆</i>试探 <i class="lk break">★</i>破壁成功 <i class="lk line" :style="{ color: focusedColor }">- -</i>破壁线{{ originNote }}</span>
      </h3>
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
  zoomGroup: { type: String, default: '' },
  /**
   * 节点页专用：把曲线上的点换成「锚定龙头 → 节点票」。
   * [{id, kind:'anchor'|'stock', date, board, name, code, status}]，日期不在窗口里的由调用方滤掉。
   */
  nodeMarks: { type: Array, default: () => [] },
  /** 只看这一个节点的标（其余淡到读不出形状）；null = 全部一起看 */
  focusNode: { type: [Number, String], default: null },
  /** 每天的琥珀圆点画不画：节点页用节点标替掉它，天梯页保持 true */
  dayPoints: { type: Boolean, default: true },
  /** 破壁虚线段与 ☆/★ 星画不画：节点页只留高度线＋节点标，天梯页保持 true */
  breakLines: { type: Boolean, default: true }
})
const emit = defineEmits(['select', 'select-node'])

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

const isNodeMode = computed(() => (props.nodeMarks || []).length > 0)

/** ECharts 内置只有 triangle 朝上，接位那枚要朝下，两枚都自己给 path。 */
const MARK_PATH = {
  anchor: 'path://M0,0 L12,0 L6,9.5 Z',
  stock: 'path://M6,0 L12,6 L6,12 L0,6 Z'
}
const MARK_COLOR = { anchor: '#60a5fa', stock: '#22c55e' }
/** 失效不是另一种票，是没兑现的票：形状留着、颜色退灰、整枚压暗。 */
const MARK_INVALID = '#7d8ea1'

function markColor(m) {
  return m.status === '失效' ? MARK_INVALID : MARK_COLOR[m.kind]
}

/**
 * 节点标落位：日期不在当前窗口里的那几枚不硬画，改在左上角出一行「◀ 在窗口外」的牌子。
 * 标签按像素位置排一遍队：龙头在上、票在下，相邻两天的标很容易压在同一个水平带上。
 * @returns {{on: Array, off: Array}} on 带像素坐标，off 只带原始字段
 */
function buildNodeMarks(rows, dates, yMin, yMax) {
  const cv = chartRef.value
  const W = (cv && cv.clientWidth) || 900
  const H = (cv && cv.clientHeight) || 200
  // grid 是 {left:40, right:30, top:30, bottom:26}，像素换算必须跟它一致
  const innerW = Math.max(W - 70, 120)
  const innerH = Math.max(H - 56, 60)
  const pxPerDay = innerW / Math.max(rows.length - 1, 1)

  const on = []
  const off = []
  for (const m of props.nodeMarks || []) {
    const i = dates.indexOf(m.date)
    if (i < 0) { off.push(m); continue }
    on.push({
      ...m, i,
      x: i * pxPerDay,
      y: ((yMax - m.board) / (yMax - yMin)) * innerH
    })
  }

  on.forEach((m) => {
    // 同一天同板高的两枚标（上一节的票＝下一节的龙头）横向错开，叠一起读不出形状
    const same = on.filter((z) => z.i === m.i && z.board === m.board)
    m.dx = same.length > 1 ? (m.kind === 'anchor' ? -8 : 8) : 0
    m.dim = props.focusNode != null && String(m.id) !== String(props.focusNode)
    m.text = `${m.kind === 'anchor' ? '锚' : '票'} ${m.name} ${m.board}板`
    const cjk = (m.text.match(/[^\x00-\xff]/g) || []).length
    m.w = cjk * 10.5 + (m.text.length - cjk) * 6
    // 退潮期票掉到 2 板 = Y 轴底，标签朝下就压进日期刻度行，这时翻上去
    m.side = m.kind === 'anchor' ? -1 : (innerH - m.y >= 20 ? 1 : -1)
    m.dist = 13
  })

  for (let pass = 0; pass < 5; pass++) {
    let moved = false
    for (let a = 0; a < on.length; a++) {
      for (let b = a + 1; b < on.length; b++) {
        const p = on[a]
        const q = on[b]
        const py = p.y + p.side * p.dist
        const qy = q.y + q.side * q.dist
        if (Math.abs(py - qy) < 12 && Math.abs(p.x - q.x) < (p.w + q.w) / 2) {
          p.dist += 9
          q.dist += 9
          moved = true
        }
      }
    }
    if (!moved) break
  }
  on.forEach((m) => {
    // 上面留 20px 顶边距可用，下面不许越过网格底（再下去就是日期刻度）
    const limit = m.side < 0 ? m.y + 20 : innerH - m.y
    m.dist = Math.max(12, Math.min(m.dist, Math.max(12, limit)))
  })
  return { on, off }
}

/** 窗口外的标：不硬画在曲线上，左上角出一行牌子说它去哪了——往左平移窗口才看得到。 */
function offscreenGraphic(off) {
  const show = off.slice(0, 3)
  const g = show.map((m, k) => ({
    type: 'text',
    left: 44,
    top: 2 + k * 13,
    silent: true,
    style: {
      text: `◀ ${m.kind === 'anchor' ? '锚' : '票'} ${m.name} ${m.board}板 · ${m.date.slice(5)} 在窗口外`,
      fill: markColor(m),
      fontSize: 10,
      backgroundColor: 'rgba(26,35,50,0.88)',
      padding: [1, 4],
      borderRadius: 3,
      opacity: props.focusNode != null && String(m.id) !== String(props.focusNode) ? 0.25 : 0.9
    }
  }))
  if (off.length > show.length) {
    g.push({
      type: 'text',
      left: 44,
      top: 2 + show.length * 13,
      silent: true,
      style: { text: `另有 ${off.length - show.length} 枚在窗口外`, fill: '#8899a6', fontSize: 10 }
    })
  }
  return g
}

/**
 * 节点标两枚 + 接位连线。连线的笔色放在闭包数组里按 dataIndex 取：
 * custom 系列的 api.style() 会把 itemStyle 的 color 当填充、边框另算，
 * 而这条线要的正是「stroke＋虚线＋透明度」三样，绕开那层映射少一类意外。
 */
function nodeMarkSeries(rows, dates, yMin, yMax) {
  const { on, off } = buildNodeMarks(rows, dates, yMin, yMax)

  const data = on.map((m) => ({
    value: [dates[m.i], m.board],
    nodeId: m.id,
    nodeKind: m.kind,
    info: m.info || null,
    symbol: MARK_PATH[m.kind],
    symbolSize: m.kind === 'anchor' ? 13 : 12,
    symbolOffset: [m.dx, 0],
    symbolKeepAspect: true,
    itemStyle: {
      color: m.status === '待验证' ? '#12203a' : markColor(m),
      borderColor: markColor(m),
      borderWidth: 1.8,
      borderType: m.status === '待验证' ? 'dashed' : 'solid',
      opacity: (m.dim ? 0.16 : 1) * (m.status === '失效' ? 0.55 : 1)
    },
    label: {
      show: !m.dim,
      formatter: m.text,
      position: [0, m.side * m.dist],
      align: 'center',
      verticalAlign: m.side < 0 ? 'bottom' : 'top',
      color: markColor(m),
      fontSize: 10.5,
      fontWeight: props.focusNode != null && String(m.id) === String(props.focusNode) ? 700 : 400,
      opacity: m.status === '失效' ? 0.8 : 1,
      backgroundColor: 'rgba(26,35,50,0.85)',
      padding: [1, 3],
      borderRadius: 3
    }
  }))

  const links = []
  on.forEach((a) => {
    if (a.kind !== 'anchor') return
    const b = on.find((z) => z.id === a.id && z.kind === 'stock')
    if (!b) return
    links.push({
      value: [a.i, a.board, b.i, b.board],
      color: a.status === '失效' || b.status === '失效' ? MARK_INVALID : '#3f5f86',
      width: a.dim ? 1 : 1.2,
      opacity: a.dim ? 0.2 : 0.75
    })
  })
  const linkSeries = {
    id: 'nodeLink',
    type: 'custom',
    name: '接位连线',
    z: 4,
    silent: true,
    tooltip: { show: false },
    encode: { x: [0, 2], y: [1, 3] },
    data: links,
    renderItem(params, api) {
      const L = links[params.dataIndex]
      if (!L) return null
      const p = api.coord([api.value(0), api.value(1)])
      const q = api.coord([api.value(2), api.value(3)])
      const mx = (p[0] + q[0]) / 2
      const my = Math.min(p[1], q[1]) - 24
      return {
        type: 'group',
        children: [{
          type: 'bezierCurve',
          shape: { x1: p[0], y1: p[1], cpx1: mx, cpy1: my, cpx2: mx, cpy2: my, x2: q[0], y2: q[1] },
          style: { stroke: L.color, lineWidth: L.width, lineDash: [2, 4], opacity: L.opacity, fill: 'none' }
        }]
      }
    }
  }

  return {
    graphic: offscreenGraphic(off),
    series: [{ id: 'nodeMark', type: 'scatter', name: '节点标', z: 6, data }, linkSeries]
  }
}

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
  const marks = props.breakLines ? probePoints.concat(breakPoints) : []
  const nodeMarks = isNodeMode.value ? nodeMarkSeries(rows, dates, yMin, yMax) : { series: [], graphic: [] }

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
  const lineRuns = props.breakLines ? readLineRuns(rows) : []
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
        // 节点页：卡片那六格里剩下的读数只有这里放得下，标本身已经说了谁、几板、哪天
        const hit = params.find((q) => q.seriesId === 'nodeMark' && q.data && q.data.info)
        if (hit) {
          const i = hit.data.info
          const ls = [`<b>${r.date}</b> 节点 #${hit.data.nodeId}`]
          ls.push([i.status, i.typeLabel, i.theme].filter(Boolean).join(' · '))
          if (i.d0) ls.push(`D0 ${i.d0}`)
          ls.push(`D0 情绪 ${i.score != null ? i.score + ' 分' : '未记'}${i.cycle ? ' · ' + i.cycle : ''}`)
          if (i.verify) ls.push(i.verify)
          if (i.pool) ls.push(`候选池 ${i.pool}`)
          return ls.join('<br/>')
        }
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
    graphic: nodeMarks.graphic,
    series: [
      {
        name: '最高连板',
        type: 'line',
        data: heights,
        smooth: 0.3,
        symbol: props.dayPoints ? 'circle' : 'none',
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
      ...lineSeries,
      // 上面按来源切的每一段各一条；这里不再有一条全量的破壁线系列
      ...nodeMarks.series
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
    // 点标切节点：页脚的日期高亮是「看哪天」，这一路是「看哪个节点」，两件事两套事件
    chart.on('click', (p) => {
      if (p.componentType === 'series' && p.seriesId === 'nodeMark' && p.data) {
        emit('select-node', p.data.nodeId)
      }
    })
  }
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

watch(() => [props.rows, props.selected, props.nodeMarks, props.focusNode], renderChart, { deep: true, flush: 'post' })
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
.lk.anchor {
  color: #60a5fa;
}
.lk.stock {
  color: #22c55e;
}
.lk.break {
  color: #ef4444;
}
.lk.line {
  color: #6b7f95;
  letter-spacing: 1px;
}
</style>
