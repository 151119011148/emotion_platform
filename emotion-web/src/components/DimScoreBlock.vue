<template>
  <section class="dim-score-block" v-loading="loading">
    <div class="block-head">
      <h3>{{ title }} <small class="dim-meta">{{ dimLabel }} ×{{ weight }}</small></h3>
      <span class="dim-score" :class="{ unscored: score == null, [bandClass]: score != null }">
        {{ score == null ? '未评' : `${score} 分 · ${band}` }}
      </span>
    </div>
    <div class="fd-bar">
      <div class="fd-bar-fill" :class="bandClass" :style="{ width: barWidth }"></div>
    </div>

    <div v-if="rows.length" class="sub-rows">
      <div v-for="row in rows" :key="row.key" class="fd-sub-row" :class="{ covered: row.filled }">
        <span class="cell-label">{{ row.label }}</span>
        <span v-if="row.weight != null" class="fd-weight">×{{ fmtNum(row.weight) }}</span>

        <template v-if="row.manual">
          <el-form-item label-width="0" class="cell-item fd-input">
            <el-select v-if="row.manual.kind === 'select'" v-model="form[row.manual.field]" size="small" clearable
              :placeholder="row.manual.ph">
              <el-option v-for="o in row.manual.options" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-input-number v-else v-model="form[row.manual.field]" :min="row.manual.min" :max="row.manual.max"
              :precision="row.manual.precision" :step="row.manual.step" controls-position="right" size="small"
              :placeholder="row.manual.ph" />
          </el-form-item>
          <span v-if="row.manual.unit" class="cell-unit">{{ row.manual.unit }}</span>
        </template>
        <span v-else class="cell-auto">{{ row.rawText || '—' }}</span>

        <span class="fd-sub-score" :class="{ unscored: row.score == null }">
          {{ row.score == null ? '未评' : row.score }}
        </span>
        <span v-if="row.bandHit" class="fd-hit">{{ row.bandHit }}</span>

        <p v-if="row.layers.length" class="fd-layer-row">
          <span v-for="l in row.layers" :key="l.key" class="fd-layer" :title="l.bandHit">
            <em class="layer-name">{{ l.label }}</em>
            <b class="layer-raw" :class="{ unscored: l.raw == null }">{{ l.raw == null ? '—' : l.raw }}</b>
            <i class="layer-score" :class="{ unscored: l.score == null }">{{ l.score == null ? '未评' : l.score + ' 分' }}</i>
          </span>
        </p>
        <p v-if="row.note" class="cell-note">{{ row.note }}</p>
      </div>
    </div>
    <div v-else class="none-hint">子指标清单未取到，等五维现算树刷新后再看。</div>

    <div v-if="list && list.length" class="cell-list">
      <span v-for="s in list" :key="s.code" class="list-item"
        :title="`${s.name} ${s.code} · 自涨停回撤 ${s.pullback}% · 收盘 ${s.pct}%${s.industry ? ' · ' + s.industry : ''}`">
        {{ s.name }}<i>-{{ s.pullback }}%</i>
      </span>
      <span v-if="listHidden" class="list-more">另有 {{ listHidden }} 家</span>
    </div>

    <p v-if="note" class="dim-note">{{ note }}</p>
  </section>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useScoringStore } from '../stores/scoring'
import { fiveDimBandOf, fiveDimBandClassOf, signed } from '../utils/scores'

const props = defineProps({
  date: { type: String, required: true },
  dimKey: { type: String, required: true },
  title: { type: String, required: true }
})

const scoring = useScoringStore()
const loading = ref(false)

const FIVE_RECORD_FIELD = {
  market: 'scoreMarket', theme_main: 'scoreThemeMain', board: 'scoreBoard',
  first: 'scoreFirst', anchor: 'scoreAnchor'
}

/** 这一维的元信息（分数、带、权重、bar 宽度）。 */
function fiveDimMeta() {
  const ev = scoring.dimEval(props.dimKey)
  const meta = scoring.fiveDimDims.find((d) => d.dimKey === props.dimKey) || {}
  const score = ev && ev.score != null ? Number(ev.score) : null
  return {
    ev,
    dimNo: (ev && ev.dimNo) || meta.dimNo || meta.dim,
    label: (ev && ev.label) || meta.label || props.dimKey,
    weight: ev && ev.weight != null ? Number(ev.weight) : (meta.weight ?? 0),
    score,
    band: fiveDimBandOf(score),
    bandClass: fiveDimBandClassOf(score),
    barWidth: score == null ? '0%' : `${Math.max(0, Math.min(100, score))}%`
  }
}

