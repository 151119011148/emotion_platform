<template>
  <div class="surv-page">
    <div class="page-header">
      <h2>异动监管名单</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>名单来自本地表 t_surveillance，回补过才全</template>
      <div class="intro-body">
        <p>
          第 9 维只数严重异常波动与交易所监管两类（下表「进分」列），例行异常波动只展示、不进分母。
          名单靠 <code>/api/market/surveillance/refresh</code> 回补，没回补过的日子这里是空的——
          那不等于"当天没有在列的票"。
        </p>
        <p v-if="basis" class="basis">{{ basis }}</p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">进分家数</span>
        <span class="stat-value">{{ surv ? surv.count : '—' }}</span>
        <span class="stat-sub">在列共 {{ surv ? surv.allCount : '—' }} 只</span>
      </div>
      <div class="stat">
        <span class="stat-label">进分均值</span>
        <span class="stat-value" :class="pctClass(surv && surv.count ? surv.avgPct : null)">
          {{ surv && surv.count ? signed(surv.avgPct) + '%' : '—' }}
        </span>
        <span class="stat-sub">{{ avgSub }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">第 9 维</span>
        <span class="stat-value">{{ survScore == null ? '未评' : survScore + ' 分' }}</span>
        <span class="stat-sub">{{ survScoreNote }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">当日温度</span>
        <span class="stat-value">{{ record ? tempText(record) : '无记录' }}</span>
        <span class="stat-sub">{{ record ? (record.stage || '未定位阶段') : '这天没复盘' }}</span>
      </div>
    </div>
    <SurvCurve :rows="curveRows" :selected="date" @select="date = $event" />
    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>当日在列 {{ surv ? `· 共 ${surv.allCount} 只，其中进分 ${surv.count} 只` : '' }}</h3>
        <el-checkbox v-model="onlyScored">只看进分</el-checkbox>
      </div>
      <el-empty v-if="!displayRows.length && !loading"
        :description="rows.length ? `没有进分的票，取消「只看进分」可看 ${rows.length} 只只展示的` : '当日名单为空：未回补，或确实没有在列的票'" />
      <el-table v-else :data="displayRows" :row-class-name="rowClass" max-height="480">
        <el-table-column prop="code" label="代码" width="90" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.scored ? 'danger' : 'info'" effect="plain" size="small">
              {{ KIND_LABEL[row.kind] || row.kind }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="annDate" label="起算日" width="112" />
        <el-table-column label="在列第几日" width="112">
          <template #default="{ row }">{{ row.dayIndex }}/{{ row.days }}</template>
        </el-table-column>
        <el-table-column label="剩余窗口" width="110">
          <template #default="{ row }">
            <span :class="{ expired: row.left <= 0 }">{{ row.left <= 0 ? '今日到期' : row.left + ' 日' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="今日涨跌" width="110" align="right">
          <template #default="{ row }">
            <span :class="pctClass(row.pct)">{{ row.pct == null ? '—' : signed(row.pct) + '%' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="是否进分" width="100">
          <template #default="{ row }">
            <span :class="row.scored ? 'scored' : 'display-only'">{{ row.scored ? '进分' : '只展示' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="describe" label="依据" min-width="260" show-overflow-tooltip />
      </el-table>
    </section>
    <section class="block" v-loading="loading">
      <h3>近 {{ DAILY_ROWS }} 日第 9 维</h3>
      <el-table :data="dailyRows" max-height="420">
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column label="进分家数" width="120">
          <template #default="{ row }">
            <span v-if="row.count == null" class="missing">未拉取</span>
            <span v-else>{{ row.count }}</span>
          </template>
        </el-table-column>
        <el-table-column label="进分均值" width="120" align="right">
          <template #default="{ row }">
            <span :class="pctClass(row.avg)">{{ row.avg == null ? '—' : signed(row.avg) + '%' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="出分" width="100">
          <template #default="{ row }">
            <span v-if="row.score == null" class="missing">未评</span>
            <span v-else :class="{ minus: row.score < 0 }">{{ row.score }} 分</span>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="算式" min-width="300" show-overflow-tooltip />
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { marketApi, recordApi } from '../api/modules'
import { survivalBandOf, signed } from '../utils/scores'
import SurvCurve from '../components/SurvCurve.vue'

/** 逐日那一屏放多少行；再多就滚，不翻页——这一格是来看趋势拐点的。 */
const DAILY_ROWS = 14
/** 往回取多少个日历日去凑这 14 个交易日：留足长假，取 60 天。 */
const LOOKBACK_DAYS = 60

const KIND_LABEL = {
  SEVERE: '严重异常波动',
  EXCH: '交易所监管',
  ZD: '异常波动'
}

// 本地日期，不能用 toISOString()：那是 UTC，00:00–07:59 会算成前一天
const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(todayStr)
const loading = ref(false)
const surv = ref(null)
const records = ref([])

const record = computed(() => records.value.find((r) => r.tradeDate === date.value) || null)

/** 例行异常波动占九成，全列出来是一堵墙而且一只不进分；默认收起来，开关放开。 */
const onlyScored = ref(true)

/** 进分的一只不藏、只展示的那一堵墙排到后面；同组里跌得深的在前，顶哨先看谁。 */
const rows = computed(() => {
  const items = (surv.value?.items || []).map((it) => ({
    ...it, left: (it.days ?? 0) - (it.dayIndex ?? 0)
  }))
  return items.sort((a, b) => {
    if (a.scored !== b.scored) return a.scored ? -1 : 1
    const av = a.pct == null ? Infinity : Number(a.pct)
    const bv = b.pct == null ? Infinity : Number(b.pct)
    return av - bv
  })
})

const displayRows = computed(() => (onlyScored.value ? rows.value.filter((r) => r.scored) : rows.value))

const avgSub = computed(() => {
  if (!surv.value) return '名单未取到'
  if (!surv.value.count) return '没有进分的票'
  const dropped = surv.value.dropped ? ` · 脏值剔除 ${surv.value.dropped}` : ''
  return `取到涨跌 ${surv.value.matched}/${surv.value.count} 家${dropped}`
})

const survScore = computed(() => {
  if (!surv.value || !surv.value.count) return null
  return survivalBandOf(surv.value.avgPct)
})

/**
 * 未评有两种来路，说法必须两个：当天没有在列的真监管股（中性事实）
 * 和有票但涨跌没取到（缺数）。两者都是整维剔出分母，含义完全不同。
 */
const survScoreNote = computed(() => {
  if (survScore.value != null) return '按均值档位进分母'
  if (!surv.value) return '名单未取到'
  if (!surv.value.count) return '当天没有在列的真监管股，不计入分母'
  return '进分家数有、涨跌未取到，不计入分母'
})

const dailyRows = computed(() => records.value.map((r) => ({
  date: r.tradeDate,
  count: r.survCount,
  avg: r.survPremium,
  score: r.survCount > 0 && r.survPremium != null ? survivalBandOf(r.survPremium) : null,
  note: r.survNote || ''
})))

/** 曲线要按时间正着走；records 是为表格倒序的，这里翻回来，不另拉一次。 */
const curveRows = computed(() => [...dailyRows.value].reverse())

/** 那句算式后端已经产出了，这里只搬运，不另写一套话术。 */
const basis = computed(() => record.value?.survNote || surv.value?.note || '')

function tempText(r) {
  return r.temperature == null ? '未出' : `${Number(r.temperature).toFixed(1)}°`
}

function pctClass(p) {
  if (p == null || Number.isNaN(Number(p))) return ''
  return Number(p) > 0 ? 'up' : Number(p) < 0 ? 'down' : ''
}

function rowClass({ row }) {
  return row.scored ? '' : 'display-row'
}

function notBeforeToday(d) {
  return d.getTime() > Date.now()
}

/** 补 T00:00:00 按本地时区解析，否则 new Date('2026-09-04') 走 UTC 会少一天。 */
function shiftDays(iso, days) {
  const d = new Date(iso + 'T00:00:00')
  d.setDate(d.getDate() - days)
  return d.toLocaleDateString('en-CA')
}

async function load() {
  loading.value = true
  try {
    const [survRes, rangeRes] = await Promise.all([
      // 单日名单拉挂了不该让整页空白，逐日那一格照样有数
      marketApi.surveillance(date.value).catch(() => null),
      recordApi.getRange(shiftDays(date.value, LOOKBACK_DAYS), date.value)
    ])
    surv.value = survRes?.data || null
    records.value = ((rangeRes && rangeRes.data) || []).slice(-DAILY_ROWS).reverse()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    // 和仪表盘同一口径：当日没复盘就回落到最近一条，而不是给人一个空页
    const res = await recordApi.getLatest(1)
    const latest = (res.data || [])[0]
    if (latest && latest.tradeDate < todayStr) date.value = latest.tradeDate
  } catch (e) {
    // 拿不到最近记录就停在今天
  }
  load()
})

// 日期是这一页唯一的输入，watch 比 @change 稳：清空、键盘改、程序改都走同一条路
watch(date, load)
</script>

<style scoped>
.surv-page {
  max-width: 1000px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0;
  color: #e1e8ed;
}
.intro {
  margin-bottom: 16px;
}
.intro-body p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #8899a6;
}
.intro-body code {
  color: #cbd5e1;
}
.intro-body .basis {
  color: #d97706;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.stat {
  background: #1a2332;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label {
  font-size: 12px;
  color: #8899a6;
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: #e1e8ed;
}
.stat-sub {
  font-size: 11px;
  color: #8899a6;
}
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.block h3 {
  margin: 0 0 14px;
  font-size: 15px;
  color: #e1e8ed;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.block-head :deep(.el-checkbox__label) {
  color: #8899a6;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.scored { color: #fbbf24; }
.display-only { color: #8899a6; }
.expired { color: #f59e0b; }
.missing {
  font-size: 12px;
  color: #8899a6;
  border: 1px dashed #2d3748;
  border-radius: 4px;
  padding: 0 5px;
}
/* 出分允许 -1：0 分和 -1 分不能撞成同一个样子 */
.minus {
  color: #60a5fa;
  font-weight: 700;
}
</style>

<style>
/* 「只展示」那一类压暗一档：它是背景噪音，眼睛该先落在进分的几只上 */
.display-row td.el-table__cell {
  color: #6b7c8c;
}
</style>
