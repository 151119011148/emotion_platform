<template>
  <div class="mainline-page">
    <div class="page-header">
      <h2>日内核心</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>日内核心五要素 + 生命周期 + 龙头分工 + 轮动信号，与打分引擎同源</template>
      <div class="intro-body">
        <p>日内核心=当日涨停聚集度最高的行业；只有连续 3 个交易日（含今天）该行业涨停≥5 家，才被收集为主线龙头。成交额聚集度是人工口径。</p>
      </div>
    </el-alert>

    <el-empty v-if="!loading && !vo" description="当日无日内核心：行情明细未回补，或当日没有涨停股" />

    <template v-if="vo">
      <!-- 主线收集状态 -->
      <el-alert v-if="vo.mainIndustry"
        :type="vo.mainlineConfirmed ? 'success' : 'warning'"
        :closable="false" show-icon class="confirm-bar"
        :title="vo.mainlineConfirmed
          ? `「${vo.mainIndustry}」已连续 ${vo.persistenceDays} 个交易日有热度（≥5 家涨停），已收集为主线龙头`
          : `「${vo.mainIndustry}」今日最热但热度未满连续 3 个交易日（当前 ${vo.persistenceDays ?? 0} 天），暂为日内核心，未收集为主线龙头`" />

      <el-alert v-if="vo.mainThemeMatched === false" type="warning" :closable="false" show-icon
        title="题材行与日内核心行业没有对上：催化剂硬度取的是默认值，可信度打折（去主线龙头页登记该行业题材可修正）"
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
          <h3>日内核心五要素 <span class="sub">{{ vo.mainIndustry || '—' }}</span></h3>
          <span class="scale">核心涨停 {{ nz(vo.mainZt) }}/{{ nz(vo.ztTotal) }} · 核心最高板 {{ nz(vo.mainMaxBoard) }}/{{ nz(vo.maxBoard) }}</span>
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
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed } from '../utils/scores'

const route = useRoute()

const ACTION_LABEL = { PROMOTE: '晋级', HOLD: '在位', BREAK: '断板', ABSENT: '缺席' }
const ACTION_TYPE = { PROMOTE: 'success', HOLD: 'primary', BREAK: 'danger', ABSENT: 'info' }
const STAGE_TYPE = { 萌芽: 'info', 确认: 'primary', 扩散: 'warning', 亢奋: 'danger', 退潮: 'info' }
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
    const res = await prdApi.mainline(date.value).catch(() => null)
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
