/**
 * 与后端打分口径对齐用的小工具。
 *
 * <p>survivalBandOf() 抄的是后端 TemperatureCalculator#calcSurvivalScore（第 9 维涨停板接力）。
 * <b>不是</b> bandPremium：第 9 维从这轮重定标起用自己那套刻度，两者在 +1.5%（接力 2 分 / 溢价 0 分）
 * 和 -3.5%（-2 / -3）这类读数上分得很开，拿错那张表会把卡面上的分印成假的。
 * 界面上要显示"这一维进了几分"又没有单独字段可取时用它现算，改那边必须改这里。
 */

/**
 * 第 9 维接力五档：>=9.5=3 / >0=2 / >=-2=-1 / >=-5=-2 / 其余=-3。
 * 注意这套刻度跳过了 1 分与 0 分——涨不到 9.5% 又还在涨是 2，一旦转负立刻掉进负档。
 * null 是未评，不是 0 分。
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

/**
 * 九维的维序与权重。唯一真源是后端 TemperatureCalculator:40-48，那边改了必须改这里。
 * key 用的是 IndicatorCards 的卡片 key（第 9 维在那边叫 surv，后端叫 survival）。
 */
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

/**
 * 他读盘的那串顺序，成交额打头。<b>不是</b>打分引擎的维序：维序是 {@code DIMS[key].dim}，
 * 界面上那句「第 N 维」印的是它，所以照这一串摆过去编号会跳（6、3、1、2…），那是刻意的。
 *
 * <p>仪表盘九张卡与复盘页九维<b>共用这一份</b>。上一轮我把卡片排成引擎序，被他退回过一次
 * （「要用我之前发的顺序，成交额第一维」）；两边各写一串的话，下一次一定再漂一次。
 */
export const CARD_ORDER = [
  'volume', 'breadth', 'height', 'premium', 'surv', 'theme', 'anchor', 'loss', 'broken'
]

/** 权重总和 × 每维满分 3 = 温度公式的分母。 */
export const MAX_POSSIBLE =
  Object.values(DIMS).reduce((sum, d) => sum + d.weight, 0) * 3

/**
 * 一维进分的那句话，仪表盘 tooltip 的第一行。
 *
 * <p>「未评」和「0 分」必须长得不一样：新口径下分母固定是 39，两者对温度的影响确实同数，
 * 于是界面成了唯一还能把它们分开说清楚的地方。
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

/** 涨跌带正负号，null 一律出 em-dash——0.00% 是一个真实读数，不能拿来代替"没数"。 */
export function signed(p, digits = 2) {
  if (p == null) return '—'
  const v = Number(p)
  if (Number.isNaN(v)) return '—'
  return (v > 0 ? '+' : '') + v.toFixed(digits)
}
