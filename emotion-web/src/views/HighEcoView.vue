<template>
  <div class="higheco-page">
    <div class="page-header">
      <h2>D5 · 高位生态
        <DimIntroTip title="「点—面—力」三层结构，与打分引擎同源"
          body="阵眼（点）= 你账号人工配置的 t_anchor（起止区间内恒定，断板日仍跟踪）；抱团（面）与监管（力）是公开事实。监管名单靠 /api/market/surveillance/refresh 回补过才全——事件窗为空时压制/反馈整支未评，不是「当天 0 分」。"/>
      </h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>

    <!-- 总分 + 定性 -->
    <section class="score-banner" v-if="vo">
      <div class="score-cell">
        <span class="score-noop">D5 总分</span>
        <span class="score-num">{{ vo.score == null ? '未评' : vo.score }}</span>
      </div>
      <el-tag v-if="vo.level" :type="levelTag(vo.level)" effect="dark" size="large">{{ vo.level }}</el-tag>
      <div class="score-meta">
        <span>当日最高连板 H = {{ vo.h }} 板</span>
        <span v-if="vo.coalition" class="thin">高位阈值 ≥ {{ vo.coalition.highThreshold }} 板</span>
      </div>
    </section>

    <!-- D5 分走势：点任意一天切日期 -->
    <DimScoreCurve :rows="curveRows" :selected="date" name="高位生态" @select="date = $event" />

    <el-empty v-if="!loading && !vo" description="当日无高位生态数据：明细未回补，或取数链路异常" />

    <template v-if="vo">
      <el-alert v-if="vo.notes?.length" type="warning" :closable="false" show-icon class="notes-bar"
        :title="'未评口径提醒：' + vo.notes.join('；')" />

      <!-- 强制风控红条：引擎 force flag 触发（死亡结构/监管龙头断板）时的顶级告警 -->
      <el-alert v-if="vo.forceRisk?.triggered" type="error" :closable="false" show-icon class="force-bar">
        <template #title><strong>强制风控</strong> · D5 总分 {{ vo.forceRisk.capScore == null ? '—' : vo.forceRisk.capScore }}（封顶）</template>
        <p class="force-body">{{ vo.forceRisk.reason }}</p>
      </el-alert>

      <!-- ① 阵眼个体（点） -->
      <section class="block" v-loading="loading">
        <div class="block-head">
          <h3>① 阵眼个体 <span class="sub">点 · 人工配置 · 权重 35%</span></h3>
          <span v-if="vo.anchor?.score != null" class="sub-score">得分 {{ vo.anchor.score }}</span>
        </div>
        <el-alert v-if="!vo.anchor?.configured" type="info" :closable="false" show-icon
          title="当日无在位人工阵眼（本子项未评，登记入口已下线，可用 /api/anchors 接口登记后可计入）" style="margin-bottom: 12px" />
        <div v-for="a in vo.anchor?.items || []" :key="a.id" class="anchor-card">
          <div class="anchor-line">
            <el-tag type="danger" effect="dark" size="small">{{ a.roleLabel || '阵眼' }}</el-tag>
            <span class="anchor-name">{{ a.name }}</span>
            <span class="anchor-code">{{ a.code }}</span>
            <el-tag size="small" effect="plain">{{ a.industry || '行业未登记' }}</el-tag>
            <span class="anchor-board">{{ a.consecutive == null ? '—' : a.consecutive + ' 板' }}</span>
            <span :class="pctClass(a.chg)">{{ a.chg == null ? '—' : signed(a.chg) + '%' }}</span>
            <el-tag :type="actionType(a.action)" size="small">{{ a.actionLabel || '未评' }}</el-tag>
            <el-tag :type="a.realTop ? 'success' : 'warning'" size="small" effect="plain">
              {{ a.realTop ? '= 实际最高板（未易主）' : '市场高度已易主' }}
            </el-tag>
          </div>
          <div class="anchor-meta">
            <span>生效第 {{ a.activeDays ?? '—' }} 天 · {{ fmtDate(a.startDate) }} 起{{ a.endDate ? ' ~ ' + fmtDate(a.endDate) : '（长期）' }}</span>
            <span v-if="a.sealAmount != null">封单 {{ fmtAmount(a.sealAmount) }}</span>
            <span v-if="a.lifecycle?.maxConsecutive != null">周期最高 {{ a.lifecycle.maxConsecutive }} 板</span>
            <span v-if="a.lifecycle?.breakTimes != null">断板 {{ a.lifecycle.breakTimes }} 次</span>
            <span v-if="a.lifecycle?.monitor" class="warn">
              监管 {{ kindLabel(a.lifecycle.monitor) }}
            </span>
          </div>
          <div class="anchor-scores">
            <span>行为 {{ nz(a.actionScore) }}</span>
            <span>高度 {{ nz(a.heightScore) }}</span>
            <span>封板 {{ nz(a.sealScore) }}</span>
            <span>主线一致 {{ nz(a.consistScore) }}</span>
            <span class="score-total">40/25/20/15 → {{ a.score == null ? '未评' : a.score }}</span>
          </div>
          <p v-if="a.consistWarn" class="anchor-warn">⚠️ {{ a.consistWarn }}（一致性 60）</p>
        </div>
      </section>

      <!-- ② 抱团与资金（面） -->
      <section class="block" v-if="vo.coalition" v-loading="loading">
        <div class="block-head">
          <h3>② 抱团与资金 <span class="sub">面 · 结构 60% + 强度 40% · 权重 30%</span></h3>
          <div class="head-right">
            <span v-if="vo.coalition.score != null" class="sub-score">得分 {{ vo.coalition.score }}</span>
            <el-tag v-if="vo.coalition.adjust" type="danger" size="small" effect="dark" class="guard-tag">{{ vo.coalition.adjust }}</el-tag>
          </div>
        </div>
        <div class="stat-grid">
          <div class="stat"><span class="stat-label">高位(≥{{ vo.coalition.highThreshold }}板)</span><span class="stat-value">{{ vo.coalition.highCount }}</span></div>
          <div class="stat"><span class="stat-label">中位(3-4板)</span><span class="stat-value">{{ vo.coalition.midCount }}</span></div>
          <div class="stat"><span class="stat-label">低位(2板)</span><span class="stat-value">{{ vo.coalition.lowCount }}</span></div>
          <div class="stat"><span class="stat-label">空间板(==H)</span><span class="stat-value">{{ vo.coalition.topCount }}</span></div>
          <div class="stat"><span class="stat-label">梯队</span><span class="stat-value" :class="vo.coalition.hasGap ? 'down' : 'up'">{{ vo.coalition.hasGap ? '断层' : '无断层' }}</span></div>
          <div class="stat"><span class="stat-label">高位封单占比</span><span class="stat-value">{{ vo.coalition.highSealRatio == null ? '—' : pct(vo.coalition.highSealRatio) }}</span></div>
          <div class="stat"><span class="stat-label">高位溢价</span><span class="stat-value" :class="pctClass(vo.coalition.highPrem)">{{ vo.coalition.highPrem == null ? '—' : signed(vo.coalition.highPrem) + '%' }}</span></div>
          <div class="stat"><span class="stat-label">高位晋级率</span><span class="stat-value">{{ vo.coalition.highJr == null ? '—' : pct(vo.coalition.highJr) }}</span></div>
        </div>
        <!-- 抱团金字塔 -->
        <div class="pyramid">
          <div class="tier">
            <span class="tier-label">高位</span>
            <div class="tier-bar"><div class="tier-fill high" :style="{ width: barW(vo.coalition.highCount) }"></div></div>
            <span class="tier-num">{{ vo.coalition.highCount }}</span>
          </div>
          <div class="tier">
            <span class="tier-label">中位</span>
            <div class="tier-bar"><div class="tier-fill mid" :style="{ width: barW(vo.coalition.midCount) }"></div></div>
            <span class="tier-num">{{ vo.coalition.midCount }}</span>
          </div>
          <div class="tier">
            <span class="tier-label">低位</span>
            <div class="tier-bar"><div class="tier-fill low" :style="{ width: barW(vo.coalition.lowCount) }"></div></div>
            <span class="tier-num">{{ vo.coalition.lowCount }}</span>
          </div>
        </div>
        <div v-if="vo.coalition.structureScore != null || vo.coalition.strengthScore != null" class="anchor-scores">
          <span>结构 {{ nz(vo.coalition.structureScore) }}</span>
          <span>强度 {{ nz(vo.coalition.strengthScore) }}</span>
          <span class="score-total">60/40 → {{ vo.coalition.score == null ? '未评' : vo.coalition.score }}</span>
        </div>
      </section>

      <!-- ③ 监管压制 + ④ 监管反馈（力 + 市场反应） -->
      <section class="block" v-if="vo.pressure || vo.feedback" v-loading="loading">
        <div class="block-head">
          <h3>③ 监管压制 + ④ 监管反馈 <span class="sub">力 · 20% + 15%</span></h3>
        </div>
        <div class="two-col">
          <div class="half-block">
            <div class="half-head">
              <h4>③ 监管压制 <span class="sub">家数40 / 高位占比35 / 扩散25</span></h4>
              <div class="head-right">
                <span v-if="vo.pressure?.score != null" class="sub-score">得分 {{ vo.pressure.score }}</span>
                <el-tag v-if="vo.pressure.adjust" type="danger" size="small" effect="dark" class="guard-tag">{{ vo.pressure.adjust }}</el-tag>
              </div>
            </div>
            <div class="stat-grid">
              <div class="stat"><span class="stat-label">SEVERE/EXCH 在列</span><span class="stat-value">{{ vo.pressure?.survCount ?? '—' }}</span></div>
              <div class="stat"><span class="stat-label">高位监管</span><span class="stat-value">{{ vo.pressure?.highSurvCount ?? '—' }} / {{ vo.pressure?.highSurvRatio == null ? '—' : pct(vo.pressure.highSurvRatio) }}</span></div>
              <div class="stat"><span class="stat-label">板块扩散(最多)</span><span class="stat-value">{{ vo.pressure?.maxSectorSurv ?? '—' }}<span class="stat-mini" v-if="vo.pressure?.maxSectorName"> {{ vo.pressure.maxSectorName }}</span></span></div>
            </div>
          </div>
          <div class="half-block">
            <div class="half-head">
              <h4>④ 监管反馈 <span class="sub">核按钮 / 均涨五态</span></h4>
              <span v-if="vo.feedback?.score != null" class="sub-score">得分 {{ vo.feedback.score }}</span>
            </div>
            <div class="stat-grid">
              <div class="stat"><span class="stat-label">监管股均涨</span><span class="stat-value" :class="pctClass(vo.feedback?.survAvgChg)">{{ vo.feedback?.survAvgChg == null ? '—' : signed(vo.feedback.survAvgChg) + '%' }}</span></div>
              <div class="stat"><span class="stat-label">核按钮/跌停</span><span class="stat-value" :class="vo.feedback?.survNuke ? 'down' : ''">{{ vo.feedback?.survNuke ?? '—' }} 只</span></div>
            </div>
          </div>
        </div>
      </section>

      <!-- 监管池 · 全生命周期轨迹 -->
      <section class="block">
        <div class="block-head">
          <h3>监管池 · 全生命周期 <span class="sub">D+1→出监管颜色编码轨迹；进监管次日最见监管无效/生效</span></h3>
        </div>
        <MonitorHeatmap :date="date" />
      </section>

      <!-- 交叉信号 -->
      <section class="block">
        <div class="block-head"><h3>交叉信号 <span class="sub">不计入分数，直接触发风控标签与操作指令</span></h3></div>
        <el-empty v-if="!vo.signals?.length" description="今日无交叉信号" :image-size="60" />
        <ul v-else class="rotation-list">
          <li v-for="(s, i) in vo.signals" :key="i">
            <el-tag :type="signalType(s.level)" size="small" effect="dark">{{ s.label }}</el-tag>
            <span class="signal-code">{{ s.code }}</span>
          </li>
        </ul>
      </section>

      <!-- 子项明细（⑤），默认折叠 -->
      <section class="block score-block" :class="{ 'score-collapsed': !detailOpen }">
        <div class="block-head score-head" @click="detailOpen = !detailOpen">
          <h3>子项明细 <span class="fold-tag">{{ detailOpen ? '收起 ▲' : '展开 ▼' }}</span></h3>
          <span class="sub">权重 35/30/20/15，未评子项剔除后按已评权重归一</span>
        </div>
        <div v-show="detailOpen" class="dim-row">
          <span>阵眼 <b>{{ vo.anchor?.score ?? '未评' }}</b> <i>×0.35</i></span>
          <span>抱团 <b>{{ vo.coalition?.score ?? '未评' }}</b> <i>×0.30</i></span>
          <span>压制 <b>{{ vo.pressure?.score ?? '未评' }}</b> <i>×0.20</i></span>
          <span>反馈 <b>{{ vo.feedback?.score ?? '未评' }}</b> <i>×0.15</i></span>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { d5Api, recordApi } from '../api/modules'
