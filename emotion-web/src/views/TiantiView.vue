<template>
  <div class="tianti-page">
    <div class="page-header">
      <h2>连板生态</h2>
      <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
        :disabled-date="notBeforeToday" style="width: 168px" />
    </div>
    <el-alert class="intro" type="info" :closable="false" show-icon>
      <template #title>连板天梯：按板高 H→2 逐层、左对齐排布；3 板及以上层级把晋级失败个股并入同层（灰色半透明标注）</template>
      <div class="intro-body">
        <p>一字=开盘前封死且全天 0 炸板；T字=开盘封死但盘中开过又回封；其余为换手板。同层个股按封单金额从大到小排序。</p>
      </div>
    </el-alert>

    <div class="stat-grid" v-loading="loading">
      <div class="stat">
        <span class="stat-label">最高连板 H</span>
        <span class="stat-value">{{ nz(vo?.maxBoard) }}</span>
        <span class="stat-sub">全市场</span>
      </div>
      <div class="stat">
        <span class="stat-label">涨停数 / 连板数</span>
        <span class="stat-value">{{ nz(vo?.ztTotal) }} / {{ nz(vo?.lbTotal) }}</span>
        <span class="stat-sub">连板=≥2 板家数</span>
      </div>
    </div>

    <!-- 连板生态打分：完整 eval 树（读数→命中阶梯→层/子加权→维分） -->
    <section class="block score-block" v-loading="scoring.detailLoading">
      <div class="block-head">
        <h3>连板生态打分</h3>
        <span v-if="boardDim" class="dim-score" :class="bandClass(boardDim.score)">
          {{ boardDim.score == null ? '未评' : Number(boardDim.score).toFixed(2) + ' 分' }}
        </span>
      </div>
      <el-empty v-if="!boardDim" :description="'当日读数未取到，连板生态未评（不计入分母）'" :image-size="60" />
      <template v-else>
        <ol class="formula">
          <li>叶子得分（四层晋级率 / 溢价 / 大面家数、家数封板率、回封率、空间板 H）：今日读数命中阈值阶梯 → 0-100 分；按当日 H 本日不存在的层标 <b>N/A</b>（如 H=4 时中高位/极高位），不进分母，与"有此层但没采到数据（未评）"区分。</li>
          <li>结构子分＝Σ(层权重 × 层得分) ÷ Σ已评层权重；未评/N/A 叶子剔出分母，不按 0 计。</li>
          <li>口径修正：中位晋级昨日基数&lt;5 家→该叶 ×0.8；H&lt;5 空间未打开→晋级结构 ×0.9；大盘背离（大盘分&lt;40 或红盘率&lt;20%）→溢价结构 ×0.8；全局跌停≥20/≥10/≥5 家→大面结构 −35/−20/−8。</li>
          <li>维分＝Σ(子项权重 × 修正后子项分) ÷ Σ已评子项权重，再过表尾三个闸门（中位吹哨 ×0.8、大盘背离 ×0.85、龙头错位 ×0.9，可连乘）。</li>
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
              <tr v-if="row.kind === 'adjust'" class="adjust-row">
                <td class="cell-name" colspan="5"><span class="indent">⇡</span>{{ row.text }}</td>
                <td class="fix-cell">{{ row.fix }}</td>
                <td><span :class="bandClass(row.after)">→{{ scoreText(row.after) }}</span></td>
                <td class="contrib"></td>
              </tr>
              <tr v-else-if="row.kind === 'group'" class="group-row">
                <td class="cell-name">{{ row.label }}</td>
                <td>×{{ fmtWeight(row.weight) }}</td>
                <td>—</td>
                <td class="bg-cell">{{ row.bg || '—' }}</td>
                <td class="band-cell">{{ row.note || '各层加权合成' }}</td>
                <td class="fix-cell">{{ row.adjustment || '—' }}</td>
                <td><span :class="bandClass(row.score)">{{ scoreText(row.score) }}</span></td>
                <td class="contrib">{{ contribText(row.weight, row.score) }}</td>
              </tr>
              <tr v-else class="leaf-row" :class="{ solo: row.kind === 'solo', na: row.applicable === false }">
                <td class="cell-name"><span class="indent" v-if="row.kind === 'leaf'">└</span>{{ row.label }}</td>
                <td>×{{ fmtWeight(row.weight) }}</td>
                <td class="raw-cell">
                  <span v-if="row.applicable === false" class="na-tag">N/A</span>
                  <template v-else>{{ rawText(row.sourceKey, row.raw) }}</template>
                </td>
                <td class="bg-cell">{{ row.bg || '—' }}</td>
                <td class="band-cell">
                  <span v-if="row.applicable === false" class="na-text">本日无此层（按 H={{ metrics.max_height ?? '—' }}）</span>
                  <span v-else-if="row.bandHit">{{ row.bandHit }}</span>
                  <span v-else-if="row.note">{{ row.note }}</span>
                  <span v-else class="missing">—</span>
                </td>
                <td class="fix-cell">{{ row.adjustment || '—' }}</td>
                <td>
                  <span v-if="row.applicable === false" class="na-tag">N/A</span>
                  <span v-else :class="bandClass(row.score)">{{ scoreText(row.score) }}</span>
                </td>
                <td class="contrib">{{ row.kind === 'solo' ? contribText(row.weight, row.score) : '' }}</td>
              </tr>
            </template>
          </tbody>
        </table>
        <!-- 维分闸门：未触发也列出，触发的高亮并给出证据 -->
        <table v-if="boardDim.gates?.length" class="gate-table">
          <thead>
            <tr><th>维分闸门</th><th>触发条件</th><th>系数</th><th>状态 / 证据</th></tr>
          </thead>
          <tbody>
            <tr v-for="g in boardDim.gates" :key="g.key" :class="{ triggered: g.triggered }">
              <td>{{ g.label }}</td>
              <td class="gate-cond">{{ gateCondition(g.key) }}</td>
              <td>×{{ fmtWeight(g.coefficient) }}</td>
              <td>
                <el-tag size="small" :type="g.triggered ? 'danger' : 'success'" effect="plain">
                  {{ g.triggered ? '已触发' : '未触发' }}
                </el-tag>
                <span class="gate-reason">{{ g.reason }}</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p class="score-foot">
          维分＝Σ(子项权重 × 修正后子项分) ÷ Σ已评子项权重，再过上方闸门
          <template v-if="boardDim.note">；<b class="whistle-note">{{ boardDim.note }}</b></template>
        </p>
      </template>
    </section>

    <!-- 天梯 -->
    <section class="block" v-loading="loading">
      <div class="block-head">
        <h3>连板天梯</h3>
        <span class="sub">从高板到 2 板左对齐；空层=断档；灰色半透明=昨日该板个股今日晋级失败（3 板及以上）</span>
      </div>
      <div v-if="!vo?.levels?.length && !loading" class="none-hint">当日无 ≥2 板个股</div>
      <div class="ladder">
        <div v-for="lvl in vo?.levels || []" :key="lvl.board" class="lad-band" :class="{ empty: !lvl.rows.length }">
          <div class="lad-head">
            <span class="lad-title">{{ lvl.board }} 板</span>
            <el-tag size="small" effect="plain" class="layer-tag">{{ lvl.layerLabel }}</el-tag>
            <span v-if="!lvl.rows.length" class="gap-tag">断层</span>
          </div>
          <div class="chips">
            <div v-for="r in lvl.rows" :key="r.code" class="chip" :class="roleChipClass(r.role)">
              <span class="chip-name">{{ r.name }}</span>
              <el-tag v-if="r.role" size="small" :type="ROLE_TYPE[r.role] || 'info'" effect="dark">{{ r.role }}</el-tag>
              <el-tag v-if="r.pattern" size="small" :type="PATTERN_TYPE[r.pattern] || 'info'" effect="plain">
                {{ PATTERN_LABEL[r.pattern] }}
              </el-tag>
              <span v-if="r.breakCount != null && r.breakCount > 0" class="chip-reseal"
                :title="`日内开板 ${r.breakCount} 次后封住（炸后回封）`">
                开板{{ r.breakCount }}次↩回封
              </span>
              <span v-if="r.promoted === true" class="chip-promoted ok">晋级</span>
              <span v-if="r.promoted === false" class="chip-promoted bad">持稳</span>
              <span v-if="r.sealAmount != null" class="chip-seal">封单 {{ moneyText(r.sealAmount) }}</span>
              <span :class="pctClass(r.changePct)" class="chip-pct">{{ signed(r.changePct) }}%</span>
            </div>
            <!-- 2 板层=1进2 兑现名单（PRD 时间截面：昨首板种子今兑现）；3 板+失败常显，2 板默认折叠 -->
            <template v-if="lvl.board === 2 && (lvl.failed || []).length">
              <div class="fail-toggle" @click="toggleLowFail">
                <span class="fail-toggle-badge">1进2 失败 {{ lvl.failed.length }} 只</span>
                <span class="fail-toggle-link">{{ lowFailExpanded ? '收起 ▲' : '展开兑现名单 ▼' }}</span>
              </div>
              <template v-if="lowFailExpanded">
                <div v-for="f in lvl.failed" :key="'f' + f.code" class="chip chip-fail" :title="failTitle(f)">
                  <span class="chip-name">{{ f.name }}</span>
                  <el-tag size="small" :type="FAIL_TYPE[f.todayStatus] || 'info'" effect="plain">
                    {{ FAIL_LABEL[f.todayStatus] || '未触板' }}
                  </el-tag>
                  <span v-if="f.changePct != null" :class="pctClass(f.changePct)" class="chip-pct">
                    {{ signed(f.changePct) }}%
                  </span>
                  <span v-else class="chip-missing">明细未覆盖</span>
                  <span v-if="f.pullbackPct != null" class="chip-pullback">回撤 {{ f.pullbackPct }}%</span>
                </div>
              </template>
            </template>
            <template v-if="lvl.board >= 3">
              <div v-for="f in (lvl.failed || [])" :key="'f' + f.code" class="chip chip-fail"
                :title="failTitle(f)">
                <span class="chip-name">{{ f.name }}</span>
                <el-tag size="small" :type="FAIL_TYPE[f.todayStatus] || 'info'" effect="plain">
                  {{ FAIL_LABEL[f.todayStatus] || '未触板' }}
                </el-tag>
                <span v-if="f.changePct != null" :class="pctClass(f.changePct)" class="chip-pct">
                  {{ signed(f.changePct) }}%
                </span>
                <span v-else class="chip-missing">明细未覆盖</span>
                <span v-if="f.pullbackPct != null" class="chip-pullback">回撤 {{ f.pullbackPct }}%</span>
              </div>
            </template>
          </div>
        </div>
      </div>
    </section>

    <!-- 结构信号：只认引擎 signalFlags / forcedEbb，前端不自创阈值 -->
    <section class="block signal-block">
      <div class="block-head">
        <h3>连板结构信号</h3>
        <span class="sub">与五维打分引擎同一份判定（score-detail 现算）</span>
      </div>

      <!-- 试错(T日首板)-兑现(T-1→T连板)背离：首板分−连板分 -->
      <div v-if="ecologyDivergence != null" class="divergence-banner" :class="divergenceLevel">
        🧭 {{ scoring.detail.ecologyDivergenceLabel }}
        <span class="divergence-num">试错-兑现背离度 {{ signedNum(ecologyDivergence) }}</span>
      </div>

      <div v-if="whistle" class="whistle-banner">
        <span class="whistle-dot"></span>
        🔴 中位吹哨：中位晋级过弱或中位负溢价叠加大面，<b>连板生态本维 ×0.8</b>
      </div>

      <div v-if="forcedEbb" class="ebb-banner">
        ⛔ 强制退潮已触发：{{ forcedEbbReason || '见打分明细' }}（无视总分，按退潮应对）
      </div>

      <!-- 客观三读数透明条：只陈列引擎读数与吹哨线，不做颜色结论（结论只看上面的灯） -->
      <div class="mid-readouts">
        <div class="readout">
          <span class="readout-label">中位晋级率 JR_中</span>
          <span class="readout-value">{{ pctText(midMetrics.jr_mid) }}</span>
          <span class="readout-rule">吹哨线 &lt; 15%</span>
        </div>
        <div class="readout">
          <span class="readout-label">中位溢价 Prem_中</span>
          <span class="readout-value" :class="numClass(midMetrics.prem_mid)">{{ signedNum(midMetrics.prem_mid) }}%</span>
          <span class="readout-rule">吹哨需 &lt; 0</span>
        </div>
        <div class="readout">
          <span class="readout-label">中位大面 Big_中</span>
          <span class="readout-value">{{ midMetrics.big_mid == null ? '未评' : midMetrics.big_mid + ' 家' }}</span>
          <span class="readout-rule">吹哨需 ≥ 3</span>
        </div>
      </div>
      <p v-if="!detailReady" class="signal-empty">结构信号计算中（需当日及前一交易日涨停池）……</p>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { prdApi, recordApi } from '../api/modules'