/** scoring_kind 中文标签。 */
const KIND_LABEL = {
  BAND_LADDER: '档位阶梯',
  STRATEGY: '策略算法',
  MANUAL: '人工读数',
  WEIGHTED_SUM: '加权合成',
  LAYER_WEIGHTED_BAND: '层级加权阶梯'
}

/**
 * 动态生成规则说明：
 *  - 统计 subs 里自动/人工子各几条，拼一句概览
 *  - 引擎 eval 树里的 dim note 追加在末尾（比如 board 维的"中位吹哨 ×0.8"）
 */
const note = computed(() => {
  const subs = scoring.subsByDim[props.dimKey] || []
  if (!subs.length) return ''

  const autoCount = subs.filter((s) => s.scoringKind !== 'MANUAL').length
  const manualCount = subs.length - autoCount

  let parts = []
  if (autoCount && manualCount) {
    parts.push(`${autoCount} 条自动 + ${manualCount} 条人工`)
  } else if (autoCount) {
    parts.push(`${autoCount} 条全自动`)
  } else if (manualCount) {
    parts.push(`${manualCount} 条全人工`)
  }

  // 拼每个 sub 的 label + kind + DB note（截断 30 字）
  subs.forEach((s) => {
    const kind = KIND_LABEL[s.scoringKind] || s.scoringKind || ''
    const hint = (s.note || '').slice(0, 30)
    const segment = [s.label, kind].filter(Boolean).join('·')
    parts.push(segment + (hint ? `（${hint}）` : ''))
  })

  // 引擎级追加
  if (meta.value.ev && meta.value.ev.note) {
    parts.push(meta.value.ev.note)
  }

  return parts.join('；')
})

/** 数字去掉浮点尾巴。 */
function fmtNum(v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return String(Math.round(n * 100) / 100)
}

/** score-detail 现算出来的最原始读数（指标 key → 值），供 STRATEGY 这类不带 raw 的节点拼读。 */
const metrics = computed(() => (scoring.detail && scoring.detail.metrics) || {})

/**
 * 各指标原始读数的展示单位。评分树叶子带 raw，但不带单位——按 sourceKey 还原成人话单位。
 * 返回 '%' / '家' / '天' / '板' / '级' / 'x100'(0-1 小数→%) / ''(比值/已是分)。
 */
function metricUnit(key) {
  if (key === 'persistence_days') return '天'
  if (key === 'max_height') return '板'
  if (key === 'catalyst_hardness') return '级'
  if (key === 'red_ratio') return 'x100'             // 红盘率入库是 0~1 小数
  if (key === 'turnover_ratio') return 'ratio'        // 量比，纯比值保留 2 位
  if (/^jr_|^prem_/.test(key)) return '%'             // 四层晋级率 / 四层溢价（已是 0~100）
  if (/^big_/.test(key)) return '家'                  // 四层大面家数
  if (key === 'sealed_home_rate' || key === 'reseal_rate') return '%'
  if (/(?:_rate|_pct)$/.test(key)) return '%'         // 首板封板率/溢价/聚集度等（已是 0~100）
  if (/_count$/.test(key)) return '家'                // 数量类
  return ''                                           // dragon_* 等本身就是 0~100 分
}

/** 把原始读数按指标单位渲染成人话：12.5%、9 家、4 板、1.15、80 分。 */
function fmtRaw(raw, key) {
  const n = Number(raw)
  if (!Number.isFinite(n)) return '—'
  const u = metricUnit(key)
  if (u === 'x100') return fmtNum(n * 100) + '%'
  if (u === 'ratio') return n.toFixed(2)
  if (u === '%') return fmtNum(n) + '%'
  if (u) return fmtNum(n) + ' ' + u
  return fmtNum(n)
}

/** STRATEGY 节点不带 raw，按内置树的两个策略键从 metrics 还原原始读数。 */
function strategyRaw(key) {
  const m = metrics.value
  if (key === 'index_env') {
    const pcts = ['index1_pct', 'index2_pct', 'index3_pct'].map((k) =>
      m[k] == null ? null : signed(Number(m[k]), 2) + '%')
    return pcts.some((p) => p != null) ? `三指 ${pcts.map((p) => p ?? '—').join(' / ')}` : ''
  }
  if (key === 'limit_combo') {
    if (m.limit_up_count == null && m.limit_down_count == null) return ''
    return `涨停 ${m.limit_up_count ?? '—'} / 跌停 ${m.limit_down_count ?? '—'}`
  }
  return ''
}

