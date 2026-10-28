<template>
  <el-dialog
    v-model="visible"
    :title="dialogTitle"
    width="880px"
    top="5vh"
    class="stock-tape-dialog"
    @close="closeStockTape"
  >
    <div class="tape-head">
      <span class="t-code">{{ code }}</span>
      <span v-if="displayName" class="t-name">{{ displayName }}</span>
      <span v-if="shownDate" class="t-date">{{ shownDate }}</span>
      <span v-if="tape && prevClose != null" class="t-prev">
        昨收 {{ fixed(prevClose, 2) }}<i class="t-src">{{ prevCloseNote }}</i>
      </span>
      <el-button v-if="shownDate" size="small" class="t-jump" @click="goTianti">在当日天梯看</el-button>
    </div>

    <div v-if="loading" class="tape-note">分时取数中…</div>
    <div v-else-if="error" class="tape-note bad">{{ error }}</div>
    <template v-else-if="tape">
      <div v-if="!tape.available" class="tape-note">{{ tape.emptyReason }}</div>
      <div v-else ref="chartRef" class="tape-chart"></div>

      <div class="auc">
        <div class="auc-title">
          集合竞价
          <span v-if="auction && auction.sealedAtOpen === true" class="auc-seal">竞价即涨停</span>
          <span v-else-if="auction && auction.sealedAtOpen === false" class="auc-noseal">竞价未封死</span>
        </div>
        <div v-if="!auction || !auction.available" class="tape-note">{{ aucReason }}</div>
        <div v-else class="auc-grid">
          <div class="auc-cell">
            <label>竞价价</label>
            <b>{{ fixed(auction.auctionPrice, 2) }}</b>
            <span :class="pctClass(auction.pct)">{{ pctText(auction.pct) }}</span>
          </div>
          <div class="auc-cell">
            <label>竞价量</label>
            <b>{{ intText(auction.volumeHand) }}</b>
            <span class="auc-unit">手</span>
          </div>
          <div class="auc-cell">
            <label>竞价额</label>
            <b>{{ fixed(auction.amountWan, 2) }}</b>
            <span class="auc-unit">万</span>
          </div>
          <div class="auc-cell">
            <label>未匹配买量</label>
            <b>{{ intText(auction.bid1Hand) }}</b>
            <span class="auc-unit">手 @{{ fixed(auction.bid1Price, 2) }}</span>
          </div>
          <div class="auc-cell">
            <label>未匹配卖量</label>
            <b>{{ intText(auction.ask1Hand) }}</b>
            <span class="auc-unit">手 @{{ fixed(auction.ask1Price, 2) }}</span>
          </div>
          <div class="auc-cell">
            <label>抓取时刻</label>
            <b class="auc-time">{{ captureText }}</b>
          </div>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, watch, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { useStockTape, closeStockTape } from '../utils/stockTape'

const { visible, code, name, date, tape, loading, error } = useStockTape()
const router = useRouter()
const chartRef = ref(null)
let chart = null

const UP = '#ef4444'
const DOWN = '#3b82f6'
/**
 * 分时轴上打出来的整点/半点。
 * 13:00 不在名单里：类目轴上 11:30 与 13:00 是紧挨着的两根，两个标签会叠成一片糊字，
 * 所以午休由 11:30 那一个刻度自己说（formatter 里写成「11:30/13:00」）。
 * 末端留 15:30 而不是 15:00：上游的分钟序列真会给到 15:30（实测 002909 那天 15:06–15:30 有 25 根，
 * 全是零成交的盘后壳子）。那些根要画——把数据截掉是假装没发生——但没有这个标签，
 * 曲线右端多出来的一平段就没法读。
 */
const TICKS = ['09:30', '10:00', '10:30', '11:00', '11:30', '13:30', '14:00', '14:30', '15:00', '15:30']
const LUNCH_LABEL = '11:30/13:00'

const displayName = computed(() => (tape.value && tape.value.name) || name.value || '')
const shownDate = computed(() => (tape.value && tape.value.tradeDate) || date.value || '')
const prevClose = computed(() => num(tape.value && tape.value.prevClose))
const auction = computed(() => tape.value && tape.value.auction)

const dialogTitle = computed(() => (displayName.value ? `${displayName.value} · 分时` : '分时 · 竞价'))

/**
 * 昨收的来路要在界面上说出来：三条路的精度不一样。
 * 「反推」那条带分位级误差，「日K」那条是前复权——不说，读的人就以为它是源给的原值。
 */
const prevCloseNote = computed(() => {
  const s = tape.value && tape.value.prevCloseSource
  if (s === 'AUCTION') return '（竞价快照原值）'
  if (s === 'MARKET_STOCK') return '（当日收盘÷(1+涨幅) 反推）'
  if (s === 'DAILY_BAR') return '（库内日K前收，前复权）'
  return ''
})

const aucReason = computed(() => {
  if (!auction.value) return '这一天没有竞价快照。'
  return auction.value.emptyReason || '这一天没有竞价快照。'
})