import { signed, fiveDimBandClassOf } from '../utils/scores'
import { useScoringStore } from '../stores/scoring'

const route = useRoute()
const scoring = useScoringStore()

const ROLE_TYPE = { 总龙头: 'danger', 中军: 'primary', 跟风: 'success', 卡位: 'warning', 反包: 'info' }
const PATTERN_LABEL = { ONE_LINE: '一字', T_SHAPE: 'T字', TURNOVER: '换手' }
const PATTERN_TYPE = { ONE_LINE: 'danger', T_SHAPE: 'warning', TURNOVER: 'info' }
const FAIL_LABEL = { ZT: '仍封停(低板)', ZB: '炸板', DT: '跌停', GONE: '未触板' }
const FAIL_TYPE = { ZT: 'warning', ZB: 'danger', DT: 'danger', GONE: 'info' }

const todayStr = new Date().toLocaleDateString('en-CA')
const date = ref(route.query.date || todayStr)
const loading = ref(false)
const vo = ref(null)
// 2 板层 1进2 失败名单默认折叠（退潮日可能 20+ 只）
const lowFailExpanded = ref(false)
function toggleLowFail() {
  lowFailExpanded.value = !lowFailExpanded.value
}

/* ---- 连板生态打分明细：直接铺 score-detail 里 board 维的完整 eval 树 ---- */
const boardDim = computed(() => (scoring.detail?.dims || []).find((d) => d.key === 'board') || null)
const marketDim = computed(() => (scoring.detail?.dims || []).find((d) => d.key === 'market') || null)
const metrics = computed(() => scoring.detail?.metrics || {})

