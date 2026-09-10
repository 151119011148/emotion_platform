<template>
  <div class="indicator-cards">
    <div class="card" v-for="c in cards" :key="c.key">
      <el-tooltip
        placement="bottom"
        :show-after="150"
        popper-class="card-tip"
      >
        <template #content>
          <div class="tip">
            <div class="tip-head">
              <span class="tip-title">第 {{ c.dimNo }} 维 · {{ c.label }}</span>
              <span class="tip-weight">×{{ fmt(c.weight) }}</span>
            </div>
            <div class="tip-score" :class="c.bandClass">
              {{ c.score == null ? '未评（不计入加权）' : c.scoreText + ' 分 · ' + c.bandName }}
            </div>
            <div v-if="c.note" class="tip-note">{{ c.note }}</div>

            <template v-if="c.subs.length">
              <div class="tip-divider"></div>
              <div v-for="(s, i) in c.subs" :key="i" class="tip-row">
                <div class="tip-row-main">
                  <span class="tip-sub-label">{{ s.label }}</span>
                  <span class="tip-sub-weight">×{{ fmt(s.weight) }}</span>
                  <span class="tip-sub-score" :class="{ unscored: s.score == null }">
                    {{ s.score == null ? '未评' : fmt(s.score) }}
                  </span>
                </div>
                <div v-if="s.detail" class="tip-sub-detail">{{ s.detail }}</div>
                <div v-for="(l, j) in s.layers" :key="'l' + j" class="tip-sub-layer">
                  └ {{ l.label }} · {{ l.score == null ? '未评' : fmt(l.score) }}
                  <span v-if="l.raw != null" class="tip-sub-layer-raw">
                    （raw={{ fmt(l.raw) }}{{ l.bandHit ? ' · ' + l.bandHit : '' }}）
                  </span>
                </div>
              </div>
            </template>
            <div v-else-if="scoring.detailLoading" class="tip-empty">子分读数加载中…</div>
            <div v-else-if="c.hasEval" class="tip-empty">这一维没有子层：整维就一个分</div>
            <div v-else-if="c.fromRecord" class="tip-empty">
              子分读数未取到（只显示已落库的维分）
            </div>
            <div v-else class="tip-empty">
              子分读数未取到，且这天还没有落库记录
            </div>

            <div v-if="c.notes && c.notes.length" class="tip-notes">
              <div v-for="(n, i) in c.notes" :key="'n' + i">{{ n }}</div>
            </div>
          </div>
        </template>

        <div class="card-inner hoverable">
          <div class="card-head">
            <span class="card-label">{{ c.dimNo }} · {{ c.label }}</span>
            <span class="card-weight">×{{ fmt(c.weight) }}</span>
          </div>
          <div class="card-value" :class="{ unscored: c.score == null }">
            {{ c.score == null ? '未评' : fmt(c.score) }}
          </div>
          <div class="card-bar">
            <div class="card-bar-fill" :class="c.bandClass" :style="{ width: c.barWidth }"></div>
          </div>
          <div class="card-band" :class="c.bandClass">{{ c.bandName }}</div>
          <div v-if="c.forcedEbb" class="card-forced">强制退潮</div>
        </div>
      </el-tooltip>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { fiveDimBandOf, fiveDimBandClassOf } from '../utils/scores'
import { useScoringStore } from '../stores/scoring'

const props = defineProps({
  /** 当日 t_daily_record（含 score_market/score_theme_main/…、forced_ebb、signal_flags） */
  record: { type: Object, default: null }
})

/**
 * 五维卡：卡主体只印"这一维 0-100 分 + 落哪条带"，子层树全在 tooltip。
 *
 * 分数来源：优先 {@code scoring.detail}（每卡现算，改一 sub 权重立刻反映），
 * 拉不到（loading / 后端不可达 / 该日还没录行情）时退回 `record.score_*`——
 * 那是上一次 recalc 落库的值，与表可能漂移，tooltip 明写"未取到"提醒。
 * 单位一律 0-100 分；未评（null）绝不兜 0，剔出分母是引擎的口径。
 */
const scoring = useScoringStore()

const FIVE_TO_RECORD = {
  market: 'scoreMarket',
  theme_main: 'scoreThemeMain',
  board: 'scoreBoard',
  first: 'scoreFirst',
  anchor: 'scoreAnchor'
}

function fmt(v) {
  if (v == null) return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return (Math.round(n * 100) / 100).toString()
}

/** 一 sub 的 tooltip 行：叶 → raw + 命中档；复合 → 层清单。 */
function flattenSub(s) {
  const row = {
    label: s.label || s.key,
    weight: s.weight,
    score: s.score,
    detail: '',
    layers: []
  }
  const kids = Array.isArray(s.children) ? s.children : []
  if (kids.length) {
    // 复合子（LAYER_WEIGHTED_BAND / WEIGHTED_SUM）：把每个层单独摆一行
    row.layers = kids.map((k) => ({
      label: k.label || k.key,
      score: k.score,
      raw: k.raw,
      bandHit: k.bandHit
    }))
    if (s.scoringKind === 'WEIGHTED_SUM') row.detail = '加权子分（非四层）'
  } else {
    // 叶：BAND_LADDER / MANUAL / STRATEGY
    const parts = []
    if (s.raw != null) parts.push(`raw=${fmt(s.raw)}`)
    if (s.bandHit) parts.push(s.bandHit)
    if (s.scoringKind === 'STRATEGY') parts.push('策略计算')
    if (s.scoringKind === 'MANUAL' && s.score == null) parts.push('人工未评')
    row.detail = parts.join(' · ')
  }
  return row
}

