/**
 * 与后端打分口径对齐用的两个小工具。
 *
 * <p>band() 的四档阈值抄自 03 篇，权威实现是 TemperatureCalculator#bandScore：
 * 界面上要显示"这一维进了几分"却没有单独字段可取时用它现算，改那边必须改这里。
 */

/**
 * 03 篇四档：>4=3 / >0=2 / >=-2=1 / 其它=0，最低档再切一刀 <-4=-1。null 是未评，不是 0 分。
 *
 * <p>-2% 以下原本是一整块，可是 -3%（当天只是不好看）和 -10.7%（池子被核按钮）不是一回事，
 * 后端 bandPremium 已把它们分开，这里跟着分开，否则卡片上的圆点和落库的分数互相打脸。
 */
export function bandOf(p) {
  if (p == null) return null
  const v = Number(p)
  if (Number.isNaN(v)) return null
  if (v > 4) return 3
  if (v > 0) return 2
  if (v >= -2) return 1
  return v >= -4 ? 0 : -1
}

/** 涨跌带正负号，null 一律出 em-dash——0.00% 是一个真实读数，不能拿来代替"没数"。 */
export function signed(p, digits = 2) {
  if (p == null) return '—'
  const v = Number(p)
  if (Number.isNaN(v)) return '—'
  return (v > 0 ? '+' : '') + v.toFixed(digits)
}
