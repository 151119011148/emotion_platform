<template>
  <div class="dashboard">
    <div class="header-bar">
      <div class="header-left">
        <h1>情绪温度</h1>
        <div class="header-meta">
          <span class="date">{{ headerDate }}</span>
          <span v-if="isStale" class="stale-tag">非当日</span>
        </div>
      </div>
      <div class="header-right">
        <div class="temperature-display" :class="tempClass">
          <span class="temp-value">{{ currentTemp }}</span>
          <span class="temp-unit">°</span>
        </div>
        <div v-if="tempDrift" class="temp-drift"
          title="这条记录还是旧引擎落的分，没按五维重算；跑一次 recalc-all 就与页头一致（± 也在对齐后才显示）">
          落库 {{ recordTempText }}（未重算）
        </div>
        <div class="stage-badge" :style="{ background: stageColor }">
          {{ currentStage || '暂无数据' }}
          <small v-if="currentLabel">{{ currentLabel }}</small>
        </div>
        <div class="delta" v-if="delta !== null">
          <span :class="delta > 0 ? 'up' : delta < 0 ? 'down' : ''">
            {{ delta > 0 ? '↑' : delta < 0 ? '↓' : '→' }}
            {{ Math.abs(delta).toFixed(1) }}
          </span>
        </div>
      </div>
    </div>

    <div v-if="loadErrorShort" class="load-error">
      记录没读回来，下面的"暂无数据 / 未评"是读不到、不是真的没录：{{ loadErrorShort }}
      <span class="load-error-hint">若这句提到 Unknown column，说明库还没跟上 schema.sql（含五维那一步迁移）。</span>
    </div>

    <div class="chart-section">
      <div class="chart-controls">
        <el-radio-group v-model="days" size="small" @change="loadCurve">
          <el-radio-button :value="20">近20日</el-radio-button>
          <el-radio-button :value="60">近60日</el-radio-button>
        </el-radio-group>
      </div>
      <TemperatureChart :data="curveData" />
    </div>

    <IndicatorCards :record="headRecord" />

    <div class="bottom-row">
      <StageLocator :stage="currentStage" :direction="headRecord?.stageDirection" :record="headRecord" />
      <StageAdvice :advice="advice" :record="headRecord" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { recordApi } from '../api/modules'
import { useScoringStore } from '../stores/scoring'
import { stageColorOf, seqOnly } from '../utils/stages'
import TemperatureChart from '../components/TemperatureChart.vue'
import IndicatorCards from '../components/IndicatorCards.vue'
import StageLocator from '../components/StageLocator.vue'
import StageAdvice from '../components/StageAdvice.vue'

// 本地日期，不能用 toISOString()：那是 UTC，00:00–07:59 会算成前一天
const todayStr = new Date().toLocaleDateString('en-CA')
const days = ref(20)
const headRecord = ref(null)
const curveData = ref({ dates: [], temperatures: [], stages: [] })
const advice = ref(null)
const loadError = ref('')
const scoring = useScoringStore()

/** MyBatis 的报错整串是带换行的堆栈，页面上只要中间那句 Cause；取不到就截断兜底。 */
const loadErrorShort = computed(() => {
  const raw = loadError.value
  if (!raw) return ''
  const m = raw.match(/(?:Cause:|SQLException:|SQLSyntaxErrorException:)\s*(.+)/)
  const line = (m ? m[1] : raw).split(/\r?\n/)[0].trim()
  return line.length > 160 ? line.slice(0, 160) + '…' : line
})

function formatDate(date) {
  if (!date) return ''
  // 补 T00:00:00 强制按本地时区解析，否则 new Date('2026-09-04') 走 UTC 会少一天
  return new Date(date + 'T00:00:00').toLocaleDateString('zh-CN')
}

// 页头日期跟着记录走：挂墙上时间会让人误以为看的是今天
const headerDate = computed(() => formatDate(headRecord.value?.tradeDate || todayStr))
const isStale = computed(() => !!headRecord.value && headRecord.value.tradeDate !== todayStr)

/** 页头温度：优先现算总分（与五维卡、复盘页同源），现算没到位才回落库 temperature。 */
const shownTemp = computed(() => {
  const det = scoring.detail
  if (det && det.total != null) return Number(det.total)
  const raw = headRecord.value?.temperature
  return raw == null ? null : Number(raw)
})

/** 落库值与现算值不一致 = 这条记录还没用新引擎重算过（要跑 recalc-all）。 */
const tempDrift = computed(() => {
  const r = headRecord.value?.temperature
  if (r == null || !scoring.detail || scoring.detail.total == null) return false
  return Math.abs(Number(r) - Number(scoring.detail.total)) > 0.05
})
const recordTempText = computed(() => {
  const r = headRecord.value?.temperature
  return r == null ? '—' : Number(r).toFixed(1)
})

const currentTemp = computed(() => {
  const t = shownTemp.value
  // 0 是一个真实的读数（冰点），不能和"没读数"共用一个 falsy 分支
  if (t == null || Number.isNaN(t)) return '--'
  return t.toFixed(1)
})

const currentStage = computed(() => headRecord.value?.stage || '')
const delta = computed(() => {
  // 页头显示现算值时，落库的 prev_temperature 与它不同源，相减出来的 ± 是假信号
  if (tempDrift.value) return null
  const r = headRecord.value
  if (!r || r.temperature == null || r.prevTemperature == null) return null
  return Number(r.temperature) - Number(r.prevTemperature)
})