/** 四层并排一行，标签压短字头。 */
const LAYER_SHORT = { 中高位: '中高', 极高位: '极高', 低位: '低', 中位: '中' }
function shortLayer(label) {
  if (!label) return ''
  for (const k of ['中高位', '极高位', '低位', '中位']) {
    if (label.startsWith(k)) return LAYER_SHORT[k]
  }
  return label
}

/** 一条复合子下面的层。 */
function layersFor(subKey, kid) {
  const evalKids = kid && Array.isArray(kid.children) ? kid.children : []
  const fromDb = scoring.childrenOf(props.dimKey, subKey)
  const list = fromDb.length
    ? fromDb.map((c) => ({ key: c.subKey, label: c.label || c.subKey, ev: evalKids.find((k) => k.key === c.subKey) || null }))
    : evalKids.map((k) => ({ key: k.key, label: k.label || k.key, ev: k }))
  return list.map((x) => ({
    key: x.key,
    label: shortLayer(x.label),
    score: x.ev && x.ev.score != null ? fmtNum(x.ev.score) : null,
    raw: x.ev && x.ev.raw != null ? fmtRaw(x.ev.raw, x.key) : null,
    bandHit: (x.ev && x.ev.bandHit) || ''
  }))
}

/** 拼一行子指标的 shape。 */
function buildRow(node, kid) {
  const kind = (kid && kid.scoringKind) || node.scoringKind || ''
  // BAND/MANUAL 叶子自带 raw；STRATEGY 不带 raw，从 metrics 还原原始读数
  let rawText = ''
  if (kid && kid.raw != null) rawText = fmtRaw(kid.raw, node.key)
  else if (kind === 'STRATEGY') rawText = strategyRaw(node.key)
  return {
    key: node.key,
    label: node.label || node.key,
    weight: node.weight != null ? Number(node.weight) : null,
    score: kid && kid.score != null ? fmtNum(kid.score) : null,
    rawText,
    bandHit: (kid && kid.bandHit) || '',
    layers: layersFor(node.key, kid),
    note: (kid && kid.note) || '',
    kind
  }
}

/**
 * 主体 computed：这一维完整的展示数据。
 * 只读，不接管任何手动输入——那是 ReviewView 的事。
 */
const meta = computed(fiveDimMeta)

const rows = computed(() => {
  const subs = scoring.subsByDim[props.dimKey] || []
  const kids = meta.value.ev && Array.isArray(meta.value.ev.children) ? meta.value.ev.children : []
  const rowNodes = subs.length ? subs : kids.map((k) => ({ key: k.key, label: k.label, weight: k.weight }))
  return rowNodes.map((node) => {
    const row = buildRow(node, kids.find((k) => k.key === node.key) || null)
    row.filled = false
    return row
  })
})

/** board 维有大面名单，从 t_market_stock 本地读。 */
const LOSS_LIST_LIMIT = 20
const stocks = ref(null)

async function loadStocks() {
  if (props.dimKey !== 'board') return
  // 不走 api，跟 ReviewView 一样读本地 t_market_stock：
  // DimScoreBlock 是纯只读展示，不引入 recordApi 避免跟父组件的请求叠发。
  // 但 ReviewView 会调 marketApi.stocks，所以 stocks 数据其实在 ReviewView 的 context 里有。
  // 这里独立调一次——慢一点但不用依赖父组件。
  try {
    const { marketApi } = await import('../api/modules')
    const res = await marketApi.stocks(props.date)
    const data = res && res.data
    stocks.value = data && data.available ? data : null
  } catch (e) {
    stocks.value = null
  }
}

const list = computed(() => {
  if (props.dimKey !== 'board') return []
  return (stocks.value && stocks.value.bigLoss || []).slice(0, LOSS_LIST_LIMIT)
})
const listHidden = computed(() => {
  if (props.dimKey !== 'board') return 0
  return Math.max(0, (stocks.value?.bigLoss?.length || 0) - LOSS_LIST_LIMIT)
})

// 对外暴露的只读 shape（template 直接用）
const dimLabel = computed(() => meta.value.label)
const weight = computed(() => fmtNum(meta.value.weight))
const score = computed(() => meta.value.score == null ? null : fmtNum(meta.value.score))
const band = computed(() => meta.value.band || '—')
const bandClass = computed(() => meta.value.bandClass)
const barWidth = computed(() => meta.value.barWidth)

