<template>
  <section class="height-curve">
    <div class="curve-head">
      <h3>{{ name }}
        <span class="sub" v-if="isNodeMode">近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 ·
          <i class="lk anchor">━●</i>龙头票 <i class="lk stock">━◆</i>节点票 · 虚线＝待验证 灰＝失效
          <i class="lk ring">○</i>断板 · 点线切节点</span>
        <span class="sub" v-else>近 {{ visibleCount }} 个交易日 · 共 {{ rows.length }} 天可回看 · 点任意一天切日期 · <i class="lk probe">☆</i>试探 <i class="lk break">★</i>破壁成功 <i class="lk line" :style="{ color: focusedColor }">- -</i>破壁线{{ originNote }}</span>
      </h3>
    </div>
    <el-empty v-if="!rows.length" description="暂无连板高度数据" :image-size="60" />
    <div v-else ref="chartRef" class="canvas" :style="{ height: height + 'px' }"></div>
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
 *
 * <p>节点页（传 nodeTracks）另有一种画法：每个节点两条轨迹——●龙头票（D0 之前把空间立起来的那只）
 * 与 ◆节点票（D0 之后接位的那只），一天一个点，不在梯那天留空，`connectNulls:false` 把它画成断口，
 * 于是「爬升 → 打到最高 → 断板 → 换人接位」是看出来的而不是读标签读出来的。轨迹的逐日板高与关键日
 * 全由 utils/nodeTracks 按 height-range 每天的 ladder 算好，下标按**全量** rows；本组件只管窗口切片、
 * 配色与淡出、标签避让、关键日落在窗口外时的那块牌子。这时 selected 传进来的是当前节点的 D0，
 * 竖线读作「D0 线」，琥珀色的最高连板退成背景。
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
   * 节点页专用：每只票一条逐日板高轨迹（●龙头票 / ◆节点票），由 utils/nodeTracks 算好。
   * [{id, status, d0Date, anchor:{name,code,max,data,keyIdx,endIdx}, stock:{…}}]，
   * data 与**全量** rows 同长同序、不在梯那天是 null；下标也按全量算，组件按当前窗口自己切。
   */
  nodeTracks: { type: Array, default: () => [] },
  /** 只看这一个节点的两条线（其余淡到读不出形状）；null = 全部一起看 */
  focusNode: { type: [Number, String], default: null },
  /** 每天的琥珀圆点画不画：节点页用轨迹替掉它，天梯页保持 true */
  dayPoints: { type: Boolean, default: true },
  /** 破壁虚线段与 ☆/★ 星画不画：节点页只留高度线＋两条轨迹，天梯页保持 true */
  breakLines: { type: Boolean, default: true },
  /** 画布高（px）：节点页两条轨迹要读出爬升与断口，比天梯页那条单线要高 */
  height: { type: Number, default: 200 },
  /** Y 轴下界（板）：天梯页从 2 板起（1 板没信息量），节点页传 1 才能看见首板那天起步 */
  yMin: { type: Number, default: 2 }
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

const isNodeMode = computed(() => (props.nodeTracks || []).length > 0)

/** ◆ 节点票要自己给 path（ECharts 内置菱形不够尖）；● 龙头票用内置 circle */
const MARK_PATH = 'path://M6,0 L12,6 L6,12 L0,6 Z'
const C_ANCHOR = '#60a5fa'
const C_STOCK = '#22c55e'
/** 失效不是另一种票，是没兑现的票：线留着、颜色退灰、整条压暗。 */
const C_INVALID = '#7d8ea1'
/** 断板那天的空心圈 */
const C_BREAK = '#94a3b8'

function trackColor(t, role) {
  if (t.status === '失效') return C_INVALID
  return role === 'anchor' ? C_ANCHOR : C_STOCK
}

/**
 * 两条轨迹的落位：每个节点最多两条线（●龙头票 / ◆节点票），一天一个点，
 * 不在梯那天是 null——`connectNulls:false` 就把它画成断口，爬升与断板都是看出来的。
 * 关键日不在当前窗口里的那几条不硬画，改在左上角出一行「◀ 在窗口外」的牌子。
 * 标签按像素位置排一遍队：相邻两天的标很容易压在同一个水平带上。
 * @param all 全量 rows——nodeTracks 里的下标都按它算
 * @param i0  当前窗口第一行在 all 里的下标
 * @returns {{series: Array, off: Array}}
 */
