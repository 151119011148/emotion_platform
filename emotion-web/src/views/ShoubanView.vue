<template>
  <div class="shouban-page">
    <div class="page-header">
      <h2>首板生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>今日首板两本账：封住（涨停池 1 板）与未封住（炸板池首板尝试）；首板溢价=昨首板今表现</template>
      <div class="intro-body">
        <p>封住/未封住名单默认收起，点击展开。1 进 2 成绩单列出昨日首板每只今天的去向：封 2 板 / 仍封低板 / 炸板 / 未触板（免费源不覆盖逐只行情时如实标注）。</p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">首板封住</span>
        <span class="stat-value">{{ summary ? nz(summary.sealedCount) : '—' }}</span>
        <span class="stat-sub">涨停池 1 板</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板炸板</span>
        <span class="stat-value">{{ summary ? nz(summary.bombedCount) : '—' }}</span>
        <span class="stat-sub">{{ prevReady ? '可判定口径' : '昨日明细缺失' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">封板率</span>
        <span class="stat-value">{{ pctText(summary?.sealedRate) }}</span>
        <span class="stat-sub">封住 ÷（封住 + 首板炸板）</span>
      </div>
      <div class="stat">
        <span class="stat-label">昨日首板</span>
        <span class="stat-value">{{ summary ? nz(summary.prevFirstCount) : '—' }}</span>
        <span class="stat-sub">{{ prevDate || '—' }} 首板</span>
      </div>
      <div class="stat">
        <span class="stat-label">1 进 2 晋级率</span>
        <span class="stat-value">{{ promoText }}</span>
        <span class="stat-sub">{{ summary?.prevFirstCount ? `晋级 ${nz(summary.promoCount)} 只` : '不可算' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">昨首板今均溢价</span>
        <span class="stat-value" :class="pctClass(summary?.prevFirstPremiumPct)">
          {{ summary?.prevFirstPremiumPct == null ? '未落档' : signed(summary.prevFirstPremiumPct) + '%' }}
        </span>
        <span class="stat-sub">档位表 board=1 全样本</span>
      </div>
    </div>

    <DimScoreBlock :date="date" dim-key="first" title="首板生态打分" />

    <!-- 1 进 2 成绩单 -->
    <section class="block" v-if="vo?.promoDetail">
      <div class="block-head">
        <h3>昨日首板今日表现（首板溢价）</h3>
        <span class="sub">
          {{ vo.promoDetail.prevCount }} 只昨日首板 ·
          晋级 <span class="ok-text">{{ vo.promoDetail.promotedCount }}</span> ·
          失败 <span class="bad-text">{{ vo.promoDetail.failed?.length || 0 }}</span>
          <template v-if="vo.promoDetail.avgPremiumPct != null">
            · 均溢价 <span :class="pctClass(vo.promoDetail.avgPremiumPct)">{{ signed(vo.promoDetail.avgPremiumPct) }}%</span>
          </template>
        </span>
      </div>
      <div class="detail-grid">
        <div>
          <h4 class="ok-text">晋级成功 · 封上 2 板（{{ vo.promoDetail.success?.length || 0 }}）</h4>
          <el-table :data="vo.promoDetail.success || []" size="small" max-height="320">
            <el-table-column prop="code" label="代码" width="80" />
            <el-table-column prop="name" label="名称" width="100" show-overflow-tooltip />
            <el-table-column label="形态" width="64">
              <template #default="{ row }">
                <el-tag size="small" :type="PATTERN_TYPE[row.pattern] || 'info'" effect="plain">{{ PATTERN_LABEL[row.pattern] || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="封单额" width="92">
              <template #default="{ row }">{{ row.sealAmount != null ? moneyText(row.sealAmount) : '—' }}</template>
            </el-table-column>
            <el-table-column label="涨幅" width="80" align="right">
              <template #default="{ row }">
                <span :class="pctClass(row.changePct)">{{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}</span>
              </template>
            </el-table-column>
            <template #empty>无</template>
          </el-table>
        </div>
        <div>
          <h4 class="bad-text">晋级失败（{{ vo.promoDetail.failed?.length || 0 }}）</h4>
          <el-table :data="vo.promoDetail.failed || []" size="small" max-height="320">
            <el-table-column prop="code" label="代码" width="80" />
            <el-table-column prop="name" label="名称" width="100" show-overflow-tooltip />
            <el-table-column label="今日状态" width="104">
              <template #default="{ row }">
                <el-tag size="small" :type="FAIL_TYPE[row.todayStatus] || 'info'">{{ FAIL_LABEL[row.todayStatus] || '—' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="涨幅/回撤" min-width="110">
              <template #default="{ row }">
                <span v-if="row.changePct != null" :class="pctClass(row.changePct)">{{ signed(row.changePct) }}%</span>
                <span v-else class="missing">明细未覆盖</span>
                <span v-if="row.pullbackPct != null" class="pullback">回撤 {{ row.pullbackPct }}%</span>
              </template>
            </el-table-column>
            <template #empty>无</template>
          </el-table>
        </div>
      </div>
    </section>

    <!-- 封住 / 未封住：默认折叠 -->
    <el-collapse v-model="activeLists" class="list-collapse">
      <el-collapse-item name="sealed">
        <template #title>
          <span class="collapse-title ok-text">封住名单（{{ sealedRows.length }}）</span>
        </template>
        <el-table :data="sealedRows" size="small">
          <el-table-column prop="code" label="代码" width="90" />
          <el-table-column prop="name" label="名称" width="110" show-overflow-tooltip />
          <el-table-column label="行业" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ 'in-main': row.inMain }">{{ row.industry || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="形态" width="72">
            <template #default="{ row }">
              <el-tag size="small" :type="PATTERN_TYPE[row.pattern] || 'info'" effect="plain">{{ PATTERN_LABEL[row.pattern] || '—' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="封单额" width="100">
            <template #default="{ row }">{{ row.sealAmount != null ? moneyText(row.sealAmount) : '—' }}</template>
          </el-table-column>
          <el-table-column label="开板" width="64" align="center">
            <template #default="{ row }">{{ row.breakCount == null ? '—' : row.breakCount + ' 次' }}</template>
          </el-table-column>
          <el-table-column label="涨幅" width="90" align="right">
            <template #default="{ row }">
              <span :class="pctClass(row.changePct)">{{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}</span>
            </template>
          </el-table-column>
          <template #empty>当日没有封住的首板（或行情明细未回补，点每日复盘拉行情）</template>
        </el-table>
      </el-collapse-item>

      <el-collapse-item name="bombed">
        <template #title>
          <span class="collapse-title bad-text">未封住名单（{{ bombedRows.length }}）</span>
        </template>
        <el-alert v-if="!prevReady" type="warning" :closable="false" show-icon
          title="昨日明细未回补，无法判定哪些炸板属于「首板尝试」，本表暂不可算" style="margin: 8px 0" />
        <el-table v-else :data="bombedRows" size="small">
          <el-table-column prop="code" label="代码" width="90" />
          <el-table-column prop="name" label="名称" width="110" show-overflow-tooltip />
          <el-table-column label="行业" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ 'in-main': row.inMain }">{{ row.industry || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="今日涨跌" width="110" align="right">
            <template #default="{ row }">
              <span :class="pctClass(row.changePct)">{{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="自涨停回撤" width="120" align="right">
            <template #default="{ row }">
              <span class="pullback">{{ row.pullbackPct == null ? '—' : '-' + Number(row.pullbackPct).toFixed(2) + '%' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开板" width="80" align="center">
            <template #default="{ row }">{{ row.breakCount == null ? '—' : row.breakCount + ' 次' }}</template>
          </el-table-column>
          <template #empty>当日没有首板炸板（或昨日明细未回补）</template>
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'
import DimScoreBlock from '../components/DimScoreBlock.vue'

const route = useRoute()

const PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }
const PATTERN_TYPE = { ONE_LINE: 'danger', T_SHAPE: 'warning', TURNOVER: 'info' }
const FAIL_LABEL = { ZT: '仍封停(1板)', ZB: '炸板', GONE: '未触板' }
const FAIL_TYPE = { ZT: 'warning', ZB: 'danger', GONE: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)
// 两个名单默认都收起
const activeLists = ref([])

const summary = computed(() => vo.value?.summary || null)
const sealedRows = computed(() => vo.value?.sealed || [])
const bombedRows = computed(() => vo.value?.bombed || [])
const prevReady = computed(() => vo.value?.prevAvailable === true)
const prevDate = computed(() => vo.value?.prevDate || '')
const promoText = computed(() => {
  const s = summary.value
  if (!s || s.prevFirstCount === 0) return '不可算'
  return s.promoRate == null ? '—' : s.promoRate.toFixed(1) + '%'
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
function moneyText(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  if (n >= 1e8) return (n / 1e8).toFixed(2) + ' 亿'
  if (n >= 1e4) return (n / 1e4).toFixed(0) + ' 万'
  return n.toFixed(0) + ' 元'
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
    } catch (e) { /* 停在今天 */ }
  }
  load()
})
watch(date, load)
</script>

<style scoped>
.shouban-page { max-width: 1100px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.page-header h2 { margin: 0; color: #e1e8ed; }
.intro { margin-bottom: 16px; }
.intro-body p { margin: 6px 0 0; font-size: 12px; line-height: 1.6; color: #8899a6; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(170px, 100%), 1fr)); gap: 12px; margin-bottom: 20px; }
.stat { background: #1a2332; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.stat-label { font-size: 12px; color: #8899a6; }
.stat-value { font-size: 20px; font-weight: 700; color: #e1e8ed; }
.stat-sub { font-size: 11px; color: #8899a6; }
.block { background: #1a2332; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
.block-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.block-head h3 { margin: 0; font-size: 15px; color: #e1e8ed; }
.sub { font-size: 12px; color: #8899a6; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.detail-grid h4 { margin: 0 0 8px; font-size: 13px; }
.ok-text { color: #6ee7b7; }
.bad-text { color: #fca5a5; }
.pullback { color: #fca5a5; }
.in-main { color: #fbbf24; }
.missing { font-size: 12px; color: #6b7c8c; }
.up { color: #ef4444; }
.down { color: #3b82f6; }
.list-collapse { background: #1a2332; border-radius: 12px; padding: 4px 20px; }
.collapse-title { font-size: 14px; font-weight: 600; }
@media (max-width: 800px) { .detail-grid { grid-template-columns: 1fr; } }
</style>