import { signed } from '../utils/scores'
import DimScoreCurve from '../components/DimScoreCurve.vue'
import DimIntroTip from '../components/DimIntroTip.vue'
import MonitorHeatmap from '../components/MonitorHeatmap.vue'

const route = useRoute()

const LEVEL_TAG = { 健康: 'success', 可控: 'primary', 警戒: 'warning', 危险: 'danger', 崩塌: 'danger' }
const ACTION_TYPE = {
  JIN_JIA: 'success', FAN_BAO: 'primary', HANG_TIAO: 'info', DUAN_BAN: 'warning', HE_PAN: 'danger'
}
const SIGNAL_TYPE = { danger: 'danger', warn: 'warning', info: 'info' }
const KIND_LABEL = { SEVERE: '严重异常波动', EXCH: '交易所监管', ZD: '异常波动' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)
/** 曲线往回取多少个日历日去凑最近 30 个交易日：留足长假，取 90 天。 */
const LOOKBACK_DAYS = 90
const CURVE_ROWS = 30
const curveRows = ref([])
// 子项明细默认折叠
const detailOpen = ref(false)

function nz(v) {
  if (v == null) return '—'
  const n = Math.round(Number(v))
  return Number.isNaN(n) ? '—' : String(n)
}
function fmtDate(d) {
  if (!d) return '—'
  return String(d).slice(5).replace('-', '/')
}
function fmtAmount(n) {
  const v = Number(n)
  if (Number.isNaN(v)) return '—'
  if (Math.abs(v) >= 1e8) return (v / 1e8).toFixed(2) + ' 亿'
  if (Math.abs(v) >= 1e4) return (v / 1e4).toFixed(0) + ' 万'
  return String(v)
}
/** 0-1 比例 → 百分数 */
function pct(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return (n * 100).toFixed(0) + '%'
}
/** 最大高/中/低位家数作满刻，刻画相对关系（都为 0 则统一给一格） */
function barW(n) {
  const { highCount: h, midCount: m, lowCount: l } = vo.value?.coalition || {}
  const max = Math.max(h || 0, m || 0, l || 0)
  if (!max || !n) return n && n > 0 ? '4%' : '0%'
  return Math.max(6, Math.round((n / max) * 100)) + '%'
}
function pctClass(p) {
  if (p == null || Number.isNaN(Number(p))) return ''
  return Number(p) > 0 ? 'up' : Number(p) < 0 ? 'down' : ''
}
function levelTag(l) { return LEVEL_TAG[l] || 'info' }
function actionType(a) { return ACTION_TYPE[a] || 'info' }
function signalType(l) { return SIGNAL_TYPE[l] || 'info' }
function kindLabel(k) { return KIND_LABEL[k] || k || '—' }
function statusClass(s) {
  if (!s) return ''
  if (s.includes('涨停')) return 'up'
  if (s.includes('核按钮') || s.includes('断板') || s.includes('绿盘')) return 'down'
  return 'warn'
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
    const res = await d5Api.high(date.value)
    vo.value = res?.data || null
    // 曲线与异动监管页同一取数口：range 返回按日升序，切出最近一段直接喂图
    const rangeRes = await recordApi.getRange(shiftDays(date.value, LOOKBACK_DAYS), date.value).catch(() => null)
    curveRows.value = ((rangeRes && rangeRes.data) || []).slice(-CURVE_ROWS).map((r) => ({
      date: r.tradeDate,
      score: r.scoreHigh
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
.higheco-page {
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
.sub {
  font-size: 12px;
  color: #8899a6;
  font-weight: 400;
  margin-left: 6px;
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
/* 总分横幅 */
.score-banner {
  display: flex;
  align-items: center;
  gap: 18px;
  background: #1a2332;
  border-radius: 12px;
  padding: 18px 22px;
  margin-bottom: 20px;
}
.score-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.score-noop {
  font-size: 12px;
  color: #8899a6;
}
.score-num {
  font-size: 30px;
  font-weight: 800;
  color: #e1e8ed;
  line-height: 1;
}
.score-meta {
  margin-left: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #8899a6;
  text-align: right;
}
.notes-bar {
  margin-bottom: 16px;
}
.force-bar {
  margin-bottom: 16px;
  border: 1px solid #f56c6c;
  box-shadow: 0 0 0 4px rgba(245, 108, 108, 0.15);
}
.force-body {
  margin: 4px 0 0;
  line-height: 1.6;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.guard-tag {
  max-width: 320px;
  white-space: normal;
  height: auto;
  line-height: 1.5;
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

/* 折叠头（与其他维度页同一套） */
.score-head { cursor: pointer; user-select: none; border-radius: 8px; transition: background .15s; }
.score-head:hover { background: rgba(255, 255, 255, .025); }
.score-head:hover h3 { color: #fff; }
.fold-tag {
  display: inline-block;
  margin-left: 10px;
  padding: 2px 10px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .2px;
  color: #9fb2c6;
  background: #22303f;
  border: 1px solid #3a4d63;
  border-radius: 999px;
  line-height: 1.7;
  transition: color .18s, border-color .18s, background .18s, transform .12s;
  vertical-align: middle;
}
.score-head:hover .fold-tag { color: #ffd166; border-color: #ffd166; background: #2b3d52; }
.score-head:active .fold-tag { transform: translateY(1px); background: #2f4258; }
.score-block.score-collapsed .block-head { margin-bottom: 0; border-bottom: 1px dashed #33455a; }
.sub-score {
  font-size: 15px;
  font-weight: 700;
  color: #fbbf24;
}
/* 阵眼卡 */
.anchor-card {
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 12px;
}
.anchor-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.anchor-name {
  font-size: 16px;
  font-weight: 700;
  color: #e1e8ed;
}
.anchor-code {
  font-size: 12px;
  color: #8899a6;
}
.anchor-board {
  color: #fbbf24;
  font-weight: 700;
}
.anchor-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 8px;
  font-size: 12px;
  color: #8899a6;
}
.anchor-meta .warn {
  color: #9b6b3d;
}
.anchor-scores {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 8px;
  font-size: 12px;
  color: #cbd5e1;
}
.score-total {
  color: #fbbf24;
  font-weight: 700;
}
.anchor-warn {
  margin: 8px 0 0;
  font-size: 12px;
  color: #d97706;
}
/* 统计格 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(150px, 100%), 1fr));
  gap: 12px;
}
.stat {
  background: #0f1419;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stat-label {
  font-size: 12px;
  color: #8899a6;
}
.stat-value {
  font-size: 18px;
  font-weight: 700;
  color: #e1e8ed;
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.stat-mini {
  font-size: 12px;
  font-weight: 400;
  color: #8899a6;
}
/* 抱团金字塔 */
.pyramid {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tier {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tier-label {
  width: 40px;
  font-size: 12px;
  color: #8899a6;
}
.tier-bar {
  flex: 1;
  height: 16px;
  background: #0f1419;
  border-radius: 4px;
  overflow: hidden;
}
.tier-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.4s;
}
.tier-fill.high { background: linear-gradient(90deg, #3b82f6, #fbbf24); }
.tier-fill.mid { background: #3b82f6; }
.tier-fill.low { background: #2d3748; }
.tier-num {
  width: 28px;
  text-align: right;
  font-size: 13px;
  color: #e1e8ed;
  font-weight: 700;
}
/* 监管两栏 */
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 800px) {
  .two-col { grid-template-columns: 1fr; }
}
.half-block {
  background: #0f1419;
  border-radius: 10px;
  padding: 14px;
}
.half-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.half-head h4 {
  margin: 0;
  font-size: 13px;
  color: #cbd5e1;
}
/* 信号 */
.rotation-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.rotation-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed #2d3748;
  font-size: 13px;
  color: #e1e8ed;
}
.rotation-list li:last-child {
  border-bottom: none;
}
.signal-code {
  font-size: 11px;
  color: #6b7c8c;
}
/* 子项明细 */
.dim-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  font-size: 13px;
  color: #cbd5e1;
}
.dim-row b {
  color: #fbbf24;
}
.dim-row i {
  font-style: normal;
  color: #8899a6;
  font-size: 11px;
}
.up { color: #ef4444; }
.down { color: #3b82f6; }
.warn { color: #d97706; }
</style>