/** 闸门触发条件（人话，与引擎 BoardScoreCalculator 常量一致）。 */
function gateCondition(key) {
  if (key === 'whistle') return '中位晋级率<15% 或（中位溢价<0 且中位大面≥3家）'
  if (key === 'divergence') return '大盘分<40 或 强制退潮 或 跌停≥10家'
  if (key === 'dragon_misalign') return '总龙头行业 ≠ 日内核心行业'
  return '—'
}

/* 试错-兑现背离度 = 首板生态(D4,T日) − 连板生态(D3,T-1→T) */
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

/** 每行对应的全局背景读数（全市场语境，防止只在连板小圈子里自评）。 */
function bgText(subKey, sourceKey) {
  const m = metrics.value
  if (subKey === 'promo' && !sourceKey) {
    return m.max_height != null ? `最高板 H=${m.max_height}` : '—'
  }
  if (sourceKey === 'jr_mid') {
    return m.jr_mid_base != null ? `昨基数 ${m.jr_mid_base} 家（<5 小样本）` : '—'
  }
  if (subKey === 'premium' && !sourceKey) {
    const parts = []
    if (marketDim.value?.score != null) parts.push(`大盘 ${Math.round(marketDim.value.score)} 分`)
    if (m.red_ratio != null) parts.push(`红盘率 ${(m.red_ratio * 100).toFixed(1)}%`)
    return parts.length ? parts.join(' / ') : '—'
  }
  if (subKey === 'bigloss' && !sourceKey) {
    return m.limit_down_count != null ? `全局跌停 ${m.limit_down_count} 家（≥20/≥10/≥5 外溢-35/-20/-8）` : '—'
  }
  if (subKey === 'broken_quality' && !sourceKey) {
    return m.limit_up_count != null ? `当日涨停 ${m.limit_up_count} 家` : '—'
  }
  if (sourceKey === 'max_height') {
    return m.limit_up_count != null ? `当日涨停 ${m.limit_up_count} 家` : '—'
  }
  return ''
}

