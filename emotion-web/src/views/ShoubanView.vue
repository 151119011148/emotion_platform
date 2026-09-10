<template>
  <div class="shouban-page">
    <div class="page-header">
      <h2>首板生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>今日首板两本账：封住的进「封板表」，炸板的进「炸板表」</template>
      <div class="intro-body">
        <p>
          炸板表只收「昨日未涨停」的首板尝试——昨日有明细才能判定；1 进 2 晋级率与昨日首板溢价
          都以昨日为基数。昨日明细没回补时这两块不可算，页面会明说，不装作 0。
        </p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">首板封住</span>
        <span class="stat-value">{{ vo?.summary ? nz(summary.sealedCount) : '—' }}</span>
        <span class="stat-sub">涨停池 1 板</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板炸板</span>
        <span class="stat-value">{{ vo?.summary ? nz(summary.bombedCount) : '—' }}</span>
        <span class="stat-sub">{{ prevReady ? '可判定口径' : '昨日明细缺失' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">封板率</span>
        <span class="stat-value">{{ pctText(summary?.sealedRate) }}</span>
        <span class="stat-sub">封住 ÷（封住 + 首板炸板）</span>
      </div>
      <div class="stat">
        <span class="stat-label">1 进 2 晋级率</span>
        <span class="stat-value">{{ promoText }}</span>
        <span class="stat-sub">{{ prevReady ? `昨日首板 ${nz(summary?.prevFirstCount)} 只` : '昨日明细缺失，不可算' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">昨日首板溢价</span>
        <span class="stat-value" :class="pctClass(summary?.prevFirstPremiumPct)">
          {{ summary?.prevFirstPremiumPct == null ? '未落档' : signed(summary.prevFirstPremiumPct) + '%' }}
        </span>
        <span class="stat-sub">档位表 board=1 行</span>
      </div>
    </div>

    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>封住 {{ summary ? `· ${summary.sealedCount} 只` : '' }}</h3>
      </div>
      <el-empty v-if="!sealedRows.length && !loading" description="当日没有封住的首板（或行情明细未回补）" :image-size="60" />
      <el-table v-else :data="sealedRows">
        <el-table-column prop="code" label="代码" width="90" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column label="行业" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'in-main': row.inMain }">{{ row.industry || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="今日涨跌" width="110" align="right">
          <template #default="{ row }">
            <span :class="pctClass(row.changePct)">
              {{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="开板" width="90" align="center">
          <template #default="{ row }">{{ row.breakCount == null ? '—' : row.breakCount + ' 次' }}</template>
        </el-table-column>
        <el-table-column label="主线" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.inMain" type="warning" size="small" effect="plain">主线</el-tag>
            <span v-else class="missing">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>炸板 {{ summary ? `· ${summary.bombedCount} 只` : '' }}</h3>
      </div>
      <el-alert v-if="vo && vo.prevAvailable === false" type="warning" :closable="false" show-icon
        title="昨日明细未回补，无法判定哪些炸板属于「首板尝试」，炸板表留空" style="margin-bottom: 12px" />
      <el-empty v-else-if="!bombedRows.length && !loading" description="当日没有首板炸板（或昨日明细未回补）" :image-size="60" />
      <el-table v-else :data="bombedRows">
        <el-table-column prop="code" label="代码" width="90" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column label="行业" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'in-main': row.inMain }">{{ row.industry || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="今日涨跌" width="110" align="right">
          <template #default="{ row }">
            <span :class="pctClass(row.changePct)">
              {{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="自涨停回撤" width="120" align="right">
          <template #default="{ row }">
            <span class="pullback">{{ row.pullbackPct == null ? '—' : '-' + Number(row.pullbackPct).toFixed(2) + '%' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="开板" width="90" align="center">
          <template #default="{ row }">{{ row.breakCount == null ? '—' : row.breakCount + ' 次' }}</template>
        </el-table-column>
        <el-table-column label="主线" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.inMain" type="warning" size="small" effect="plain">主线</el-tag>
            <span v-else class="missing">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'

const route = useRoute()

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)

const summary = computed(() => vo.value?.summary || null)
/** 昨日有明细，晋级率与炸板表才可判。 */
const prevReady = computed(() => vo.value?.prevAvailable === true)
const sealedRows = computed(() => vo.value?.sealed || [])
const bombedRows = computed(() => vo.value?.bombed || [])

const promoText = computed(() => {
  if (!summary.value || summary.value.promoRate == null) return '不可算'
  const n = summary.value
  return `${n.promoRate.toFixed(1)}%（${n.promoCount}/${n.prevFirstCount}）`
})

function nz(v) {
  return v == null ? '—' : v
}

function pctText(v) {
  return v == null ? '—' : Number(v).toFixed(1) + '%'
}

function pctClass(p) {
  if (p == null || Number.isNaN(Number(p))) return ''
  return Number(p) > 0 ? 'up' : Number(p) < 0 ? 'down' : ''
}

function notBeforeToday(d) {
  return d.getTime() > Date.now()
}

async function load() {
  loading.value = true
  try {
    const res = await prdApi.shouban(date.value).catch(() => null)
    vo.value = res?.data || null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (!route.query.date) {
    try {
      const res = await recordApi.getLatest(1)
      const latest = (res.data || [])[0]
      if (latest && latest.tradeDate < todayStr) date.value = latest.tradeDate
    } catch (e) {
      // 拿不到最近记录就停在今天
    }
  }
  load()
})

watch(date, load)
</script>

<style scoped>
.shouban-page {
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
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(170px, 100%), 1fr));
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
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
}
.block-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.in-main {
  color: #fbbf24;
}
.pullback {
  color: #3b82f6;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.missing {
  font-size: 12px;
  color: #6b7c8c;
}
</style>
