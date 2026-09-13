<template>
  <div class="mainline-page">
    <div class="page-header">
      <h2>主线与日内核心
        <DimIntroTip title="主线区（已确认·打D2分）+ 雷达区（候选池·不打D2分）双轨，与打分引擎同源"
          body="雷达区=当日所有有涨停的行业扫描（只标连续天数与强度）；连续 3 个交易日（含今天）该行业涨停≥5 家，才晋级主线区打 D2 分。雷达区可点击「升级到主线区」落人工标记。" />
      </h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>

    <DimScoreBlock :date="date" dim-key="theme_main" title="D2 · 主线明确度" />

    <!-- D2 分走势：点任意一天切日期 -->
    <DimScoreCurve :rows="curveRows" :selected="date" name="D2主线明确度" @select="date = $event" />

    <el-empty v-if="!loading && !vo" description="当日无日内核心：行情明细未回补，或当日没有涨停股" />

    <template v-if="vo">
      <!-- 主线收集状态 -->
      <el-alert v-if="vo.mainIndustry"
        :type="vo.hasMainline ? 'success' : 'warning'"
        :closable="false" show-icon class="confirm-bar"
        :title="(vo.manuallyMarked ? '🏷人工主线：' : '') + mainlineTitle()" />

      <el-alert v-if="!vo.hasMainline && vo.mainlineSignal" type="warning" :closable="false" show-icon
        class="confirm-bar" :title="vo.mainlineSignal"
        style="margin-top: -6px" />

      <el-alert v-if="vo.mainThemeMatched === false" type="warning" :closable="false" show-icon
        title="题材行与日内核心行业没有对上：催化剂硬度取的是默认值，可信度打折（登记入口已下线，可用 /api/themes 接口登记该行业题材修正）"
        style="margin: 16px 0" />

      <!-- 生命周期轨道 -->
      <section class="block" v-loading="loading">
        <div class="block-head">
          <h3>生命周期</h3>
          <el-tag v-if="vo.lifecycleStage" :type="STAGE_TYPE[vo.lifecycleStage] || 'info'" effect="dark">
            {{ vo.lifecycleStage }}
          </el-tag>
        </div>
        <div class="lifecycle-track">
          <template v-for="(st, i) in vo.lifecycle || []" :key="st">
            <div class="stage" :class="{ active: st === vo.lifecycleStage }">
              <span class="stage-dot"></span>
              <span class="stage-name">{{ st }}</span>
            </div>
            <div v-if="i < (vo.lifecycle?.length || 0) - 1" class="stage-line"></div>
          </template>
        </div>
        <p class="stage-note">{{ STAGE_NOTE[vo.lifecycleStage] || '当日无日内核心，生命周期不可判' }}</p>
      </section>

      <!-- 五要素 -->
      <section class="block" v-loading="loading">
        <div class="block-head">
          <h3>主线五要素 <span class="sub">{{ vo.mainIndustry || '—' }}</span></h3>
          <span class="scale">
            <template v-if="vo.radarTopIndustry && vo.radarTopIndustry !== vo.mainIndustry">
              今日候选榜首「{{ vo.radarTopIndustry }}」≠ 主线「{{ vo.mainIndustry }}」 ·
            </template>
            核心涨停 {{ nz(vo.mainZt) }}/{{ nz(vo.ztTotal) }} · 核心最高板 {{ nz(vo.mainMaxBoard) }}/{{ nz(vo.maxBoard) }}
          </span>
        </div>
        <div class="elem-grid">
          <div class="elem">
            <span class="elem-label">涨停聚集度</span>
            <span class="elem-value">{{ pctText(vo.ztGatherPct) }}</span>
            <span class="elem-sub">自动 · 权重 25%</span>
          </div>
          <div class="elem">
            <span class="elem-label">高度聚集度</span>
            <span class="elem-value">{{ pctText(vo.heightGatherPct) }}</span>
            <span class="elem-sub">自动 · 权重 25%</span>
          </div>
          <div class="elem">
            <span class="elem-label">成交额聚集度</span>
            <span class="elem-value">{{ pctText(vo.amountGatherPct) }}</span>
            <span class="elem-sub">人工口径{{ vo.amountGatherPct == null ? ' · 未填=未评' : '' }}</span>
          </div>
          <div class="elem">
            <span class="elem-label">催化剂硬度</span>
            <span class="elem-value">
              <el-rate v-if="vo.catalystHardness != null" :model-value="vo.catalystHardness" disabled
                text-color="#fbbf24" style="--el-rate-icon-margin: 1px" />
              <span v-else class="missing">未评</span>
            </span>
            <span class="elem-sub">题材登记 · 权重 15%</span>
          </div>
          <div class="elem">
            <span class="elem-label">持续性</span>
            <span class="elem-value">{{ vo.persistenceDays != null ? vo.persistenceDays + ' 天' : '—' }}</span>
            <span class="elem-sub">连续热度 · 权重 15%（≥5家/日）</span>
          </div>
        </div>
      </section>

      <!-- 龙头分工 -->
      <section class="block" v-loading="loading">
        <div class="block-head">
          <h3>龙头分工</h3>
          <span class="scale">总龙头 50% · 中军 20% · 跟风 15% · 卡位 10% · 反包 5%</span>
        </div>

        <div class="dragon-card" v-if="vo.dragon">
          <div class="dragon-line">
            <el-tag type="danger" effect="dark" size="small">总龙头</el-tag>
            <span class="dragon-name">{{ vo.dragon.name || '—' }}</span>
            <span class="dragon-code">{{ vo.dragon.code }}</span>
            <el-tag size="small" effect="plain">{{ vo.dragon.industry || '行业未登记' }}</el-tag>
            <span class="dragon-board">{{ nz(vo.dragon.board) }} 板</span>
            <span :class="pctClass(vo.dragon.changePct)">
              {{ vo.dragon.changePct == null ? '—' : signed(vo.dragon.changePct) + '%' }}
            </span>
            <el-tag :type="ACTION_TYPE[vo.dragon.action] || 'info'" size="small">
              {{ ACTION_LABEL[vo.dragon.action] || vo.dragon.action || '—' }}
            </el-tag>
            <span v-if="vo.dragon.pullbackPct != null" class="pullback">
              自涨停回撤 -{{ Number(vo.dragon.pullbackPct).toFixed(2) }}%
            </span>
          </div>
          <p v-if="vo.dragon.reason" class="dragon-reason">判定依据：{{ vo.dragon.reason }}</p>
        </div>
        <el-alert v-else type="info" :closable="false" show-icon title="当日无总龙头（全市场没有连板股），阵眼按缺判" style="margin-bottom: 14px" />

        <div class="member-groups">
          <div class="member-group">
            <h4>中军 <span class="sub">日内核心内其余连板 ≥2</span></h4>
            <el-empty v-if="!vo.zhongJun?.length" description="无" :image-size="40" />
            <el-table v-else :data="vo.zhongJun" size="small">
              <el-table-column prop="code" label="代码" width="90" />
              <el-table-column prop="name" label="名称" width="110" />
              <el-table-column prop="industry" label="行业" min-width="110" show-overflow-tooltip />
              <el-table-column prop="board" label="连板" width="70" align="center" />
              <el-table-column label="涨跌" width="100" align="right">
                <template #default="{ row }">
                  <span :class="pctClass(row.changePct)">
                    {{ row.changePct == null ? '—' : signed(row.changePct) + '%' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="member-group">
            <h4>跟风 <span class="sub">日内核心内涨停 {{ vo.genFengCount ?? '—' }} 只</span></h4>
            <div class="ka-wei" v-if="vo.kaWei">
              <el-tag type="warning" effect="dark" size="small">卡位</el-tag>
              <span class="member-name">{{ vo.kaWei.name }}</span>
              <span class="member-code">{{ vo.kaWei.code }}</span>
              <el-tag size="small" effect="plain">{{ vo.kaWei.industry || '—' }}</el-tag>
              <span>{{ nz(vo.kaWei.board) }} 板</span>
              <el-tag :type="vo.kaWei.sealed === false ? 'danger' : 'success'" size="small" effect="plain">
                {{ vo.kaWei.sealed === false ? '今日炸板' : '封住' }}
              </el-tag>
            </div>
            <el-empty v-else description="无卡位（他题材高标）" :image-size="40" />
            <template v-if="vo.fanBao?.length">
              <h4 class="fanbao-title">反包</h4>
              <div class="fanbao-row">
                <span v-for="f in vo.fanBao" :key="f.code" class="fanbao-item">
                  <el-tag type="info" effect="dark" size="small">反包</el-tag>
                  {{ f.name }}（{{ f.code }}）
                </span>
              </div>
            </template>
          </div>
        </div>
      </section>

      <!-- 雷达区（候选池，当日有涨停的行业，不打 D2 分）→ 板块表 + 题材表 -->
      <section class="block" v-loading="loading">
        <div class="block-head">
          <h3>雷达区 · 日内核心 <span class="sub">候选池扫描，连续≥3天晋级主线</span></h3>
        </div>

        <h4 class="radar-sub">板块表 <span class="sub">全量行业 · {{ vo.radar?.length || 0 }} 个 · 只显示前 5</span></h4>
        <el-empty v-if="!vo.radar?.length" description="当日无涨停行业" :image-size="60" />
        <el-table v-else :data="boardView" size="small" class="radar-table">
          <el-table-column type="index" label="排名" width="56" align="center" :index="rankIndex" />
          <el-table-column prop="industry" label="行业" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ 'main-row': row.industry === vo.mainIndustry }">{{ row.industry }}</span>
            </template>
          </el-table-column>
          <el-table-column label="涨停" width="64" align="center">
            <template #default="{ row }">{{ row.zt }}</template>
          </el-table-column>
          <el-table-column label="聚集%" width="72" align="right">
            <template #default="{ row }">{{ row.ztGatherPct != null ? Number(row.ztGatherPct).toFixed(1) : '—' }}</template>
          </el-table-column>
          <el-table-column label="最高板" width="64" align="center">
            <template #default="{ row }">{{ nz(row.maxBoard) }}</template>
          </el-table-column>
          <el-table-column label="连续" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="FLAG_TYPE[row.flag] || 'info'" size="small" effect="plain">
                {{ row.persistenceDays }} 天{{ row.flag === 'NEW' ? ' 🆕' : row.flag === 'MAIN' ? ' ⭐主线' : '' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="板块龙头" min-width="120">
            <template #default="{ row }">
              <template v-if="row.leader">
                {{ row.leader.name }}（{{ row.leader.code }} · {{ nz(row.leader.board) }}板）
              </template>
              <span v-else class="missing">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" align="center">
            <template #default="{ row }">
              <el-button v-if="isCurrentManual(row)" size="small" type="danger" plain :loading="busy" @click="cancelPromote(row.industry)">取消升级</el-button>
              <el-button v-else size="small" type="primary" plain :loading="busy" @click="promote(row.industry)">升级</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 题材表：概念维度，一票可归多个题材，is_primary 主题材去重计数 -->
        <h4 class="radar-sub radar-sub-theme">题材表 <span class="sub">概念维度 · {{ themesData?.themes?.length || 0 }} 个 · 只显示前 5</span></h4>
        <el-alert v-if="themesData && !themeLoading" type="info" :closable="false" show-icon class="theme-notice"
          :title="`今日全市场涨停 ${nz(themesData.totalZt)} 只，已归类 ${nz(themesData.assigned)} 只，未归类 ${nz(themesData.unassigned)} 只`" />
        <el-empty v-if="themeLoading" description="题材表读取中…" :image-size="60" />
        <el-table v-else-if="themesData?.themes?.length" :data="themesView" size="small" class="radar-table">
          <el-table-column type="index" label="排名" width="52" align="center" :index="rankIndex" />
          <el-table-column label="题材" min-width="108" show-overflow-tooltip>
            <template #default="{ row }">
              <el-tag size="small" :type="row.mainLine ? 'success' : 'primary'" effect="plain">
                {{ row.name }}{{ row.mainLine ? ' ⭐主线' : '' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="涨停" width="56" align="center">
            <template #default="{ row }">{{ row.ztCount }}</template>
          </el-table-column>
          <el-table-column label="强度" width="64" align="right">
            <template #default="{ row }">{{ Number(row.strength).toFixed(1) }}</template>
          </el-table-column>
          <el-table-column label="最高板" width="58" align="center">
            <template #default="{ row }">{{ nz(row.maxBoard || 0) }}</template>
          </el-table-column>
          <el-table-column label="连续" width="82" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.continuousDays >= 3 ? 'success' : 'info'" effect="plain">
                {{ row.continuousDays }} 天{{ row.continuousDays >= 3 ? ' ⭐' : '' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="硬度" width="78" align="center">
            <template #default="{ row }">
              <el-rate :model-value="row.hardness || 0" disabled size="small" text-color="#fbbf24" />
            </template>
          </el-table-column>
          <el-table-column label="生命周期" width="74" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="STAGE_TYPE[row.status] || 'info'" effect="dark">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="关联板块" min-width="128" show-overflow-tooltip>
            <template #default="{ row }">{{ (row.industries || []).join(' + ') || '—' }}</template>
          </el-table-column>
          <el-table-column label="题材龙头" min-width="118">
            <template #default="{ row }">
              <template v-if="row.leader">{{ row.leader.name }}（{{ row.leader.code }} · {{ nz(row.leader.board) }}板）</template>
              <span v-else class="missing">—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!themeLoading" description="当日题材表为空（读取时后端会按热门行业自动回填）" :image-size="60" />
      </section>

      <!-- 轮动信号 -->
      <section class="block" v-loading="loading">
        <div class="block-head"><h3>轮动信号</h3></div>
        <el-empty v-if="!vo.rotationSignals?.length" description="暂无轮动信号" :image-size="60" />
        <ul v-else class="rotation-list">
          <li v-for="(s, i) in vo.rotationSignals" :key="i">
            <el-icon><Bell /></el-icon>{{ s }}
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'
import DimScoreBlock from '../components/DimScoreBlock.vue'
import DimScoreCurve from '../components/DimScoreCurve.vue'

const route = useRoute()

const ACTION_LABEL = { PROMOTE: '晋级', HOLD: '在位', BREAK: '断板', ABSENT: '缺席' }
const ACTION_TYPE = { PROMOTE: 'success', HOLD: 'primary', BREAK: 'danger', ABSENT: 'info' }
const STAGE_TYPE = { 萌芽: 'info', 确认: 'primary', 扩散: 'warning', 亢奋: 'danger', 退潮: 'info' }
const FLAG_TYPE = { NEW: 'info', WATCH: 'warning', MAIN: 'success', MANUAL: 'primary' }
const STAGE_NOTE = {
  萌芽: '主线刚冒头，持续性不足两天，试错仓为主',
  确认: '持续性 ≥2 天，主线结构初步成立，可加试错仓',
  扩散: '持续性 ≥3 天，跟风响应，确定性窗口',
  亢奋: '持续性 ≥5 天或全市场 H≥7，情绪高位，只留核心',
  退潮: '总龙头断板或主线涨停腰斩，防守优先'
}

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const busy = ref(false)
const vo = ref(null)
/** 板块表只显示前 5，其余不展示（不做展开/收起）。 */
const boardView = computed(() => (vo.value?.radar || []).slice(0, 5))
/** 题材表：来自 /api/intraday/themes（后端自动回填热门行业题材），同样只看前 5。 */
const themeLoading = ref(false)
const themesData = ref(null)
const themesView = computed(() => (themesData.value?.themes || []).slice(0, 5))
/** el-table 默认 index 从 1 起但只在当前页内排：展开全部时仍按全局排名显示。 */
function rankIndex(i) {
  return i + 1
}

/** 主线收集状态标题（区分人工标记 / 自动晋级 / 未晋级）。 */
function mainlineTitle() {
  const v = vo.value
  if (v?.mainlineConfirmed) {
    return `「${v.mainIndustry}」已连续 ${v.persistenceDays ?? 0} 个交易日有热度（≥5 家涨停），已晋级为主线`
  }
  return `「${v.mainIndustry}」今日最热但热度未满连续 3 个交易日（当前 ${v.persistenceDays ?? 0} 天），暂为日内核心，未晋级主线`
}

/** 该雷达行是否是当前人工标记的主线（显示「取消升级」）。 */
function isCurrentManual(row) {
  return !!(vo.value?.manuallyMarked && row.industry === vo.value.mainIndustry)
}

async function promote(industry) {
  busy.value = true
  try {
    const res = await prdApi.promote(date.value, industry)
    vo.value = res?.data || vo.value
  } finally {
    busy.value = false
  }
}

async function cancelPromote(industry) {
  busy.value = true
  try {
    const res = await prdApi.cancelPromote(date.value, industry)
    vo.value = res?.data || vo.value
  } finally {
    busy.value = false
  }
}

/** 曲线往回取多少个日历日去凑最近 30 个交易日：留足长假，取 90 天。 */
const LOOKBACK_DAYS = 90
const CURVE_ROWS = 30
const curveRows = ref([])

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

/** 补 T00:00:00 按本地时区解析，否则 new Date('2026-09-04') 走 UTC 会少一天。 */
function shiftDays(iso, days) {
  const d = new Date(iso + 'T00:00:00')
  d.setDate(d.getDate() - days)
  return d.toLocaleDateString('en-CA')
}

async function load() {
  loading.value = true
  try {
    const res = await prdApi.mainline(date.value).catch(() => null)
    vo.value = res?.data || null
    // 题材表（后端自动回填热门行业题材）：独立取数，单独 loading
    themeLoading.value = true
    try {
      const t = await prdApi.intradayThemes(date.value).catch(() => null)
      themesData.value = t?.data || null
    } finally {
      themeLoading.value = false
    }
    // 曲线与异动监管页同一取数口：range 返回按日升序，切出最近一段直接喂图
    const rangeRes = await recordApi.getRange(shiftDays(date.value, LOOKBACK_DAYS), date.value).catch(() => null)
    curveRows.value = ((rangeRes && rangeRes.data) || []).slice(-CURVE_ROWS).map((r) => ({
      date: r.tradeDate,
      score: r.scoreThemeMain
    }))
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
.mainline-page {
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
  flex-wrap: wrap;
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
.scale {
  font-size: 12px;
  color: #8899a6;
}
/* 生命周期轨道 */
.lifecycle-track {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0 10px;
}
.stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.stage-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #2d3748;
  border: 2px solid #3d4a5c;
}
.stage.active .stage-dot {
  background: #fbbf24;
  border-color: #fbbf24;
  box-shadow: 0 0 8px rgba(251, 191, 36, 0.6);
}
.stage-name {
  font-size: 12px;
  color: #8899a6;
}
.stage.active .stage-name {
  color: #fbbf24;
  font-weight: 700;
}
.stage-line {
  flex: 1;
  height: 2px;
  background: #2d3748;
  margin-bottom: 18px;
}
.stage-note {
  margin: 0;
  font-size: 12px;
  color: #8899a6;
}
/* 五要素 */
.elem-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 12px;
}
.elem {
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.elem-label {
  font-size: 12px;
  color: #8899a6;
}
.elem-value {
  font-size: 18px;
  font-weight: 700;
  color: #e1e8ed;
  display: flex;
  align-items: center;
  min-height: 24px;
}
.elem-sub {
  font-size: 11px;
  color: #6b7c8c;
}
/* 龙头分工 */
.dragon-card {
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
}
.dragon-reason {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #d97706;
}
.confirm-bar {
  margin-bottom: 16px;
}
.dragon-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.dragon-name {
  font-size: 16px;
  font-weight: 700;
  color: #e1e8ed;
}
.dragon-code {
  font-size: 12px;
  color: #8899a6;
}
.dragon-board {
  color: #fbbf24;
  font-weight: 700;
}
.member-groups {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 800px) {
  .member-groups {
    grid-template-columns: 1fr;
  }
}
.member-group h4 {
  margin: 0 0 10px;
  font-size: 13px;
  color: #cbd5e1;
}
.fanbao-title {
  margin-top: 14px !important;
}
.ka-wei {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 13px;
  color: #e1e8ed;
}
.member-name {
  font-weight: 700;
}
.member-code {
  font-size: 12px;
  color: #8899a6;
}
.fanbao-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: #e1e8ed;
}
.fanbao-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
/* 轮动 */
.rotation-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.main-row {
  color: #fbbf24;
  font-weight: 700;
}
.radar-sub {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #e1e8ed;
}
.radar-sub .sub {
  color: #9fb2c6;
}
.radar-sub-theme {
  margin-top: 22px;
  padding-top: 14px;
  border-top: 1px dashed #2d3748;
}
.radar-table :deep(.el-table__row) {
  color: #cbd5e1;
}
.theme-notice {
  margin: 6px 0 12px;
}
.rotation-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed #2d3748;
  font-size: 13px;
  color: #cbd5e1;
}
.rotation-list li:last-child {
  border-bottom: none;
}
.rotation-list .el-icon {
  color: #fbbf24;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.pullback {
  color: #3b82f6;
  font-size: 12px;
}
.missing {
  font-size: 13px;
  color: #6b7c8c;
}
</style>