async function load() {
  loading.value = true
  try {
    scoring.load() // 保证 subsByDim 到位
    await scoring.loadDetail(props.date, true)
    await loadStocks()
  } finally {
    loading.value = false
  }
}

watch(() => props.date, load)
onMounted(load)
</script>

<style scoped>
.dim-score-block {
  background: #1a2332;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
}
.block-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
}
.block-head h3 {
  margin: 0;
  font-size: 15px;
  color: #e1e8ed;
}
.dim-meta {
  font-size: 12px;
  color: #8899a6;
  font-weight: 400;
  margin-left: 4px;
}
.dim-score {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
}
.dim-score.unscored {
  color: #8899a6;
  font-weight: 400;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 0 5px;
  font-size: 11px;
}
.dim-score.b-none { color: #64748b; }
.dim-score.b-ebb { color: #94a3b8; }
.dim-score.b-chaos { color: #60a5fa; }
.dim-score.b-ferment { color: #fbbf24; }
.dim-score.b-climax { color: #f87171; }

.fd-bar {
  height: 6px;
  background: #2d3748;
  border-radius: 3px;
  margin-bottom: 14px;
  overflow: hidden;
}
.fd-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width .4s ease;
}
.fd-bar-fill.b-none { background: #475569; }
.fd-bar-fill.b-ebb { background: #94a3b8; }
.fd-bar-fill.b-chaos { background: #60a5fa; }
.fd-bar-fill.b-ferment { background: #fbbf24; }
.fd-bar-fill.b-climax { background: #ef4444; }

.sub-rows {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.fd-sub-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: #0f1419;
  border-radius: 8px;
  flex-wrap: wrap;
}
.fd-sub-row.covered { opacity: 0.85; }

.cell-label {
  font-size: 13px;
  color: #e1e8ed;
  min-width: 110px;
}
.fd-weight {
  color: #64748b;
  font-size: 11px;
}
.cell-auto {
  color: #a8b7c4;
  font-size: 13px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.cell-unit {
  font-size: 11px;
  color: #8899a6;
}
.cell-item.fd-input {
  margin-bottom: 0;
}
.fd-sub-score {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
  color: #e1e8ed;
}
.fd-sub-score.unscored {
  color: #64748b;
  font-weight: 400;
  font-size: 11px;
  border: 1px solid #2d3748;
  border-radius: 4px;
  padding: 0 5px;
}
.fd-hit {
  font-size: 11px;
  color: #fbbf24;
}

.fd-layer-row {
  width: 100%;
  margin: 4px 0 0;
  padding: 0 0 0 110px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.fd-layer {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
  background: #131b26;
  border: 1px solid #233040;
  border-radius: 8px;
  padding: 6px 10px;
  min-width: 88px;
}
.layer-name {
  font-style: normal;
  font-size: 11px;
  color: #8899a6;
}
.layer-raw {
  font-size: 15px;
  font-weight: 700;
  color: #e1e8ed;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  line-height: 1.2;
}
.layer-raw.unscored {
  color: #4b5a68;
  font-weight: 500;
}
.layer-score {
  font-style: normal;
  font-size: 11px;
  color: #93a4b3;
}
.layer-score.unscored {
  color: #5b6c7d;
}

.cell-note {
  width: 100%;
  margin: 2px 0 0 110px;
  font-size: 11px;
  color: #6b7c8c;
  line-height: 1.5;
}

.cell-list {
  margin-top: 12px;
  padding: 10px 12px;
  background: #0f1419;
  border-radius: 8px;
}
.list-item {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  margin: 2px 8px 2px 0;
  font-size: 12px;
  color: #fca5a5;
}
.list-item i {
  font-style: normal;
  font-size: 11px;
  color: #f87171;
}
.list-more {
  font-size: 11px;
  color: #64748b;
  margin-left: 8px;
}

.dim-note {
  margin: 10px 0 0;
  padding: 8px 12px;
  background: rgba(8, 145, 178, 0.08);
  border: 1px solid rgba(8, 145, 178, 0.28);
  border-radius: 6px;
  font-size: 12px;
  color: #8899a6;
  line-height: 1.6;
}

.none-hint {
  color: #6b7c8c;
  font-size: 12px;
  padding: 8px 0;
}
</style>