const cards = computed(() => {
  const r = props.record
  const dims = scoring.detail?.dims
  const dimMap = {}
  if (Array.isArray(dims)) for (const d of dims) dimMap[d.key] = d
  const forced = scoring.detail?.forcedEbb === true || r?.forcedEbb === 1
  const globalNotes = Array.isArray(scoring.detail?.notes) ? scoring.detail.notes : []
  return scoring.fiveDimCardOrder.map((key) => {
    const meta = scoring.fiveDimDims.find((d) => d.dimKey === key) || {}
    const evalNode = dimMap[key] || null
    const fromRecord = !evalNode && r
    const rawScore = evalNode ? evalNode.score
      : (r ? r[FIVE_TO_RECORD[key]] : null)
    const band = fiveDimBandOf(rawScore)
    const subs = evalNode && Array.isArray(evalNode.children)
      ? evalNode.children.map(flattenSub) : []
    // 只把和这一维相关的 note 挂上（简化：全量挂，反正 tooltip 空间够）
    return {
      key,
      dimNo: evalNode?.dimNo ?? meta.dimNo ?? meta.dim,
      label: evalNode?.label ?? meta.label ?? key,
      weight: evalNode?.weight ?? meta.weight,
      score: rawScore,
      scoreText: rawScore == null ? '未评' : fmt(rawScore),
      bandName: band || '—',
      bandClass: fiveDimBandClassOf(rawScore),
      barWidth: rawScore == null ? '0%' : `${Math.max(0, Math.min(100, Number(rawScore)))}%`,
      note: evalNode?.note || (fromRecord ? 'score-detail 未取到，显示的是落库的维分' : ''),
      subs,
      notes: globalNotes,
      fromRecord: !!fromRecord,
      hasEval: !!evalNode,
      forcedEbb: forced
    }
  })
})
</script>

<style scoped>
.indicator-cards {
  display: grid;
  /* 5 张卡：minmax 保证窄容器下也不会挤成一列一字 */
  grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.card {
  background: #1a2332;
  border-radius: 10px;
  padding: 14px 16px;
  position: relative;
}
.card-inner.hoverable {
  cursor: default;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
}
.card-label {
  font-size: 12px;
  color: #8899a6;
}
.card-weight {
  font-size: 11px;
  color: #5b6c7d;
}
.card-value {
  font-size: 26px;
  font-weight: 700;
  color: #e1e8ed;
  line-height: 30px;
}
.card-value.unscored {
  font-size: 18px;
  color: #8899a6;
  font-weight: 500;
}
.card-bar {
  margin: 8px 0 6px;
  height: 6px;
  border-radius: 3px;
  background: #223041;
  overflow: hidden;
}
.card-bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.3s ease;
}
.card-band {
  font-size: 11px;
  color: #8899a6;
  text-align: left;
}
.card-forced {
  position: absolute;
  top: 8px;
  right: 8px;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  color: #fecaca;
  background: #7f1d1d;
  border: 1px solid #b91c1c;
  letter-spacing: 0.5px;
}

/* 4 带色：与 Dashboard 页头温度色一致（灰 / 蓝 / 橙 / 红） */
.b-none { background: #2d3748; }
.b-ebb { color: #94a3b8; }
.card-bar-fill.b-ebb { background: #64748b; }
.b-chaos { color: #60a5fa; }
.card-bar-fill.b-chaos { background: #3b82f6; }
.b-ferment { color: #fbbf24; }
.card-bar-fill.b-ferment { background: #f59e0b; }
.b-climax { color: #f87171; }
.card-bar-fill.b-climax { background: #ef4444; }
</style>

<style>
/* popper 挂在 body 下，scoped 到不了 */
.card-tip {
  max-width: 400px;
}
.card-tip .tip-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-weight: 700;
  margin-bottom: 4px;
}
.card-tip .tip-weight {
  color: #8899a6;
  font-weight: 400;
}
.card-tip .tip-score {
  font-weight: 700;
  margin-bottom: 4px;
}
.card-tip .tip-score.b-ebb { color: #94a3b8; }
.card-tip .tip-score.b-chaos { color: #60a5fa; }
.card-tip .tip-score.b-ferment { color: #fbbf24; }
.card-tip .tip-score.b-climax { color: #f87171; }
.card-tip .tip-note {
  color: #d97706;
  font-size: 12px;
  margin-bottom: 4px;
}
.card-tip .tip-divider {
  border-top: 1px solid #2d3748;
  margin: 6px 0;
}
.card-tip .tip-row {
  line-height: 1.55;
  margin-bottom: 4px;
}
.card-tip .tip-row-main {
  display: flex;
  gap: 8px;
  align-items: baseline;
}
.card-tip .tip-sub-label {
  flex: 1 1 auto;
}
.card-tip .tip-sub-weight {
  color: #5b6c7d;
  font-size: 11px;
}
.card-tip .tip-sub-score {
  color: #f59e0b;
  font-weight: 700;
  min-width: 42px;
  text-align: right;
}
.card-tip .tip-sub-score.unscored {
  color: #8899a6;
  font-weight: 400;
}
.card-tip .tip-sub-detail {
  color: #8899a6;
  font-size: 12px;
  padding-left: 8px;
}
.card-tip .tip-sub-layer {
  color: #a8b7c4;
  font-size: 12px;
  padding-left: 12px;
}
.card-tip .tip-sub-layer-raw {
  color: #7d8d9d;
}
.card-tip .tip-empty {
  color: #8899a6;
  font-style: italic;
  margin-top: 4px;
}
.card-tip .tip-notes {
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #2d3748;
  color: #d97706;
  font-size: 12px;
}
</style>
