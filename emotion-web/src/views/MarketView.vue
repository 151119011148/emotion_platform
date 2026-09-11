<template>
  <div class="market-page">
    <div class="page-header">
      <h2>大盘生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>第 1 维 · 大盘生态（权重 25%）：指数环境 35 / 量能 25 / 广度 20 / 涨跌停 20</template>
      <div class="intro-body">
        <p>指数与盘面读数全部来自本地表（复盘 md 导入或行情回补）；打分明细与仪表盘第 1 维同源，改权重刷新即变。</p>
      </div>
    </el-alert>

    <!-- 强制退潮：命中即无视总分按退潮应对（引擎 forcedEbb 真值，非前端阈值） -->
    <div v-if="forcedEbb" class="ebb-banner">
      <span class="ebb-ico">⛔</span>
      <div class="ebb-text">
        <b>强制退潮已触发</b>（无视大盘维 / 总分，周期阶段按「退潮(强制)」应对）
        <span class="ebb-reason">{{ forcedEbbReason || '详见五维结构信号' }}</span>
      </div>
    </div>

    <!-- 五大指数 -->
    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>五大指数 <span class="sub" v-if="idxTradeDate">{{ idxTradeDate }} 收盘</span></h3>
      </div>
      <el-empty v-if="!indexes.length && !loading" description="当日没有指数收盘数据（未导入或未回补）" :image-size="60" />
      <div v-else class="index-grid">
        <div v-for="ix in indexes" :key="ix.code" class="index-card">
          <span class="ix-name">{{ ix.name }}</span>
          <span class="ix-code">{{ ix.code }}</span>
          <span class="ix-close">{{ ix.close == null ? '—' : Number(ix.close).toFixed(2) }}</span>
          <span class="ix-pct" :class="pctClass(ix.changePct)">
            {{ ix.changePct == null ? '—' : signed(ix.changePct) + '%' }}
          </span>
        </div>
      </div>
    </section>

    <!-- 盘面读数 -->
    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">两市成交额</span>
        <span class="stat-value">{{ record?.totalVolume != null ? Number(record.totalVolume).toLocaleString() + ' 亿' : '—' }}</span>
        <span class="stat-sub">复盘导入口径</span>
      </div>
      <div class="stat">
        <span class="stat-label">量比（成交额 / 20 日均）</span>
        <span class="stat-value">{{ ratioText(metrics.turnover_ratio) }}</span>
        <span class="stat-sub">{{ bandText('turnover') }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">上涨 / 下跌家数</span>
        <span class="stat-value">
          <span class="up">{{ breadthText.up }}</span>
          <span class="sep">/</span>
          <span class="down">{{ breadthText.down }}</span>
        </span>
        <span class="stat-sub">{{ breadthText.sub }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停 / 跌停</span>
        <span class="stat-value">
          <span class="up">{{ metrics.limit_up_count ?? '—' }}</span>
          <span class="sep">/</span>
          <span class="down">{{ metrics.limit_down_count ?? '—' }}</span>
        </span>
        <span class="stat-sub">家</span>
      </div>
    </div>

    <!-- D1 打分明细 -->
    <section class="block" v-loading="scoring.detailLoading">
      <div class="block-head">
        <h3>大盘生态打分</h3>
        <span v-if="marketDim" class="dim-score" :class="bandClass(marketDim.score)">
          {{ marketDim.score == null ? '未评' : Number(marketDim.score).toFixed(2) + ' 分' }}
        </span>
      </div>
      <el-empty v-if="!marketDim" description="当日读数未取到，第 1 维未评（不计入分母）" :image-size="60" />
      <el-table v-else :data="marketDim.children || []">
        <el-table-column prop="label" label="子项" width="120" />
        <el-table-column label="权重" width="80">
          <template #default="{ row }">×{{ row.weight }}</template>
        </el-table-column>
        <el-table-column label="得分" width="90" align="right">
          <template #default="{ row }">
            <span v-if="row.score == null" class="missing">未评</span>
            <span v-else :class="bandClass(row.score)">{{ Number(row.score).toFixed(0) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="读数 / 命中档" min-width="260">
          <template #default="{ row }">
            <span class="band-hit">{{ row.bandHit || strategyText(row) || '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { marketApi, recordApi } from '../api/modules'
import { useScoringStore } from '../stores/scoring'
import { signed, fiveDimBandClassOf } from '../utils/scores'

const route = useRoute()
const scoring = useScoringStore()

const todayStr = new Date().toLocaleDateString('en-CA')
// 从仪表盘维卡跳来时带 ?date=，直接停在同一天；否则默认今天再回落最近记录日
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const indexes = ref([])
const idxTradeDate = ref('')
const record = ref(null)

const metrics = computed(() => scoring.detail?.metrics || {})
const marketDim = computed(() => (scoring.detail?.dims || []).find((d) => d.key === 'market') || null)
// 强制退潮是全模型级信号（跌停≥10 / 阵眼核按钮 / 中位吹哨+大面 / 极高位爆量断板），直接镜像引擎，不在前端重算。
const forcedEbb = computed(() => scoring.detail?.forcedEbb === true)
const forcedEbbReason = computed(() => scoring.detail?.forcedEbbReason || '')

// 实时涨跌家数（东财 f104/105/106，仅当前时刻）；历史日期用 md 导入的 upCount/downCount
const liveBreadth = ref(null)
const isLatestDay = computed(() => date.value === todayStr)
const breadthText = computed(() => {
  const r = record.value
  if (r?.upCount != null && r?.downCount != null) {
    const denom = r.upCount + r.downCount
    const ratio = denom ? ((r.upCount / denom) * 100).toFixed(1) + '%' : '—'
    return { up: r.upCount, down: r.downCount, sub: '复盘导入 · 红盘率 ' + ratio }
  }
  if (isLatestDay.value && liveBreadth.value) {
    return {
      up: liveBreadth.value.upCount,
      down: liveBreadth.value.downCount,
      sub: '实时 · 红盘率 ' + (liveBreadth.value.redRatioPct ?? '—') + '%' +
        (liveBreadth.value.flatCount != null ? ' · 平 ' + liveBreadth.value.flatCount : '')
    }
  }
  return { up: '—', down: '—', sub: isLatestDay.value ? '实时未取到' : '历史日无导入数据' }
})

function ratioText(v) {
  return v == null ? '—' : Number(v).toFixed(2)
}

function bandText(key) {
  const sub = marketDim.value?.children?.find((c) => c.key === key)
  return sub?.bandHit || '量能档未评'
}

function strategyText(row) {
  // STRATEGY 节点没有 bandHit，raw 也为 null：把得分对应的档位含义写在备注里没数据可引，给空
  if (row.scoringKind === 'STRATEGY') return '策略算法（三指协同 / 涨跌停组合）'
  return ''
}

function pctClass(p) {
  if (p == null || Number.isNaN(Number(p))) return ''
  return Number(p) > 0 ? 'up' : Number(p) < 0 ? 'down' : ''
}

function bandClass(score) {
  return fiveDimBandClassOf(score)
}

function notBeforeToday(d) {
  return d.getTime() > Date.now()
}

async function load() {
  loading.value = true
  try {
    const [idxRes, recRes] = await Promise.all([
      marketApi.indexes(date.value).catch(() => null),
      recordApi.getByDate(date.value).catch(() => null)
    ])
    indexes.value = idxRes?.data?.indexes || []
    idxTradeDate.value = idxRes?.data?.tradeDate || ''
    record.value = recRes?.data || null
    // 实时家数只在看今天时有意义
    liveBreadth.value = null
    if (date.value === todayStr) {
      const b = await marketApi.breadth().catch(() => null)
      liveBreadth.value = b?.data || null
    }
    await scoring.loadDetail(date.value, true)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  // 没带日期且今天没有记录时，回落到最近一个复盘日（与其他页同口径）
  if (!route.query.date) {
    try {
      const res = await recordApi.getLatest(1)
      const latest = (res.data || [])[0]
      if (latest && latest.tradeDate < todayStr) date.value = latest.tradeDate
    } catch (e) { /* 停在今天 */ }
  }
  load()
})

watch(date, load)
</script>

<style scoped>
.market-page {
  max-width: 1100px;
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
.ebb-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background: rgba(239, 68, 68, .16);
  border: 1px solid #ef4444;
}
.ebb-ico { font-size: 18px; flex: none; animation: ebb-blink 1.1s steps(2, start) infinite; }
.ebb-text { color: #fecaca; font-size: 13px; line-height: 1.6; }
.ebb-text b { color: #fca5a5; font-size: 14px; }
.ebb-reason { display: block; color: #f87171; font-weight: 700; }
@keyframes ebb-blink { to { opacity: .25; } }
.block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.block-head h3 {
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
.dim-score {
  font-size: 16px;
  font-weight: 700;
}
.index-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(170px, 100%), 1fr));
  gap: 12px;
}
.index-card {
  background: #0f1419;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ix-name {
  font-size: 14px;
  color: #e1e8ed;
  font-weight: 600;
}
.ix-code {
  font-size: 11px;
  color: #6b7c8c;
}
.ix-close {
  font-size: 20px;
  font-weight: 700;
  color: #e1e8ed;
}
.ix-pct {
  font-size: 14px;
  font-weight: 700;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr));
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
.sep {
  color: #5b6c7d;
  margin: 0 6px;
}
.band-hit {
  font-size: 12px;
  color: #a8b7c4;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.missing {
  font-size: 12px;
  color: #6b7c8c;
}
.b-ebb { color: #94a3b8; }
.b-chaos { color: #60a5fa; }
.b-ferment { color: #fbbf24; }
.b-climax { color: #f87171; }
</style>
