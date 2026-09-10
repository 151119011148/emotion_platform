<template>
  <div class="tianti-page">
    <div class="page-header">
      <h2>连板生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>≥2 板连板股按四层分组，龙头分工标签与打分引擎同源</template>
      <div class="intro-body">
        <p>
          空层保留——某一层没有票本身就是信息。总龙头 = 全市场最高连板，梯子空也照常给状态卡；
          标签（总龙头/中军/跟风/卡位/反包）来自主线判定的同一份结论，能在复盘页 score-detail 里逐个对上。
        </p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">最高连板 H</span>
        <span class="stat-value">{{ vo ? nz(vo.maxBoard) : '—' }}</span>
        <span class="stat-sub">全市场</span>
      </div>
      <div class="stat">
        <span class="stat-label">主线行业</span>
        <span class="stat-value main-industry">{{ vo?.mainIndustry || '—' }}</span>
        <span class="stat-sub">涨停聚集度最高者</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停 / 炸板</span>
        <span class="stat-value">{{ vo ? `${nz(vo.ztTotal)} / ${nz(vo.zbTotal)}` : '—' }}</span>
        <span class="stat-sub">当日家数</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停聚集度</span>
        <span class="stat-value">{{ pctText(vo?.ztGatherPct) }}</span>
        <span class="stat-sub">主线涨停 ÷ 全市场涨停</span>
      </div>
      <div class="stat">
        <span class="stat-label">高度聚集度</span>
        <span class="stat-value">{{ pctText(vo?.heightGatherPct) }}</span>
        <span class="stat-sub">主线最高板 ÷ 全市场 H</span>
      </div>
      <div class="stat">
        <span class="stat-label">持续活跃</span>
        <span class="stat-value">{{ vo?.persistenceDays != null ? vo.persistenceDays + ' 天' : '—' }}</span>
        <span class="stat-sub">主线连续活跃天数</span>
      </div>
    </div>

    <section class="block dragon-block" v-if="vo?.dragon" v-loading="loading">
      <div class="block-head">
        <h3>总龙头</h3>
        <el-tag :type="ACTION_TYPE[vo.dragon.action] || 'info'" effect="dark" size="small">
          {{ ACTION_LABEL[vo.dragon.action] || vo.dragon.action || '—' }}
        </el-tag>
      </div>
      <div class="dragon-card">
        <span class="dragon-name">{{ vo.dragon.name || '—' }}</span>
        <span class="dragon-code">{{ vo.dragon.code }}</span>
        <el-tag size="small" effect="plain">{{ vo.dragon.industry || '行业未登记' }}</el-tag>
        <span class="dragon-board">{{ vo.dragon.board }} 板</span>
        <span class="dragon-pct" :class="pctClass(vo.dragon.changePct)">
          {{ vo.dragon.changePct == null ? '—' : signed(vo.dragon.changePct) + '%' }}
        </span>
        <span class="dragon-promoted" v-if="vo.dragon.promoted != null">
          {{ vo.dragon.promoted ? '昨日晋级（昨 H-1 → 今 H）' : '今日新王' }}
        </span>
      </div>
    </section>

    <section class="block" v-for="tier in vo?.tiers || []" :key="tier.key" v-loading="loading">
      <div class="block-head">
        <h3>{{ tier.label }} <span class="board-range">{{ tier.boardRange }}</span></h3>
        <span class="tier-count">{{ tier.count }} 只</span>
      </div>
      <el-empty v-if="!tier.rows.length" :description="`${tier.label}（${tier.boardRange}）今日断档`"
        :image-size="60" />
      <el-table v-else :data="tier.rows">
        <el-table-column prop="code" label="代码" width="90" />
        <el-table-column prop="name" label="名称" width="110" />
        <el-table-column prop="industry" label="行业" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'in-main': row.industry && row.industry === vo.mainIndustry }">
              {{ row.industry || '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="board" label="连板" width="80" align="center">
          <template #default="{ row }">
            <span class="board-num">{{ row.board }}</span>
          </template>
        </el-table-column>
        <el-table-column label="今日涨跌" width="110" align="right">
          <template #default="{ row }">
            <span :class="pctClass(row.changePct)">
              {{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="晋级" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.promoted == null" class="missing">—</span>
            <el-tag v-else :type="row.promoted ? 'success' : 'info'" size="small" effect="plain">
              {{ row.promoted ? '晋级' : '未晋级' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开板" width="80" align="center">
          <template #default="{ row }">{{ row.breakCount == null ? '—' : row.breakCount + ' 次' }}</template>
        </el-table-column>
        <el-table-column label="分工" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.role" :type="ROLE_TYPE[row.role] || 'info'" size="small" effect="dark">
              {{ row.role }}
            </el-tag>
            <span v-else class="missing">—</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-empty v-if="!loading && !vo" description="当日天梯为空：行情明细未回补，或确实没有 ≥2 板的连板股" />
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'

const route = useRoute()

const ACTION_LABEL = { PROMOTE: '晋级', HOLD: '在位', BREAK: '断板', ABSENT: '缺席' }
const ACTION_TYPE = { PROMOTE: 'success', HOLD: 'primary', BREAK: 'danger', ABSENT: 'info' }
// PRD 配色：🔴总龙头 🔵中军 🟢跟风 🟡卡位 🟣反包
const ROLE_TYPE = { 总龙头: 'danger', 中军: 'primary', 跟风: 'success', 卡位: 'warning', 反包: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)

function nz(v) {
  return v == null ? '—' : v
}

function pctText(v) {
  return v == null ? '未评' : Number(v).toFixed(1) + '%'
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
    const res = await prdApi.tianti(date.value).catch(() => null)
    vo.value = res?.data || null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  if (!route.query.date) {
    try {
      // 和仪表盘同一口径：当日没复盘就回落到最近一条，而不是给人一个空页
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
.tianti-page {
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
  grid-template-columns: repeat(auto-fit, minmax(min(160px, 100%), 1fr));
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
.main-industry {
  font-size: 16px;
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
.board-range {
  font-size: 12px;
  color: #8899a6;
  margin-left: 6px;
}
.tier-count {
  font-size: 12px;
  color: #8899a6;
}
.dragon-card {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 12px;
}
.dragon-name {
  font-size: 20px;
  font-weight: 700;
  color: #e1e8ed;
}
.dragon-code {
  font-size: 13px;
  color: #8899a6;
}
.dragon-board {
  font-size: 14px;
  color: #fbbf24;
  font-weight: 700;
}
.dragon-pct {
  font-size: 14px;
  font-weight: 700;
}
.dragon-promoted {
  font-size: 12px;
  color: #8899a6;
}
.board-num {
  color: #fbbf24;
  font-weight: 700;
}
.in-main {
  color: #fbbf24;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.missing {
  font-size: 12px;
  color: #6b7c8c;
}
</style>
