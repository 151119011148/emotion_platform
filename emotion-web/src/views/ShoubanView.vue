<template>
  <div class="shouban-page">
    <div class="page-header">
      <h2>首板生态
        <DimIntroTip title="纯 T 日试错端：只看今天新诞生的首板——资金还愿不愿意打新板、封不封得住、钱往哪个新方向试"
          body="1进2晋级率 / 首板溢价 / 1进2大面是 T-1→T 的兑现口径，已归入连板生态低位层（连板页 2 板层）。封住/未封住名单默认收起，点击展开。" />
      </h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>

    <!-- 首板生态打分明细（score-detail first 维 eval 树，纯 T 日），默认折叠 -->
    <section class="block score-block" :class="{ 'score-collapsed': !scoreOpen }" v-loading="scoring.detailLoading">
      <div class="block-head score-head" @click="scoreOpen = !scoreOpen">
        <h3>首板生态打分 <span class="fold-tag">{{ scoreOpen ? '收起 ▲' : '展开 ▼' }}</span></h3>
        <span class="dim-score" :class="bandClass(firstDim?.score)">
          {{ firstDim?.score == null ? '未评' : Number(firstDim.score).toFixed(2) + ' 分' }}
        </span>
      </div>
      <div v-show="scoreOpen">
      <el-empty v-if="!firstDim" :description="'当日读数未取到，首板生态未评（不计入分母）'" :image-size="60" />
      <template v-else>
        <ol class="formula">
          <li>全部子项只看 <b>T 日当天</b>新首板：数量、封板率、炸板率、封单质量（均封单 0.6 + 一字占比 0.4）、题材聚集度。</li>
          <li>叶子读数命中阈值阶梯 → 0-100 分；结构子分＝Σ(叶权 × 叶分) ÷ Σ已评叶权，未评剔出分母不按 0 计。</li>
          <li>大盘背离（大盘分&lt;40 / 强制退潮 / 跌停≥10 家）时：首板数量 ×0.85、首板封板率 −10，修正留痕在下表。</li>
        </ol>
        <table class="score-table">
          <thead>
            <tr>
              <th class="col-name">项目</th>
              <th class="col-w">权重</th>
              <th class="col-raw">今日读数</th>
              <th class="col-bg">全局背景</th>
              <th class="col-band">读数 / 命中档（阈值 → 档分）</th>
              <th class="col-fix">修正系数</th>
              <th class="col-score">得分</th>
              <th class="col-contrib">加权贡献</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in scoreRows" :key="row.key">
              <tr v-if="row.kind === 'group'" class="group-row">
                <td class="cell-name">{{ row.label }}</td>
                <td>×{{ fmtWeight(row.weight) }}</td>
                <td>—</td>
                <td class="bg-cell">{{ row.bg || '—' }}</td>
                <td class="band-cell">{{ row.note || '各叶加权合成' }}</td>
                <td class="fix-cell">{{ row.adjustment || '—' }}</td>
                <td><span :class="bandClass(row.score)">{{ scoreText(row.score) }}</span></td>
                <td class="contrib">{{ contribText(row.weight, row.score) }}</td>
              </tr>
              <tr v-else class="leaf-row" :class="{ solo: row.kind === 'solo' }">
                <td class="cell-name"><span class="indent" v-if="row.kind === 'leaf'">└</span>{{ row.label }}</td>
                <td>×{{ fmtWeight(row.weight) }}</td>
                <td class="raw-cell">{{ rawText(row.sourceKey, row.raw) }}</td>
                <td class="bg-cell">{{ row.bg || '—' }}</td>
                <td class="band-cell">
                  <span v-if="row.bandHit">{{ row.bandHit }}</span>
                  <span v-else-if="row.note">{{ row.note }}</span>
                  <span v-else class="missing">—</span>
                </td>
                <td class="fix-cell">{{ row.adjustment || '—' }}</td>
                <td><span :class="bandClass(row.score)">{{ scoreText(row.score) }}</span></td>
                <td class="contrib">{{ row.kind === 'solo' ? contribText(row.weight, row.score) : '' }}</td>
              </tr>
            </template>
          </tbody>
        </table>
        <p class="score-foot" v-if="firstDim.note">
          <b class="whistle-note">{{ firstDim.note }}</b>
        </p>
      </template>
      </div>
    </section>

    <!-- D4 分走势：点任意一天切日期 -->
    <DimScoreCurve :rows="curveRows" :selected="date" name="首板生态" @select="date = $event" />

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">首板封住</span>
        <span class="stat-value">{{ summary ? nz(summary.sealedCount) : '—' }}</span>
        <span class="stat-sub">涨停池 1 板</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板炸板</span>
        <span class="stat-value">{{ summary ? nz(summary.bombedCount) : '—' }}</span>
        <span class="stat-sub">{{ prevReady ? '首板尝试未封住' : '昨日明细缺失，不可判定' }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板封板率</span>
        <span class="stat-value">{{ pctText(summary?.sealedRate) }}</span>
        <span class="stat-sub">封住 ÷（封住 + 首板炸板）</span>
      </div>
      <div class="stat">
        <span class="stat-label">一字首板</span>
        <span class="stat-value">{{ summary ? nz(summary.yiziCount) : '—' }}</span>
        <span class="stat-sub">占比 {{ pctText(summary?.yiziRatio) }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板均封单</span>
        <span class="stat-value">{{ summary?.avgSealAmount != null ? moneyText(summary.avgSealAmount) : '—' }}</span>
        <span class="stat-sub">封住首板封单均值</span>
      </div>
      <div class="stat">
        <span class="stat-label">首板题材聚集</span>
        <span class="stat-value">{{ pctText(summary?.themeGatherPct) }}</span>
        <span class="stat-sub">{{ summary?.topIndustry ? `最热：${summary.topIndustry} ${nz(summary.topIndustryCount)} 只` : '资金分散' }}</span>
      </div>
    </div>

    <!-- 试错-兑现背离信号 -->
    <section class="block signal-block">
      <div class="block-head">
        <h3>试错-兑现背离</h3>
        <span class="sub">首板生态(T日试错) − 连板生态(T-1→T兑现)</span>
      </div>
      <div v-if="ecologyDivergence != null" class="divergence-banner" :class="divergenceLevel">
        🧭 {{ scoring.detail?.ecologyDivergenceLabel }}
        <span class="divergence-num">背离度 {{ signedNum(ecologyDivergence) }}</span>
      </div>
      <p v-else class="signal-empty">两维都出分后计算背离度……</p>
    </section>

    <!-- 封住 / 未封住：默认折叠，点击展开；chip 样式与连板天梯一致 -->
    <section class="block list-block">
      <div class="block-head list-head" @click="sealedOpen = !sealedOpen">
        <h3>封住名单 <span class="fold-tag ok-tag">{{ sealedOpen ? '收起 ▲' : '展开 ▼' }}</span></h3>
        <span class="list-count ok-text">{{ sealedRows.length }} 只</span>
      </div>
      <div v-show="sealedOpen" class="list-body">
        <div v-if="!sealedRows.length" class="list-empty">当日没有封住的首板（或行情明细未回补，点每日复盘拉行情）</div>
        <div v-else class="chips">
          <div v-for="r in sealedRows" :key="r.code" class="chip">
            <span class="chip-name">{{ r.name }}</span>
            <span class="chip-code">{{ r.code }}</span>
            <span class="chip-industry" :class="{ 'in-main': r.inMain }">{{ r.industry || '—' }}</span>
            <el-tag v-if="r.pattern" size="small" :type="PATTERN_TYPE[r.pattern] || 'info'" effect="plain">
              {{ PATTERN_LABEL[r.pattern] || '—' }}
            </el-tag>
            <span v-if="r.sealAmount != null" class="chip-seal">封单 {{ moneyText(r.sealAmount) }}</span>
            <span v-if="r.breakCount != null && r.breakCount > 0" class="chip-reseal">开板{{ r.breakCount }}次↩回封</span>
            <span :class="pctClass(r.changePct)" class="chip-pct">{{ r.changePct == null ? '—' : signed(r.changePct) + '%' }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="block list-block">
      <div class="block-head list-head" @click="bombedOpen = !bombedOpen">
        <h3>未封住名单 <span class="fold-tag bad-tag">{{ bombedOpen ? '收起 ▲' : '展开 ▼' }}</span></h3>
        <span class="list-count bad-text">{{ bombedRows.length }} 只</span>
      </div>
      <div v-show="bombedOpen" class="list-body">
        <el-alert v-if="!prevReady" type="warning" :closable="false" show-icon
          title="昨日明细未回补，无法判定哪些炸板属于「首板尝试」，本表暂不可算" style="margin: 8px 0" />
        <div v-else-if="!bombedRows.length" class="list-empty">当日没有首板炸板（或昨日明细未回补）</div>
        <div v-else class="chips">
          <div v-for="r in bombedRows" :key="r.code" class="chip chip-fail">
            <span class="chip-name">{{ r.name }}</span>
            <span class="chip-code">{{ r.code }}</span>
            <span class="chip-industry" :class="{ 'in-main': r.inMain }">{{ r.industry || '—' }}</span>
            <span :class="pctClass(r.changePct)" class="chip-pct">{{ r.changePct == null ? '—' : signed(r.changePct) + '%' }}</span>
            <span v-if="r.pullbackPct != null" class="chip-pullback">回撤 -{{ Number(r.pullbackPct).toFixed(2) }}%</span>
            <span v-if="r.breakCount != null && r.breakCount > 0" class="chip-break">开板{{ r.breakCount }}次</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed, fiveDimBandClassOf } from '../utils/scores'
import { useScoringStore } from '../stores/scoring'
import DimScoreCurve from '../components/DimScoreCurve.vue'
import DimIntroTip from '../components/DimIntroTip.vue'

const route = useRoute()
const scoring = useScoringStore()

const PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }
const PATTERN_TYPE = { ONE_LINE: 'danger', T_SHAPE: 'warning', TURNOVER: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)
/** 曲线往回取多少个日历日去凑最近 30 个交易日：留足长假，取 90 天。 */
const LOOKBACK_DAYS = 90
const CURVE_ROWS = 30
const curveRows = ref([])
// 打分明细 / 封住名单 / 未封住名单：默认折叠，点头部展开
const scoreOpen = ref(false)
const sealedOpen = ref(false)
const bombedOpen = ref(false)

const summary = computed(() => vo.value?.summary || null)
const sealedRows = computed(() => vo.value?.sealed || [])
const bombedRows = computed(() => vo.value?.bombed || [])
const prevReady = computed(() => vo.value?.prevAvailable === true)

/* ---- 首板生态打分明细：score-detail first 维 eval 树（纯 T 日） ---- */
const firstDim = computed(() => (scoring.detail?.dims || []).find((d) => d.key === 'first') || null)
const metrics = computed(() => scoring.detail?.metrics || {})

const scoreRows = computed(() => {
  const dim = firstDim.value
  if (!dim) return []
  const rows = []
  for (const sub of dim.children || []) {
    const kids = sub.children || []
    if (kids.length) {
      rows.push({ kind: 'group', key: 'g-' + sub.key, label: sub.label, weight: sub.weight,
        score: sub.score, note: sub.note, adjustment: sub.adjustment, bg: bgText(sub.key) })
      for (const k of kids) {
        rows.push({ kind: 'leaf', key: 'l-' + sub.key + '-' + k.key, label: k.label, weight: k.weight,
          raw: k.raw, bandHit: k.bandHit, note: k.note, score: k.score, sourceKey: k.sourceKey,
          adjustment: k.adjustment, bg: '' })
      }
    } else {
      rows.push({ kind: 'solo', key: 's-' + sub.key, label: sub.label, weight: sub.weight,
        raw: sub.raw, bandHit: sub.bandHit, note: sub.note, score: sub.score, sourceKey: sub.sourceKey,
        adjustment: sub.adjustment, bg: bgText(sub.key) })
    }
  }
  return rows
})

function bgText(subKey) {
  const m = metrics.value
  if (subKey === 'first_count') return m.limit_up_count != null ? `当日涨停 ${m.limit_up_count} 家` : '—'
  if (subKey === 'first_sealed' || subKey === 'first_bomb') return m.limit_down_count != null ? `全局跌停 ${m.limit_down_count} 家` : '—'
  if (subKey === 'first_theme') return '只统计 T 日新首板行业分布'
  return ''
}

function bandClass(score) {
  return fiveDimBandClassOf(score)
}
function fmtWeight(w) {
  if (w == null) return '—'
  return String(Number(w))
}
function scoreText(s) {
  return s == null ? '未评' : Math.round(Number(s))
}
function contribText(w, s) {
  if (w == null || s == null) return '—'
  return (Number(w) * Number(s)).toFixed(1)
}
/** 读数按 source_key 带单位：率=%、封单额=亿元、家数。 */
function rawText(sourceKey, raw) {
  if (raw == null || Number.isNaN(Number(raw))) return '—'
  const n = Number(raw)
  if (sourceKey === 'first_avg_seal_amount') return Number(n.toFixed(2)) + ' 亿'
  if (sourceKey === 'first_count') return n + ' 只'
  return Number(n.toFixed(2)) + '%'
}

/* 试错-兑现背离度 */
const ecologyDivergence = computed(() => {
  const v = scoring.detail?.ecologyDivergence
  return v == null || Number.isNaN(Number(v)) ? null : Number(v)
})
const divergenceLevel = computed(() => {
  const d = ecologyDivergence.value
  if (d == null) return ''
  if (d > 30) return 'severe'
  if (d > 15) return 'warn'
  if (d < -15) return 'reverse'
  return 'balanced'
})

function nz(v) {
  return v == null ? '—' : v
}
function pctText(v) {
  return v == null ? '—' : Number(v).toFixed(1) + '%'
}
function signedNum(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return signed(Number(v))
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

/** 补 T00:00:00 按本地时区解析，否则 new Date('2026-09-04') 走 UTC 会少一天。 */
function shiftDays(iso, days) {
  const d = new Date(iso + 'T00:00:00')
  d.setDate(d.getDate() - days)
  return d.toLocaleDateString('en-CA')
}

async function load() {
  loading.value = true
  try {
    const [res] = await Promise.all([
      prdApi.shouban(date.value).catch(() => null),
      scoring.loadDetail(date.value, true).catch(() => null)
    ])
    vo.value = res?.data || null
    // 曲线与异动监管页同一取数口：range 返回按日升序，切出最近一段直接喂图
    const rangeRes = await recordApi.getRange(shiftDays(date.value, LOOKBACK_DAYS), date.value).catch(() => null)
    curveRows.value = ((rangeRes && rangeRes.data) || []).slice(-CURVE_ROWS).map((r) => ({
      date: r.tradeDate,
      score: r.scoreFirst
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
    } catch (e) { /* 停在今天 */ }
  }
  load()
})
watch(date, load)
</script>

<style scoped>
.shouban-page { max-width: 1100px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.page-header h2 { margin: 0; color: #e1e8ed; white-space: nowrap; }
.intro { margin-bottom: 16px; }
.intro-body p { margin: 6px 0 0; font-size: 12px; line-height: 1.6; color: #8899a6; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(160px, 100%), 1fr)); gap: 12px; margin-bottom: 20px; }
.stat { background: #1a2332; border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
.stat-label { font-size: 12px; color: #8899a6; }
.stat-value { font-size: 20px; font-weight: 700; color: #e1e8ed; }
.stat-sub { font-size: 11px; color: #8899a6; }
.block { background: #1a2332; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
.block-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.block-head h3 { margin: 0; font-size: 15px; color: #e1e8ed; white-space: nowrap; }
.sub { font-size: 12px; color: #6b7c8c; }
.formula { margin: 0 0 12px; padding-left: 18px; color: #a8b7c4; font-size: 12px; line-height: 1.8; }
.score-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.score-table th {
  text-align: left; color: #8899a6; font-weight: 500;
  padding: 6px 8px; border-bottom: 1px solid #2d3748; white-space: nowrap;
}
.score-table td { padding: 6px 8px; border-bottom: 1px solid #22303f; color: #cbd5e1; vertical-align: top; }
.col-name { width: 18%; }
.col-w { width: 56px; }
.col-raw { width: 80px; }
.col-bg { width: 18%; }
.col-fix { width: 16%; }
.col-score { width: 64px; text-align: right; }
.col-contrib { width: 72px; }
.score-table td.col-score { text-align: right; }
.group-row td { background: rgba(251,191,36,.05); font-weight: 600; color: #e1e8ed; }
.leaf-row .cell-name { color: #a8b7c4; font-weight: 400; }
.leaf-row.solo td { font-weight: 600; }
.indent { color: #4b5a68; margin-right: 6px; }
.raw-cell { font-family: ui-monospace, Menlo, Consolas, monospace; color: #e1e8ed; }
.bg-cell { color: #7c8794; font-size: 11px; }
.band-cell { color: #8899a6; }
.fix-cell { color: #fbbf24; font-size: 11px; }
.contrib { color: #a8b7c4; font-family: ui-monospace, Menlo, Consolas, monospace; }
.score-foot { margin: 10px 0 0; font-size: 12px; color: #8899a6; line-height: 1.7; }
.whistle-note { color: #fca5a5; }
.b-ebb { color: #94a3b8; }
.b-chaos { color: #60a5fa; }
.b-ferment { color: #fbbf24; }
.b-climax { color: #f87171; }
.missing { color: #6b7c8c; }
.divergence-banner {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 10px 14px; border-radius: 8px;
  font-size: 13px; font-weight: 600; border: 1px solid;
}
.divergence-banner.severe { background: rgba(239,68,68,.14); border-color: #ef4444; color: #fca5a5; }
.divergence-banner.warn { background: rgba(251,191,36,.12); border-color: #fbbf24; color: #fcd34d; }
.divergence-banner.reverse { background: rgba(96,165,250,.12); border-color: #60a5fa; color: #93c5fd; }
.divergence-banner.balanced { background: rgba(110,231,183,.08); border-color: #2f5a4a; color: #6ee7b7; }
.divergence-num { margin-left: auto; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; font-weight: 400; opacity: .85; }
.signal-empty { color: #6b7c8c; font-size: 12px; margin: 0; }
.block-head .sub { color: #6b7c8c; font-size: 12px; }

/* 折叠头（与连板页同一套：整块可点，折叠态收窄底距） */
.score-head, .list-head { cursor: pointer; user-select: none; border-radius: 8px; transition: background .15s; }
.score-head:hover, .list-head:hover { background: rgba(255, 255, 255, .025); }
.score-head:hover h3, .list-head:hover h3 { color: #fff; }
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
.score-head:hover .fold-tag, .list-head:hover .fold-tag { color: #ffd166; border-color: #ffd166; background: #2b3d52; }
.score-head:active .fold-tag, .list-head:active .fold-tag { transform: translateY(1px); background: #2f4258; }
.score-block.score-collapsed .block-head { margin-bottom: 0; border-bottom: 1px dashed #33455a; }

/* 封住 / 未封住名单：区块头常驻分隔线，身体可折叠 */
.list-block .block-head { margin-bottom: 0; border-bottom: 1px dashed #33455a; }
.list-body { padding-top: 12px; }
.list-count { font-size: 12px; font-weight: 600; }
.list-empty { color: #6b7c8c; font-size: 12px; padding: 4px 0; }

/* chip 名单：与连板天梯同款 */
.chips { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  background: #0f1419; border: 1px solid #2d3748; border-radius: 999px;
  padding: 4px 10px; font-size: 12px;
}
.chip-name { color: #e1e8ed; font-weight: 600; }
.chip-code { color: #6b7c8c; font-size: 11px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.chip-industry { color: #a8b7c4; font-size: 11px; }
.chip-industry.in-main { color: #fbbf24; }
.chip-seal { color: #a8b7c4; font-size: 11px; }
.chip-reseal { color: #7dd3fc; font-size: 11px; background: rgba(56, 189, 248, .12); border-radius: 6px; padding: 1px 6px; }
.chip-break { color: #7c8794; font-size: 11px; }
.chip-pct { font-weight: 600; font-size: 11px; }
.chip.chip-fail { opacity: .55; background: #0d1117; border-color: #3a4450; border-style: dashed; }
.chip-fail .chip-name { color: #94a3b8; font-weight: 500; }
.chip-pullback { color: #fca5a5; font-size: 11px; }
.ok-text { color: #6ee7b7; }
.bad-text { color: #fca5a5; }
.in-main { color: #fbbf24; }
.up { color: #ef4444; }
.down { color: #3b82f6; }
</style>
