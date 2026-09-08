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

    <div class="chart-section">
      <div class="chart-controls">
        <el-radio-group v-model="days" size="small" @change="loadCurve">
          <el-radio-button :value="20">近20日</el-radio-button>
          <el-radio-button :value="60">近60日</el-radio-button>
        </el-radio-group>
      </div>
      <TemperatureChart :data="curveData" />
    </div>

    <IndicatorCards :record="headRecord" :details="details" :tiers="tiers" :anchor="anchorDay" />

    <div class="bottom-row">
      <StageLocator :stage="currentStage" :direction="headRecord?.stageDirection" :record="headRecord" />
      <StageAdvice :advice="advice" :record="headRecord" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { recordApi, marketApi, anchorApi } from '../api/modules'
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
const details = ref(null)
const tiers = ref(null)
const anchorDay = ref(null)

function formatDate(date) {
  if (!date) return ''
  // 补 T00:00:00 强制按本地时区解析，否则 new Date('2026-09-04') 走 UTC 会少一天
  return new Date(date + 'T00:00:00').toLocaleDateString('zh-CN')
}

// 页头日期跟着记录走：挂墙上时间会让人误以为看的是今天
const headerDate = computed(() => formatDate(headRecord.value?.tradeDate || todayStr))
const isStale = computed(() => !!headRecord.value && headRecord.value.tradeDate !== todayStr)

const currentTemp = computed(() => {
  const raw = headRecord.value?.temperature
  // 0 是一个真实的读数（冰点），不能和"没读数"共用一个 falsy 分支
  if (raw == null) return '--'
  return Number(raw).toFixed(1)
})

const currentStage = computed(() => headRecord.value?.stage || '')
const delta = computed(() => {
  const r = headRecord.value
  if (!r || r.temperature == null || r.prevTemperature == null) return null
  return Number(r.temperature) - Number(r.prevTemperature)
})

const tempClass = computed(() => {
  const raw = headRecord.value?.temperature
  if (raw === null || raw === undefined) return ''
  const t = Number(raw)
  if (Number.isNaN(t)) return ''
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
  try {
    const res = await recordApi.getToday()
    if (res.data) {
      headRecord.value = res.data
      return
    }
  } catch (e) { /* 当日无数据，往下回落 */ }
  // 周末 / 节假日 / 当天还没复盘：回落到最近一条。
  // 不回落的话这一整块全是 "--"，而下面的建议卡片（后端自带回落）却有内容，页面自相矛盾。
  try {
    const res = await recordApi.getLatest(1)
    headRecord.value = (res.data && res.data[0]) || null
  } catch (e) { /* 一条都没有 */ }
}

async function loadCurve() {
  try {
    const res = await recordApi.getCurve(days.value)
    curveData.value = res.data
  } catch (e) { /* ignore */ }
}

// 明细跟着头部那条记录的日期走：头记录回落到的那天才是页面上数字的那天
async function loadDay() {
  const date = headRecord.value?.tradeDate
  details.value = null
  tiers.value = null
  anchorDay.value = null
  if (!date) return
  await Promise.all([
    marketApi.stocks(date)
      .then((res) => { details.value = res.data?.available ? res.data : null })
      .catch(() => { /* 这天没回补过明细：卡片照旧显示数字，只是不挂 hover */ }),
    marketApi.premiumTiers(date)
      .then((res) => { tiers.value = res.data?.available ? res.data : null })
      .catch(() => { /* 没档位数据：溢价卡退回含首板那个数 */ }),
    anchorApi.list(date)
      .then((res) => { anchorDay.value = res.data })
      .catch(() => { /* 未设阵眼就是 available=false，这里只管请求本身失败 */ })
  ])
}

async function loadAdvice() {
  try {
    const res = await recordApi.getAdvice()
    advice.value = res.data
  } catch (e) { /* ignore */ }
}

onMounted(() => {
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
