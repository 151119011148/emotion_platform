/**
 * 与后端打分口径对齐用的小工具。
 *
 * <p>两套并存：
 *   <ul>
 *     <li>五维双层模型（{@link FIVE_DIM_DIMS} / {@link FIVE_DIM_ORDER} / {@link FIVE_DIM_MAX}）
 *         是新的 active 模型；每维 0-100 打分、按维权直加成 0-100 总分。后端唯一真源是
 *         BoardScoreCalculator#builtinTree。</li>
 *     <li>旧 9 维（{@link DIMS} / {@link CARD_ORDER} / {@link MAX_POSSIBLE} / {@link survivalBandOf}）
 *         仍在 DailyRecord 的 score_height..surv_* 列中回填、IndicatorCards 的 legacy 卡片仍在展示，
 *         所以在替换完成前保留。后端唯一真源是 TemperatureCalculator#builtinModel。</li>
 *   </ul>
 * 任何一边改了常量都必须同步这里，否则界面印出的分与引擎实际算出来的会静默漂移。
 */

// ==================== 五维双层（新，active=five_dim）====================

/** 五维一级维：dimNo / label / weight（维权和=1.00）。 */
export const FIVE_DIM_DIMS = {
  market: { dim: 1, label: '大盘生态', weight: 0.25, recordColumn: 'scoreMarket' },
  theme_main: { dim: 2, label: '主线明确度', weight: 0.20, recordColumn: 'scoreThemeMain' },
  board: { dim: 3, label: '连板生态', weight: 0.25, recordColumn: 'scoreBoard' },
  first: { dim: 4, label: '首板生态', weight: 0.15, recordColumn: 'scoreFirst' },
  anchor: { dim: 5, label: '阵眼', weight: 0.15, recordColumn: 'scoreAnchor' }
}

/** 仪表盘卡片摆放序：与后端 dim_no 一致。 */
export const FIVE_DIM_ORDER = ['market', 'theme_main', 'board', 'first', 'anchor']

/** 总分满分——五维模型直加权，0-100，不再 (x+M)/2M 映射。 */
export const FIVE_DIM_MAX = 100

/** 4 带（交易纪律）；边界与后端 BoardScoreCalculator.stageOf 一致。 */
export const FIVE_DIM_BANDS = [
  { name: '高潮', min: 85, max: 100, cls: 'b-climax' },
  { name: '发酵', min: 60, max: 84.999, cls: 'b-ferment' },
  { name: '混沌', min: 40, max: 59.999, cls: 'b-chaos' },
  { name: '退潮', min: 0, max: 39.999, cls: 'b-ebb' }
]

/** 分 → 带对象；null / NaN / 落在带外都返回 null（未评绝不退化成"退潮"）。 */
function bandOf(score) {
  if (score == null) return null
  const v = Number(score)
  if (Number.isNaN(v)) return null
  for (const b of FIVE_DIM_BANDS) {
    if (v >= b.min && v <= b.max) return b
  }
  return null
}

/** 分 → 4 带名；null / NaN 返回 null，不兜"退潮"，让调用方自己判"未评"。 */
export function fiveDimBandOf(score) {
  const b = bandOf(score)
  return b ? b.name : null
}

/** 分 → 带样式类名（横条与文字同色）；未评返回 b-none。类名由这里独家定义。 */
export function fiveDimBandClassOf(score) {
  const b = bandOf(score)
  return b ? b.cls : 'b-none'
}

// ==================== 旧 9 维（legacy，仅显示回填列）====================

/**
 * 第 9 维接力五档（对齐旧 TemperatureCalculator#calcSurvivalScore）：
 *   >=9.5=3 / >0=2 / >=-2=-1 / >=-5=-2 / 其余=-3。
 * null 是未评，不是 0。
 */
export function survivalBandOf(p) {
  if (p == null) return null
  const v = Number(p)
  if (Number.isNaN(v)) return null
  if (v >= 9.5) return 3
  if (v > 0) return 2
  if (v >= -2) return -1
  if (v >= -5) return -2
  return -3
}

/** 旧 9 维维序与权重（唯一真源 TemperatureCalculator#builtinModel）。 */
export const DIMS = {
  height: { dim: 1, label: '连板高度', weight: 1.0 },
  premium: { dim: 2, label: '分档溢价', weight: 1.0 },
  breadth: { dim: 3, label: '涨停/跌停', weight: 2.0 },
  broken: { dim: 4, label: '炸板率', weight: 1.5 },
  loss: { dim: 5, label: '大面数', weight: 1.0 },
  volume: { dim: 6, label: '成交额', weight: 2.0 },
  theme: { dim: 7, label: '主线明确度', weight: 2.0 },
  anchor: { dim: 8, label: '阵眼当日', weight: 1.5 },
  surv: { dim: 9, label: '异动监管', weight: 1.0 }
}

/** 旧 9 维仪表盘卡片摆放序。 */
export const CARD_ORDER = [
  'volume', 'breadth', 'height', 'premium', 'surv', 'theme', 'anchor', 'loss', 'broken'
]

/** 旧 9 维权重和 × 每维满分 3 = 温度分母。 */
export const MAX_POSSIBLE =
  Object.values(DIMS).reduce((sum, d) => sum + d.weight, 0) * 3

/**
 * 一维进分那句 tooltip（旧 9 维；新五维由 stores/scoring.dimScoreLineFive 走树）。
 */
export function dimScoreLine(key, score) {
  const d = DIMS[key]
  if (!d) return ''
  if (score == null) {
    return `第 ${d.dim} 维 · ${d.label} · 未评（不进分子，分母仍是 ${MAX_POSSIBLE}）`
  }
  const fmt = (v) => (v > 0 ? '+' : '') + Math.round(v * 100) / 100
  const v = Number(score)
  return `第 ${d.dim} 维 · ${d.label} · ${fmt(v)} 分 × 权重 ${d.weight} = 加权 ${fmt(v * d.weight)}`
}

/** 涨跌带正负号；null 一律 em-dash——0.00% 是真实读数，不能代替"没数"。 */
export function signed(p, digits = 2) {
  if (p == null) return '—'
  const v = Number(p)
  if (Number.isNaN(v)) return '—'
  return (v > 0 ? '+' : '') + v.toFixed(digits)
}