function buildTracks(all, rows, i0, yMin, yMax) {
  const cv = chartRef.value
  const W = (cv && cv.clientWidth) || 900
  const H = (cv && cv.clientHeight) || props.height
  // grid 是 {left:40, right:30, top:30, bottom:26}，像素换算必须跟它一致
  const innerW = Math.max(W - 70, 120)
  const innerH = Math.max(H - 56, 60)
  const pxPerDay = innerW / Math.max(rows.length - 1, 1)
  const n = rows.length
  const focus = props.focusNode

  const series = []
  const off = []
  const labels = []

  for (const t of props.nodeTracks || []) {
    const dim = focus != null && String(t.id) !== String(focus)
    const hot = focus != null && !dim
    // 轨迹是这张图的主角（琥珀最高板已退成背景），不聚焦时也接近满色，
    // 半透明会让爬升与断口读不清；只有被焦点淡出的那些才真的淡
    const op = dim ? 0.16 : hot ? 1 : 0.95
    for (const role of ['anchor', 'stock']) {
      const tr = t[role]
      if (!tr) continue
      const color = trackColor(t, role)
      const keyI = tr.keyIdx - i0
      const endI = tr.endIdx - i0
      // 断板＝这段连板没走到数据末尾。末尾那天在梯的票还算「在梯」，不标断
      const broken = tr.endIdx < all.length - 1
      if (!dim && (keyI < 0 || keyI >= n)) {
        off.push({
          tr, color, role,
          // 箭头是「往哪边平移才看得到」：关键日在窗口左边就 ◀，在右边（往左翻过之后）就 ▶
          dir: keyI < 0 ? '◀' : '▶',
          date: all[tr.keyIdx].date,
          board: tr.data[tr.keyIdx]
        })
      }
      // 标签密度：焦点节点两条都标；没焦点时只标节点票，12 枚一起上就糊成一片
      const wantLabel = !dim && (hot || (focus == null && role === 'stock'))
      const data = []
      for (let k = 0; k < n; k++) {
        const v = tr.data[i0 + k]
        if (v == null) { data.push(null); continue }
        const isKey = k === keyI
        const isEnd = broken && k === endI
        data.push({
          value: v,
          nodeId: t.id,
          symbol: isEnd ? 'circle' : role === 'anchor' ? 'circle' : MARK_PATH,
          symbolSize: isKey ? (role === 'anchor' ? 11 : 14) : isEnd ? 10 : role === 'anchor' ? 7 : 10,
          symbolKeepAspect: true,
          itemStyle: isEnd
            ? { color: '#1a2332', borderColor: C_BREAK, borderWidth: 2, opacity: op }
            : { color, borderColor: '#1a2332', borderWidth: 1.2, opacity: op },
          label: { show: false }
        })
        // 最高板那天往往就是断板那天（打完空间就断），这时票名+板高优先，「断」并进同一行
        if (wantLabel && (isKey || isEnd)) {
          labels.push({
            item: data[k],
            x: k * pxPerDay,
            y: ((yMax - v) / (yMax - yMin)) * innerH,
            text: isKey ? `${tr.name} ${v}板${isEnd ? ' · 断' : ''}` : '断',
            color: isKey ? color : C_BREAK,
            bold: hot,
            invalid: t.status === '失效'
          })
        }
      }
      series.push({
        id: `nodeTrack-${t.id}-${role}`,
        name: role === 'anchor' ? '龙头票' : '节点票',
        type: 'line',
        data,
        connectNulls: false,
        symbol: 'none',
        silent: dim,
        z: hot ? 6 : dim ? 2 : 4,
        lineStyle: {
          // 节点票是这张图要读的主角，比龙头票再粗一档；被焦点淡出的那些才细下去
          width: dim ? 1 : role === 'stock' ? (hot ? 3.2 : 2.6) : hot ? 2.8 : 2.1,
          color,
          opacity: op,
          type: t.status === '待验证' ? 'dashed' : 'solid'
        },
        itemStyle: { color, opacity: op },
        emphasis: { disabled: true }
      })
    }
  }

  labels.forEach((m) => {
    const cjk = (m.text.match(/[^\x00-\xff]/g) || []).length
    m.w = cjk * 10.5 + (m.text.length - cjk) * 6
    // 点贴着网格底时（退潮期掉到低板），标签朝下就压进日期刻度行，这时翻上去
    m.side = innerH - m.y >= 20 ? 1 : -1
    m.dist = 15
    m.dx = 0
  })
  // 同一天同板高的两枚（两个节点的票打到同一高度）横向错开：只挪标签，点还钉在线上
  labels.forEach((m) => {
    const same = labels.filter((z) => Math.abs(z.x - m.x) < 1 && Math.abs(z.y - m.y) < 1)
    if (same.length > 1) m.dx = (same.indexOf(m) - (same.length - 1) / 2) * 26
  })
  for (let pass = 0; pass < 5; pass++) {
    let moved = false
    for (let a = 0; a < labels.length; a++) {
      for (let b = a + 1; b < labels.length; b++) {
        const p = labels[a]
        const q = labels[b]
        const py = p.y + p.side * p.dist
        const qy = q.y + q.side * q.dist
        if (Math.abs(py - qy) < 12 && Math.abs(p.x + p.dx - (q.x + q.dx)) < (p.w + q.w) / 2) {
          p.dist += 9
          q.dist += 9
          moved = true
        }
      }
    }
    if (!moved) break
  }
  labels.forEach((m) => {
    // 上面留 20px 顶边距可用，下面不许越过网格底（再下去就是日期刻度）
    const limit = m.side < 0 ? m.y + 20 : innerH - m.y
    m.dist = Math.max(12, Math.min(m.dist, Math.max(12, limit)))
    m.item.label = {
      show: true,
      formatter: m.text,
      position: [m.dx, m.side * m.dist],
      align: 'center',
      verticalAlign: m.side < 0 ? 'bottom' : 'top',
      color: m.color,
      fontSize: 10.5,
      fontWeight: m.bold ? 700 : 400,
      opacity: m.invalid ? 0.8 : 1,
      backgroundColor: 'rgba(26,35,50,0.85)',
      padding: [1, 3],
      borderRadius: 3
    }
  })

  return { series, off }
}