/** 抓取时刻只到分：秒没有意义（排班是 09:26 那一趟），而分必须看得见——它是"到底在不在窗口里"的证据。 */
const captureText = computed(() => {
  const raw = auction.value && auction.value.capturedAt
  if (!raw) return '—'
  return String(raw).replace('T', ' ').slice(5, 16)
})

function num(v) {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

function fixed(v, digits) {
  const n = num(v)
  return n === null ? '—' : n.toFixed(digits)
}

function intText(v) {
  const n = num(v)
  return n === null ? '—' : n.toLocaleString('zh-CN')
}

function signed(v) {
  const n = num(v)
  if (n === null) return '—'
  return (n > 0 ? '+' : '') + n.toFixed(2)
}

/** 缺分母就没有涨幅这一维：印「—」而不是「—%」，后者看着像算出来的 0。 */
function pctText(v) {
  return num(v) === null ? '—' : signed(v) + '%'
}

function pctClass(v) {
  const n = num(v)
  if (n === null) return ''
  return n > 0 ? 'up' : n < 0 ? 'down' : ''
}

function goTianti() {
  router.push({ path: '/tianti', query: { date: shownDate.value, code: code.value } })
}

function buildOption() {
  const points = (tape.value && tape.value.points) || []
  if (!points.length) return {}
  const times = points.map((p) => p.time)
  const prices = points.map((p) => num(p.price))
  const vwaps = points.map((p) => num(p.vwap))
  const vols = points.map((p, i) => ({
    value: num(p.volumeHand),
    itemStyle: {
      // 这一分钟的量按"比上一分钟涨还是跌"着色：分时量柱的惯例口径，
      // 与"比昨收涨还是跌"是两件事，后者已经由价格线自己承担了
      color: i === 0 || prices[i] == null || prices[i - 1] == null
        ? 'rgba(136,153,166,0.5)'
        : prices[i] >= prices[i - 1]
          ? 'rgba(239,68,68,0.55)'
          : 'rgba(59,130,246,0.5)'
    }
  }))

  const lines = []
  if (prevClose.value != null) {
    lines.push({
      yAxis: prevClose.value,
      lineStyle: { color: '#8899a6', type: 'dashed', width: 1 },
      label: { formatter: `昨收 ${fixed(prevClose.value, 2)}`, position: 'insideStartTop', color: '#8899a6', fontSize: 10 }
    })
  }
  const limitUp = auction.value && num(auction.value.limitUpPrice)
  // 涨停线只在票真的贴着它走的时候才进量程。002909 那天全天 5.44–5.58，涨停 5.90：
  // 把它拉进来，价格带就被压成画布底部的一条细线，日内形状全没了——而那只票根本不需要参照涨停。
  // 判据用「离数据上沿还有几个数据带宽」：两个带宽以内算贴着，够得着就画。
  const dataLo = Math.min(...prices.filter((v) => v != null))
  const dataHi = Math.max(...prices.filter((v) => v != null))
  const limitUpInView = limitUp != null && limitUp <= dataHi + 2 * Math.max(dataHi - dataLo, dataHi * 0.005)
  if (limitUp != null) {
    lines.push({
      yAxis: limitUp,
      lineStyle: { color: UP, type: 'dotted', width: 1 },
      label: { formatter: '涨停价', position: 'insideEndTop', color: UP, fontSize: 10 }
    })
  }

  // 量程贴着数据，但昨收线必须在视野里：不然"红还是绿"这件事在这张图上读不出来
  const inView = prices.filter((v) => v != null).concat(
    prevClose.value != null ? [prevClose.value] : [],
    limitUpInView ? [limitUp] : []
  )
  const lo = Math.min(...inView)
  const hi = Math.max(...inView)
  const pad = Math.max((hi - lo) * 0.06, hi * 0.001)

  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a2332',
      borderColor: '#2d3748',
      textStyle: { color: '#e1e8ed', fontSize: 12 },
      formatter: (params) => {
        const i = params[0]?.dataIndex ?? 0
        const p = points[i] || {}
        const row = (label, value) => `${label}: <b>${value}</b>`
        const out = [`${p.time ?? ''}`]
        out.push(row('价', fixed(p.price, 2)) + ` <span class="${pctClass(p.pct)}">${pctText(p.pct)}</span>`)
        out.push(row('均价', fixed(p.vwap, 3)))
        out.push(row('量', `${intText(p.volumeHand)} 手`))
        out.push(`额: <b>${fixed(p.amountWan, 2)}</b> 万`)
        return out.join('<br/>')
      }
    },
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: [
      { left: 58, right: 18, top: 28, height: '56%' },
      { left: 58, right: 18, top: '76%', height: '15%' }
    ],
    xAxis: [
      {
        type: 'category',
        data: times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#2d3748' } },
        axisTick: { show: false },
        axisLabel: { show: false }
      },
      {
        type: 'category',
        gridIndex: 1,
        data: times,
        boundaryGap: false,
        axisLine: { lineStyle: { color: '#2d3748' } },
        axisTick: { show: false },
        // 267 个点全印标签会糊成一条；只留整点/半点，午休那一格改印两头
        axisLabel: {
          color: '#8899a6',
          fontSize: 10,
          interval: (index) => TICKS.indexOf(times[index]) >= 0,
          formatter: (v) => (v === '11:30' ? LUNCH_LABEL : v)
        }
      }
    ],
    yAxis: [
      {
        type: 'value',
        min: +(lo - pad).toFixed(3),
        max: +(hi + pad).toFixed(3),
        splitLine: { lineStyle: { color: '#2d3748' } },
        axisLabel: { color: '#8899a6', fontSize: 10, formatter: (v) => v.toFixed(2) }
      },
      {
        type: 'value',
        gridIndex: 1,
        name: '手',
        nameTextStyle: { color: '#6e7681', fontSize: 10 },
        splitNumber: 2,
        splitLine: { show: false },
        axisLabel: { color: '#6e7681', fontSize: 10 }
      }
    ],
    series: [
      {
        name: '价格',
        type: 'line',
        data: prices,
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.6, color: '#e1e8ed' },
        z: 4,
        markLine: { silent: true, symbol: 'none', data: lines }
      },
      {
        name: '均价',
        type: 'line',
        data: vwaps,
        showSymbol: false,
        connectNulls: false,
        lineStyle: { width: 1.2, color: '#fbbf24' },
        z: 3
      },
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: vols,
        barWidth: '62%'
      }
    ]
  }
}

