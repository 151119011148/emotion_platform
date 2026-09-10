<template>
  <div class="tianti-page">
    <div class="page-header">
      <h2>连板生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>连板金字塔：按板高 H→2 逐层，每层给个股（龙头标签/一字·T字形态/封单额）与晋级成败</template>
      <div class="intro-body">
        <p>一字=开盘前封死且全天 0 炸板；T字=开盘封死但盘中开过又回封；其余为换手板。总龙头=全市场最高连板（同板高取涨幅最大）。</p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">最高连板 H</span>
        <span class="stat-value">{{ nz(vo?.maxBoard) }}</span>
        <span class="stat-sub">全市场</span>
      </div>
      <div class="stat">
        <span class="stat-label">日内核心</span>
        <template v-if="vo?.mainIndustry">
          <span class="stat-value main-industry">{{ vo.mainIndustry }}</span>
          <el-tag size="small" :type="vo.mainlineConfirmed ? 'success' : 'warning'">
            {{ vo.mainlineConfirmed ? '已成主线' : `热度${nz(vo.persistenceDays)}天未成主线` }}
          </el-tag>
        </template>
        <span v-else class="stat-sub">—</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停 / 炸板</span>
        <span class="stat-value">{{ nz(vo?.ztTotal) }} / {{ nz(vo?.zbTotal) }}</span>
        <span class="stat-sub">当日家数</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停聚集</span>
        <span class="stat-value">{{ pctText(vo?.ztGatherPct) }}</span>
        <span class="stat-sub">高度聚集 {{ pctText(vo?.heightGatherPct) }}</span>
      </div>
    </div>

    <DimScoreBlock :date="date" dim-key="board" title="连板生态打分" />

    <section class="block dragon-block" v-loading="loading">
      <div class="block-head">
        <h3>总龙头</h3>
        <el-tag v-if="vo?.dragon" :type="ACTION_TYPE[vo.dragon.action] || 'info'" effect="dark" size="small">
          {{ ACTION_LABEL[vo.dragon.action] || '—' }}
        </el-tag>
      </div>
      <template v-if="vo?.dragon">
        <div class="dragon-card">
          <div class="dragon-line">
            <span class="dragon-name">{{ vo.dragon.name }}</span>
            <span class="dragon-code">{{ vo.dragon.code }}</span>
            <el-tag size="small" effect="plain">{{ vo.dragon.industry || '行业未登记' }}</el-tag>
            <span class="dragon-board">{{ nz(vo.dragon.board) }} 板</span>
            <span :class="pctClass(vo.dragon.changePct)">
              {{ vo.dragon.changePct == null ? '—' : signed(vo.dragon.changePct) + '%' }}
            </span>
          </div>
          <p v-if="vo.dragon.reason" class="dragon-reason">{{ vo.dragon.reason }}</p>
        </div>
      </template>
      <el-alert v-else-if="!loading" type="info" :closable="false" show-icon title="当日无涨停池，总龙头缺席" />
    </section>

    <!-- 金字塔 -->
    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>连板金字塔</h3>
        <span class="sub">从高板到 2 板；空层=断档</span>
      </div>
      <div v-if="!vo?.levels?.length && !loading" class="none-hint">当日无 ≥2 板个股</div>
      <div class="pyramid">
        <div v-for="lvl in vo?.levels || []" :key="lvl.board" class="pyr-row">
          <div class="pyr-band" :class="{ empty: !lvl.rows.length }" :style="bandStyle(lvl)">
            <div class="pyr-head">
              <span class="pyr-title">{{ lvl.board }} 板</span>
              <el-tag size="small" effect="plain" class="layer-tag">{{ lvl.layerLabel }}</el-tag>
              <span class="pyr-count">{{ lvl.count }} 只</span>
              <span class="pyr-promo" :class="promoClass(lvl.promoRate)">
                晋级 {{ lvl.promotedCount }}/{{ lvl.prevCount }}
                <template v-if="lvl.promoRate != null">（{{ lvl.promoRate.toFixed(1) }}%）</template>
                <template v-else>（昨日无 {{ lvl.board - 1 }} 板）</template>
              </span>
            </div>
            <div v-if="lvl.rows.length" class="chips">
              <div v-for="r in lvl.rows" :key="r.code" class="chip" :class="roleChipClass(r.role)">
                <span class="chip-name">{{ r.name }}</span>
                <el-tag v-if="r.role" size="small" :type="ROLE_TYPE[r.role] || 'info'" effect="dark">{{ r.role }}</el-tag>
                <el-tag v-if="r.pattern" size="small" :type="PATTERN_TYPE[r.pattern] || 'info'" effect="plain">
                  {{ PATTERN_LABEL[r.pattern] }}
                </el-tag>
                <span v-if="r.promoted === true" class="chip-promoted ok">晋级</span>
                <span v-if="r.promoted === false" class="chip-promoted bad">持稳</span>
                <span v-if="r.sealAmount != null" class="chip-seal">封单 {{ moneyText(r.sealAmount) }}</span>
                <span :class="pctClass(r.changePct)" class="chip-pct">{{ signed(r.changePct) }}%</span>
              </div>
            </div>
            <div v-else class="gap-hint">断档（无 {{ lvl.board }} 板）</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 每层晋级明细（默认折叠） -->
    <section class="block" v-if="(vo?.levels || []).some(l => ((l.success?.length) || (l.failed?.length)))">
      <div class="block-head">
        <h3>晋级明细</h3>
        <span class="sub">昨日各板今日的晋级成败</span>
      </div>
      <el-collapse>
        <el-collapse-item v-for="lvl in (vo?.levels || [])" :key="'d' + lvl.board"
          v-show="((lvl.success?.length) || (lvl.failed?.length))"
          :name="lvl.board">
          <template #title>
            <span class="detail-title">
              {{ lvl.board - 1 }}→{{ lvl.board }} 板
              <el-tag size="small" type="success" effect="plain">成功 {{ lvl.success?.length || 0 }}</el-tag>
              <el-tag size="small" type="danger" effect="plain">失败 {{ lvl.failed?.length || 0 }}</el-tag>
              <span v-if="lvl.promoRate != null" class="detail-rate">晋级率 {{ lvl.promoRate.toFixed(1) }}%</span>
            </span>
          </template>
          <div class="detail-grid">
            <div>
              <h4 class="ok-text">晋级成功（{{ lvl.success?.length || 0 }}）</h4>
              <el-table :data="lvl.success || []" size="small">
                <el-table-column prop="code" label="代码" width="80" />
                <el-table-column prop="name" label="名称" width="100" />
                <el-table-column label="形态" width="70">
                  <template #default="{ row }">{{ PATTERN_LABEL[row.pattern] || '—' }}</template>
                </el-table-column>
                <el-table-column label="封单额" width="100">
                  <template #default="{ row }">{{ row.sealAmount != null ? moneyText(row.sealAmount) : '—' }}</template>
                </el-table-column>
                <el-table-column label="涨幅" width="90" align="right">
                  <template #default="{ row }">
                    <span :class="pctClass(row.changePct)">{{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div>
              <h4 class="bad-text">晋级失败（{{ lvl.failed?.length || 0 }}）</h4>
              <el-table :data="lvl.failed || []" size="small">
                <el-table-column prop="code" label="代码" width="80" />
                <el-table-column prop="name" label="名称" width="100" />
                <el-table-column label="今日状态" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" :type="FAIL_TYPE[row.todayStatus] || 'info'">{{ FAIL_LABEL[row.todayStatus] || '—' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="涨幅/回撤" min-width="120">
                  <template #default="{ row }">
                    <span v-if="row.changePct != null" :class="pctClass(row.changePct)">{{ signed(row.changePct) }}%</span>
                    <span v-else class="missing">明细未覆盖</span>
                    <span v-if="row.pullbackPct != null" class="pullback">回撤 {{ row.pullbackPct }}%</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'
import DimScoreBlock from '../components/DimScoreBlock.vue'

const route = useRoute()

const ACTION_LABEL = { PROMOTE: '晋级', HOLD: '在位', BREAK: '断板', ABSENT: '缺席' }
const ACTION_TYPE = { PROMOTE: 'success', HOLD: 'primary', BREAK: 'danger', ABSENT: 'info' }
const ROLE_TYPE = { 总龙头: 'danger', 中军: 'primary', 跟风: 'success', 卡位: 'warning', 反包: 'info' }
const PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }
const PATTERN_TYPE = { ONE_LINE: 'danger', T_SHAPE: 'warning', TURNOVER: 'info' }
const FAIL_LABEL = { ZT: '仍封停(低板)', ZB: '炸板', GONE: '未触板' }
const FAIL_TYPE = { ZT: 'warning', ZB: 'danger', GONE: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)

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
/** 封单额：元 → 亿/万。 */
function moneyText(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  if (n >= 1e8) return (n / 1e8).toFixed(2) + ' 亿'
  if (n >= 1e4) return (n / 1e4).toFixed(0) + ' 万'
  return n.toFixed(0) + ' 元'
}
function roleChipClass(role) {
  return role ? `role-${role}` : ''
}
/** 金字塔带宽：按各层个股数相对最高层收窄，最少 42% 保证空层也有形。 */
function bandStyle(lvl) {
  const maxCount = Math.max(1, ...(vo.value?.levels || []).map((l) => l.count))
  const ratio = lvl.count === 0 ? 0.42 : Math.max(0.5, 0.55 + 0.45 * (lvl.count / maxCount))
  return { width: (ratio * 100).toFixed(1) + '%' }
}
function promoClass(rate) {
  if (rate == null) return ''
  return rate >= 60 ? 'ok' : rate >= 30 ? 'mid' : 'bad'
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
.tianti-page { max-width: 1100px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.page-header h2 { margin: 0; color: #e1e8ed; }
.intro { margin-bottom: 16px; }
.intro-body p { margin: 6px 0 0; font-size: 12px; line-height: 1.6; color: #8899a6; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr)); gap: 12px; margin-bottom: 20px; }
.stat { background: #1a2332; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.stat-label { font-size: 12px; color: #8899a6; }
.stat-value { font-size: 20px; font-weight: 700; color: #e1e8ed; }
.stat-value.main-industry { font-size: 17px; }
.stat-sub { font-size: 11px; color: #8899a6; }
.block { background: #1a2332; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
.block-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.block-head h3 { margin: 0; font-size: 15px; color: #e1e8ed; }
.sub { font-size: 12px; color: #8899a6; }
.dragon-card { background: #0f1419; border-radius: 10px; padding: 12px 14px; }
.dragon-line { display: flex; align-items: baseline; flex-wrap: wrap; gap: 10px; }
.dragon-name { font-size: 16px; font-weight: 700; color: #e1e8ed; }
.dragon-code { font-size: 13px; color: #8899a6; }
.dragon-board { color: #fbbf24; font-weight: 700; }
.dragon-reason { margin: 8px 0 0; font-size: 12px; line-height: 1.6; color: #d97706; }
/* 金字塔 */
.pyramid { display: flex; flex-direction: column; gap: 10px; align-items: center; }
.pyr-row { display: flex; justify-content: center; width: 100%; }
.pyr-band {
  width: 100%;
  background: linear-gradient(180deg, #223041, #1b2735);
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 10px 14px;
  transition: width .3s ease;
}
.pyr-band.empty { background: transparent; border-style: dashed; border-color: #2d3748; }
.pyr-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.pyr-title { font-size: 15px; font-weight: 700; color: #fbbf24; }
.layer-tag { flex: none; }
.pyr-count { font-size: 12px; color: #cbd5e1; }
.pyr-promo { margin-left: auto; font-size: 12px; color: #8899a6; }
.pyr-promo.ok { color: #6ee7b7; }
.pyr-promo.mid { color: #fbbf24; }
.pyr-promo.bad { color: #fca5a5; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: #0f1419; border: 1px solid #2d3748; border-radius: 999px;
  padding: 4px 10px; font-size: 12px;
}
.chip.role-总龙头 { border-color: #ef4444; box-shadow: 0 0 0 1px rgba(239,68,68,.35); }
.chip-name { color: #e1e8ed; font-weight: 600; }
.chip-promoted { font-size: 11px; }
.chip-promoted.ok { color: #6ee7b7; }
.chip-promoted.bad { color: #94a3b8; }
.chip-seal { color: #a8b7c4; font-size: 11px; }
.chip-pct { font-weight: 600; font-size: 11px; }
.gap-hint { color: #4a5568; font-size: 12px; padding: 2px 0; }
.none-hint { color: #6b7c8c; font-size: 13px; }
.detail-title { display: inline-flex; align-items: center; gap: 8px; }
.detail-rate { color: #fbbf24; font-size: 12px; }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.detail-grid h4 { margin: 0 0 8px; font-size: 13px; }
.ok-text { color: #6ee7b7; }
.bad-text { color: #fca5a5; }
.pullback { margin-left: 8px; color: #fca5a5; font-size: 11px; }
.missing { color: #6b7c8c; font-size: 12px; }
.up { color: #ef4444; }
.down { color: #3b82f6; }
@media (max-width: 800px) { .detail-grid { grid-template-columns: 1fr; } }
</style>