/** 关键日在窗口外的：不硬画在曲线上，左上角出一行牌子说它去哪了——往左平移窗口才看得到。 */
function offscreenGraphic(off) {
  const show = off.slice(0, 3)
  const g = show.map((m, k) => ({
    type: 'text',
    left: 44,
    top: 2 + k * 13,
    silent: true,
    style: {
      text: `${m.dir} ${m.role === 'anchor' ? '龙头' : '节点票'} ${m.tr.name} ${m.board}板 · ${m.date.slice(5)} 在窗口外`,
      fill: m.color,
      fontSize: 10,
      backgroundColor: 'rgba(26,35,50,0.88)',
      padding: [1, 4],
      borderRadius: 3,
      opacity: 0.9
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
  const yMin = props.yMin
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
  // nodeTracks 里的下标一律按全量 rows 算，切完片要告诉它窗口从哪一行起
  const i0 = all.findIndex((r) => r.date === dates[0])
  const tracks = isNodeMode.value && i0 >= 0
    ? buildTracks(all, rows, i0, yMin, yMax)
    : { series: [], off: [] }

  // 选中日期竖线：必须两点式。单点 {xAxis} 的端点贴着网格底，标签会被钳进 X 轴刻度行
  // （选最左一天时实测 y182-194，与首个刻度标签正面重叠）；锚到 yMax 后恒在 y157-167。
  // 节点页传进来的 selected 就是当前节点的 D0，所以这条线在那儿读作「D0 线」，颜色也让给琥珀背景线。
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
            formatter: isNodeMode.value ? `D0 ${dates[selIdx].slice(5)}` : dates[selIdx].slice(5),
            position: 'insideEndTop',
            rotate: 0,
            color: isNodeMode.value ? '#cbd5e1' : '#fbbf24',
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
        // 节点页：标上已经写了票名与板高，浮层只补「这一天这几条轨迹各在第几板」——
        // 标签只在关键日和断板日出，其余日子的读数全靠这里
        if (isNodeMode.value) {
          const k = i0 + p.dataIndex
          const focus = props.focusNode
          const hits = []
          for (const t of props.nodeTracks || []) {
            if (focus != null && String(t.id) !== String(focus)) continue
            for (const role of ['anchor', 'stock']) {
              const tr = t[role]
              const v = tr ? tr.data[k] : null
              if (v == null) continue
              // 断板＝这段连板没走到数据末尾；末尾那天在梯的票是「还在梯」，不叫断
              hits.push({
                t, tr, role, v,
                key: tr.keyIdx === k,
                end: tr.endIdx === k && tr.endIdx < all.length - 1
              })
            }
          }
          const lines = [`<b>${r.date}</b> 全场最高 ${r.maxHeight}板`]
          hits.sort((a, b) => b.v - a.v)
          hits.slice(0, 6).forEach((h) => {
            // 带节点号：同一只票常是上一节的节点票＋下一节的龙头，光写角色会印出两行一样的字
            const tag = h.role === 'anchor' ? `龙头#${h.t.id}` : `节点票#${h.t.id}`
            // 关键日与断板日常常是同一天（打完空间就断），两个都要报，标上那句「· 断」也是这个意思
            const note = [h.key ? '关键日' : '', h.end ? '断板' : ''].filter(Boolean).join(' · ')
            lines.push(
              `<span style="color:${trackColor(h.t, h.role)}">${tag} ${h.tr.name}</span> ${h.v}板${note ? ' · ' + note : ''}`
            )
          })
          if (hits.length > 6) lines.push(`另有 ${hits.length - 6} 条在梯`)
          return lines.join('<br/>')
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
    graphic: offscreenGraphic(tracks.off),
    series: [
      {
        name: '最高连板',
        type: 'line',
        data: heights,
        smooth: 0.3,
        symbol: props.dayPoints ? 'circle' : 'none',
        symbolSize: 8,
        showAllSymbol: true,
        // 节点页这条线退成背景：主角是龙头票与节点票的两条轨迹，琥珀满色会把它们盖住
        z: isNodeMode.value ? 1 : 2,
        lineStyle: {
          width: isNodeMode.value ? 1.2 : 2.5,
          color: '#fbbf24',
          opacity: isNodeMode.value ? 0.18 : 1
        },
        itemStyle: { color: '#fbbf24', borderColor: '#1a2332', borderWidth: 2 },
        emphasis: {
          itemStyle: { borderColor: '#fbbf24', borderWidth: 2 }
        },
        areaStyle: {
          opacity: isNodeMode.value ? 0.08 : 1,
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(251,191,36,0.25)' },
            { offset: 1, color: 'rgba(251,191,36,0.02)' }
          ])
        },
        markPoint: marks.length ? { data: marks } : undefined,
        markLine: selectedLine.length ? {
          silent: true,
          symbol: 'none',
          lineStyle: {
            type: 'dashed',
            color: isNodeMode.value ? '#94a3b8' : '#fbbf24',
            width: isNodeMode.value ? 1.2 : 1.5
          },
          data: selectedLine
        } : undefined
      },
      ...lineSeries,
      // 上面按来源切的每一段各一条；这里不再有一条全量的破壁线系列
      ...tracks.series
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
    // 点轨迹上的点切节点：页脚的日期高亮是「看哪天」，这一路是「看哪个节点」，两件事两套事件。
    // 轨迹系列每节点每角色一条（id 形如 nodeTrack-918-anchor），所以认 data 上的 nodeId，不认 seriesId
    chart.on('click', (p) => {
      if (p.componentType === 'series' && p.data && p.data.nodeId != null) {
        emit('select-node', p.data.nodeId)
      }
    })
  }
  // height 是 prop 驱动的：dom 高度先由 Vue 改掉，ECharts 不会自己跟上，
  // 不 resize 就 setOption 的话，按新高度算出来的像素落位会画在旧尺寸的画布上
  chart.resize()
  chart.setOption(buildOption(), true)
}

const onResize = () => chart?.resize()

watch(() => [props.rows, props.selected, props.nodeTracks, props.focusNode, props.height, props.yMin], renderChart, { deep: true, flush: 'post' })
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
.lk.ring {
  color: #94a3b8;
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