/**
 * 把两层 eval 树压成表格行：
 * - group：晋级/溢价/大面结构、炸板质量（其下还有四层/两个叶子）
 * - leaf： group 下的叶子（四层指标等）
 * - solo：直属维的叶子（数量高度）
 * - adjust：组级口径修正（空间/背离/外溢），渲染为组下方的说明行
 */
const scoreRows = computed(() => {
  const dim = boardDim.value
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
          applicable: k.applicable, adjustment: k.adjustment, bg: bgText(sub.key, k.sourceKey) })
      }
    } else {
      rows.push({ kind: 'solo', key: 's-' + sub.key, label: sub.label, weight: sub.weight,
        raw: sub.raw, bandHit: sub.bandHit, note: sub.note, score: sub.score, sourceKey: sub.sourceKey,
        applicable: sub.applicable, adjustment: sub.adjustment, bg: bgText(sub.key, sub.sourceKey) })
    }
    if (sub.adjustment) {
      rows.push({ kind: 'adjust', key: 'a-' + sub.key, text: `${sub.label}修正：${sub.adjustment}`,
        fix: sub.adjustment.match(/×\s*[\d.]+|−\s*\d+|-?\d+(?=\s*$)/)?.[0] || '', after: sub.score })
    }
  }
  return rows
})

