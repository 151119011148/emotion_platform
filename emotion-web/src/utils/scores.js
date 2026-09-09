/**
 * 与后端打分口径对齐用的两个小工具。
 *
 * <p>bandOf() 的七档阈值抄自后端 TemperatureCalculator#bandPremium：
 * 界面上要显示"这一维进了几分"却没有单独字段可取时用它现算，改那边必须改这里。
 */

/**
 * 分档溢价七档：>5=3 / >3=2 / >1=1 / >=0=0 / >=-3=-1 / >=-5=-2 / <-5=-3。
 * null 是未评，不是 0 分。
 */
export function bandOf(p) {
  if (p == null) return null
  const v = Number(p)
  if (Number.isNaN(v)) return null
  if (v > 5) return 3
  if (v > 3) return 2
  if (v > 1) return 1
  if (v >= 0) return 0
  if (v >= -3) return -1
  if (v >= -5) return -2
  return -3
}

/** 涨跌带正负号，null 一律出 em-dash——0.00% 是一个真实读数，不能拿来代替"没数"。 */
export function signed(p, digits = 2) {
  if (p == null) return '—'
  const v = Number(p)
  if (Number.isNaN(v)) return '—'
  return (v > 0 ? '+' : '') + v.toFixed(digits)
}