const tempClass = computed(() => {
  const t = shownTemp.value
  if (t === null || t === undefined || Number.isNaN(t)) return ''
  if (t < 0) return 'sub-zero'
  if (t <= 15) return 'cold'
  if (t <= 35) return 'cool'
  if (t <= 55) return 'warm'
  if (t <= 80) return 'hot'
  return 'burning'
})

const stageColor = computed(() => stageColorOf(currentStage.value))

// 子段标签只在后端算得出来（它问的是"这是第几个退潮段"，答案在更早的记录里），
// 所以这里只从曲线里取，不在前端抄一份中文数字表。取不到就只显示主阶段名。
const currentLabel = computed(() => {
  const iso = headRecord.value?.tradeDate
  const i = iso ? (curveData.value.isoDates || []).indexOf(iso) : -1
  const label = (i >= 0 && curveData.value.labels) ? curveData.value.labels[i] : ''
  return seqOnly(currentStage.value, label)
})

async function loadHead() {
  loadError.value = ''
  try {
    const res = await recordApi.getToday()
    if (res.data) {
      headRecord.value = res.data
      return
    }
  } catch (e) { /* 当日无数据或这次没读回来，往下回落 */ }
  // 周末 / 节假日 / 当天还没复盘：回落到最近一条。
  // 不回落的话这一整块全是 "--"，而下面的建议卡片（后端自带回落）却有内容，页面自相矛盾。
  try {
    const res = await recordApi.getLatest(1)
    headRecord.value = (res.data && res.data[0]) || null
  } catch (e) {
    // 红条会自己闪没，所以把原因留在页面上：库里少列（schema 没跟上）和"确实还没录"
    // 长得一模一样——都是一屏"暂无数据"，但只有前者需要你去跑迁移。
    headRecord.value = null
    loadError.value = e?.message || '记录读取失败'
  }
}

async function loadCurve() {
  try {
    const res = await recordApi.getCurve(days.value)
    curveData.value = res.data
  } catch (e) { /* ignore */ }
}

// 卡片子分跟着头部那条记录的日期现算：改一 sub 权重刷新即见效，不用 recalc-all
async function loadDay() {
  const date = headRecord.value?.tradeDate
  if (!date) {
    scoring.detail = null
    scoring.detailDate = null
    return
  }
  await scoring.loadDetail(date, true)
}

async function loadAdvice() {
  try {
    const res = await recordApi.getAdvice()
    advice.value = res.data
  } catch (e) { /* ignore */ }
}

onMounted(() => {
  scoring.load()
  loadHead().then(loadDay)
  loadCurve()
  loadAdvice()
})
</script>

<style scoped>
.dashboard {
  max-width: 1200px;
  margin: 0 auto;
}
.header-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  /* 侧边栏占宽时页头右侧会被裁掉，允许整体换行比溢出好 */
  flex-wrap: wrap;
  gap: 12px 24px;
  margin-bottom: 24px;
  padding: 20px 24px;
  background: #1a2332;
  border-radius: 12px;
}
.header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.header-left h1 {
  margin: 0;
  font-size: 20px;
  color: #e1e8ed;
  white-space: nowrap;
}
.header-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.date {
  color: #8899a6;
  font-size: 14px;
  white-space: nowrap;
}
.stale-tag {
  padding: 2px 8px;
  border: 1px solid #8899a6;
  border-radius: 10px;
  color: #8899a6;
  font-size: 12px;
  white-space: nowrap;
  flex-shrink: 0;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.temperature-display {
  display: flex;
  align-items: baseline;
}
.temp-value {
  font-size: 48px;
  font-weight: 700;
}
.temp-unit {
  font-size: 24px;
  color: #8899a6;
}
.temp-drift {
  font-size: 11px;
  color: #b45309;
  cursor: help;
}
.cold .temp-value { color: #3b82f6; }
/* 每维 -1 下限之后温度可以是负数：跌破 0 和"低但还在 0 以上"不是一回事 */
.sub-zero .temp-value { color: #1e40af; }
.cool .temp-value { color: #60a5fa; }
.warm .temp-value { color: #f59e0b; }
.hot .temp-value { color: #f97316; }
.burning .temp-value { color: #ef4444; }

.stage-badge {
  padding: 8px 16px;
  border-radius: 20px;
  color: white;
  font-weight: 600;
  font-size: 14px;
  text-align: center;
}
/* 主阶段还是主角，段号只当注脚：同一行放会让徽章宽到把页头挤换行 */
.stage-badge small {
  display: block;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.85;
  margin-top: 2px;
}
.delta {
  font-size: 18px;
  font-weight: 600;
}
.delta .up { color: #ef4444; }
.delta .down { color: #3b82f6; }

.chart-section {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.load-error {
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 10px;
  background: rgba(127, 29, 29, 0.25);
  border: 1px solid #b91c1c;
  color: #fecaca;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.load-error-hint {
  display: block;
  margin-top: 4px;
  color: #fbbf24;
}
.chart-controls {
  margin-bottom: 12px;
}

.bottom-row {
  display: grid;
  /* 中文的 min-content 是一个字宽，必须用 minmax(0,..) 否则列会被压成竖排单字 */
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
  margin-top: 20px;
}
</style>