function bandClass(score) {
  return fiveDimBandClassOf(score)
}
function fmtWeight(w) {
  if (w == null) return '—'
  return String(Number(w))
}
function scoreText(s) {
  return s == null ? '未评' : String(Math.round(Number(s)))
}
function contribText(w, s) {
  if (w == null || s == null) return '—'
  return (Number(w) * Number(s)).toFixed(1)
}
/** 读数按 source_key 带单位：晋级率/溢价/封板率=%，大面=家数，空间板 H=板。 */
function rawText(sourceKey, raw) {
  if (raw == null || Number.isNaN(Number(raw))) return '—'
  const n = Number(raw)
  const key = sourceKey || ''
  const shown = Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)))
  if (key === 'max_height') return shown + ' 板'
  if (key.startsWith('big_')) return shown + ' 家'
  return shown + '%'
}

/* ---- 结构信号：只镜像引擎 score-detail，不在前端重算判定 ---- */
// detail 是否真的到位（metrics 有键才算），用于"计算中"占位；取不到不瞎亮灯。
const detailReady = computed(() => {
  const mm = scoring.detail?.metrics
  return !!mm && Object.keys(mm).length > 0
})
const whistle = computed(() => (scoring.detail?.signalFlags || []).includes('中位吹哨'))
const forcedEbb = computed(() => scoring.detail?.forcedEbb === true)
const forcedEbbReason = computed(() => scoring.detail?.forcedEbbReason || '')
// 中位三读数直接取引擎 metrics（jr_mid=中位晋级率 / prem_mid=中位溢价 / big_mid=中位大面）
const midMetrics = computed(() => scoring.detail?.metrics || {})

/** 带符号的数值文本（null → —），用于溢价这种有正负的读数。 */
function signedNum(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return signed(Number(v))
}
function numClass(v) {
  if (v == null || Number.isNaN(Number(v))) return ''
  return Number(v) > 0 ? 'up' : Number(v) < 0 ? 'down' : ''
}

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
function failTitle(f) {
  return `昨日 ${f.prevBoard} 板，今日${FAIL_LABEL[f.todayStatus] || '未触板'}`
}