function render() {
  if (!chartRef.value) return
  // 换一只没有分时的票时，图的那块 DOM 会被空态替掉；再换回有分时的票就是一块新 div。
  // echarts 实例还挂在旧的、已经离开文档的节点上，不重init 就等于画在看不见的地方。
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption(buildOption(), true)
  chart.resize()
}

/**
 * 容器跟着弹窗一起出现，而 el-dialog 的内容（含那段过渡的宽度变化）不由我们的响应式数据驱动：
 * 只 watch 数据的话，watcher 跑在内容挂载之前，那一次 render() 拿到的是 null，
 * 之后再也没有什么变化来叫它第二次——实测就是这样画出一张空图。
 * 所以盯着 DOM 本身：容器一出现、一变色（弹窗打开的过渡、窄屏换行都算）就重画。
 */
let resizeObserver = null
watch(chartRef, (el) => {
  if (resizeObserver) resizeObserver.disconnect()
  if (!el) return
  resizeObserver = new ResizeObserver(render)
  resizeObserver.observe(el)
})

// 数据到而容器尺寸没变时不会触发上面那个观察者，所以取数本身也要重画一次
watch([tape, prevClose], render)

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style>
/* el-dialog teleport 到 body，scoped 够不到面板；深色底由 App.vue 的全站 el-dialog 规则给，
   这里只补分时自己的那点长相。 */
.stock-tape-dialog .tape-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin: -6px 0 8px;
  font-size: 12px;
  color: #8899a6;
}
.stock-tape-dialog .t-code { font-family: ui-monospace, Menlo, monospace; color: #6b7c8c; }
.stock-tape-dialog .t-name { font-size: 14px; font-weight: 600; color: #e1e8ed; }
.stock-tape-dialog .t-jump { margin-left: auto; }
.stock-tape-dialog .t-prev { color: #9fb2c6; }
.stock-tape-dialog .t-src { font-style: normal; color: #6b7c8c; margin-left: 4px; }
.stock-tape-dialog .tape-chart { width: 100%; height: 320px; }
.stock-tape-dialog .tape-note {
  padding: 14px 2px;
  font-size: 12px;
  line-height: 1.7;
  color: #8899a6;
}
.stock-tape-dialog .tape-note.bad { color: #f87171; }

.stock-tape-dialog .auc {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #2a3a52;
}
.stock-tape-dialog .auc-title {
  font-size: 12px;
  color: #9fb2c6;
  margin-bottom: 6px;
}
.stock-tape-dialog .auc-seal {
  margin-left: 8px;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
  color: #ef4444;
  background: rgba(239, 68, 68, 0.14);
  border: 1px solid rgba(239, 68, 68, 0.35);
}
.stock-tape-dialog .auc-noseal {
  margin-left: 8px;
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 11px;
  color: #8899a6;
  background: rgba(136, 153, 166, 0.12);
  border: 1px solid rgba(136, 153, 166, 0.28);
}
.stock-tape-dialog .auc-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 12px;
}
.stock-tape-dialog .auc-cell {
  background: #16202e;
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 12px;
  color: #cbd5e0;
}
.stock-tape-dialog .auc-cell label {
  display: block;
  font-size: 11px;
  color: #6b7c8c;
  margin-bottom: 2px;
}
.stock-tape-dialog .auc-cell b { font-size: 14px; color: #e1e8ed; margin-right: 6px; }
.stock-tape-dialog .auc-cell.auc-time { font-variant-numeric: tabular-nums; }
.stock-tape-dialog .auc-unit { color: #6b7c8c; }
.stock-tape-dialog .up { color: #ef4444; }
.stock-tape-dialog .down { color: #3b82f6; }

@media (max-width: 768px) {
  /* 竞价六格在 390 上一行三格每格只剩 108px，「未匹配买量」的标签会被压成两行、
     数字与单位错位——窄屏改一行两格，读得清比省两行高度重要 */
  .stock-tape-dialog .auc-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