async function load() {
  loading.value = true
  lowFailExpanded.value = false
  try {
    // 天梯数据与引擎信号同源同日；score-detail 由 store 去重（页内 DimScoreBlock 也在拉，不会重复发）
    const [tiantiRes] = await Promise.all([
      prdApi.tianti(date.value).catch(() => null),
      scoring.loadDetail(date.value, true).catch(() => null)
    ])
    vo.value = tiantiRes?.data || null
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
.stat-sub { font-size: 11px; color: #8899a6; }
.block { background: #1a2332; border-radius: 12px; padding: 20px; margin-bottom: 20px; }
.block-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 14px; }
.block-head h3 { margin: 0; font-size: 15px; color: #e1e8ed; white-space: nowrap; flex: none; }
.sub { font-size: 12px; color: #8899a6; }

/* 打分明细 */
.dim-score {
  font-size: 16px; font-weight: 700; color: #e1e8ed;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.formula { margin: 0 0 12px; padding-left: 18px; color: #a8b7c4; font-size: 12px; line-height: 1.8; }
.score-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.score-table th {
  text-align: left; color: #8899a6; font-weight: 500;
  padding: 6px 8px; border-bottom: 1px solid #2d3748; white-space: nowrap;
}
.score-table td { padding: 6px 8px; border-bottom: 1px solid #22303f; color: #cbd5e1; vertical-align: top; }
.col-name { width: 17%; }
.col-w { width: 56px; }
.col-raw { width: 78px; }
.col-bg { width: 20%; }
.col-fix { width: 18%; }
.col-score { width: 64px; text-align: right; }
.col-contrib { width: 72px; }
.score-table td.col-score { text-align: right; }
.group-row td { background: rgba(251,191,36,.05); font-weight: 600; color: #e1e8ed; }
.leaf-row .cell-name { color: #a8b7c4; font-weight: 400; }
.leaf-row.solo td { font-weight: 600; }
.leaf-row.na td { opacity: .55; }
.indent { color: #4b5a68; margin-right: 6px; }
.raw-cell { font-family: ui-monospace, Menlo, Consolas, monospace; color: #e1e8ed; }
.bg-cell { color: #7c8794; font-size: 11px; }
.band-cell { color: #8899a6; }
.fix-cell { color: #fbbf24; font-size: 11px; }
.adjust-row td { background: rgba(251,191,36,.07); color: #d8c08a; font-size: 11px; padding: 4px 8px; }
.na-tag {
  display: inline-block; padding: 0 6px; border-radius: 4px;
  background: #2a3340; color: #8b98a5; font-size: 11px; font-weight: 600;
}
.na-text { color: #6b7c8c; font-style: italic; }
.contrib { color: #a8b7c4; font-family: ui-monospace, Menlo, Consolas, monospace; }

/* 维分闸门表 */
.gate-table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 10px; }
.gate-table th {
  text-align: left; color: #8899a6; font-weight: 500;
  padding: 6px 8px; border-top: 1px solid #2d3748; border-bottom: 1px solid #2d3748;
}
.gate-table td { padding: 6px 8px; border-bottom: 1px solid #22303f; color: #a8b7c4; }
.gate-table tr.triggered td { background: rgba(239,68,68,.08); color: #fca5a5; }
.gate-table tr.triggered td:first-child { font-weight: 700; }
.gate-cond { color: #7c8794; }
.gate-reason { margin-left: 8px; color: #8899a6; font-size: 11px; }
.gate-table tr.triggered .gate-reason { color: #fca5a5; }
.score-foot { margin: 10px 0 0; font-size: 12px; color: #8899a6; line-height: 1.7; }
.whistle-note { color: #fca5a5; }
.b-ebb { color: #94a3b8; }
.b-chaos { color: #60a5fa; }
.b-ferment { color: #fbbf24; }
.b-climax { color: #f87171; }
.missing { color: #6b7c8c; }

/* 天梯：左对齐、全宽、逐层堆叠 */
.ladder { display: flex; flex-direction: column; gap: 10px; align-items: stretch; }
.lad-band {
  width: 100%;
  background: linear-gradient(180deg, #223041, #1b2735);
  border: 1px solid #2d3748;
  border-radius: 8px;
  padding: 10px 14px;
}
.lad-band.empty { background: rgba(251,191,36,.06); border-style: dashed; border-color: #fbbf24; }
.lad-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; flex-wrap: wrap; }
.lad-title { font-size: 15px; font-weight: 700; color: #fbbf24; }
.layer-tag { flex: none; }
.gap-tag { color: #fbbf24; font-size: 12px; font-weight: 600; }
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
.chip-reseal { color: #7dd3fc; font-size: 11px; background: rgba(56,189,248,.12); border-radius: 6px; padding: 1px 6px; }
/* 晋级失败：透明灰色，与在板个股明确区分 */
.chip.chip-fail {
  opacity: .55;
  background: #0d1117;
  border-color: #3a4450;
  border-style: dashed;
}
.chip-fail .chip-name { color: #94a3b8; font-weight: 500; }
.chip-missing { color: #7c8794; font-size: 11px; }
.chip-pullback { color: #fca5a5; font-size: 11px; }
.none-hint { color: #6b7c8c; font-size: 13px; }

/* 结构信号区 */
.signal-block .block-head .sub { color: #6b7c8c; font-size: 12px; }
.divergence-banner {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: 10px 0; padding: 10px 14px; border-radius: 8px;
  font-size: 13px; font-weight: 600; border: 1px solid;
}
.divergence-banner.severe { background: rgba(239,68,68,.14); border-color: #ef4444; color: #fca5a5; }
.divergence-banner.warn { background: rgba(251,191,36,.12); border-color: #fbbf24; color: #fcd34d; }
.divergence-banner.reverse { background: rgba(96,165,250,.12); border-color: #60a5fa; color: #93c5fd; }
.divergence-banner.balanced { background: rgba(110,231,183,.08); border-color: #2f5a4a; color: #6ee7b7; }
.divergence-num { margin-left: auto; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; font-weight: 400; opacity: .85; }
.fail-toggle {
  flex-basis: 100%; display: flex; align-items: center; gap: 10px;
  margin: 4px 0 2px; cursor: pointer; user-select: none;
}
.fail-toggle-badge {
  padding: 2px 10px; border-radius: 999px; font-size: 12px;
  background: rgba(239,68,68,.15); color: #fca5a5; border: 1px dashed rgba(239,68,68,.5);
}
.fail-toggle-link { color: #8899a6; font-size: 12px; }
.fail-toggle:hover .fail-toggle-link { color: #fca5a5; }
.whistle-banner {
  display: flex; align-items: center; gap: 8px;
  margin: 10px 0; padding: 10px 14px; border-radius: 8px;
  background: rgba(239,68,68,.14); border: 1px solid #ef4444; color: #fca5a5; font-size: 14px; font-weight: 600;
}
.whistle-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: #ef4444; flex: none;
  animation: whistle-blink 1s steps(2, start) infinite;
}
@keyframes whistle-blink { to { opacity: .15; } }
.ebb-banner {
  margin: 10px 0; padding: 10px 14px; border-radius: 8px;
  background: rgba(239,68,68,.18); border: 1px solid #b91c1c;
  color: #fecaca; font-size: 14px; font-weight: 700;
}
.mid-readouts {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px; margin-top: 10px;
}
.readout {
  background: #0f1419; border: 1px solid #2d3748; border-radius: 8px;
  padding: 10px 14px; display: flex; flex-direction: column; gap: 4px;
}
.readout-label { color: #8899a6; font-size: 12px; }
.readout-value { color: #e1e8ed; font-size: 20px; font-weight: 700; font-family: ui-monospace, Menlo, Consolas, monospace; }
.readout-value.up { color: #ef4444; }
.readout-value.down { color: #3b82f6; }
.readout-rule { color: #6b7c8c; font-size: 11px; }
.signal-empty { color: #6b7c8c; font-size: 12px; margin: 8px 0 0; }
.up { color: #ef4444; }
.down { color: #3b82f6; }
</style>
