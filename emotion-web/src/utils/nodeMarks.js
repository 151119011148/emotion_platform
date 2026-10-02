/**
 * 节点页曲线上那两枚标的落点算法（锚定龙头 ▼ / 节点票 ◆）。
 *
 * <p>后端 height-range 每天都带一份「当天 2 板及以上、各自几板」的名单（{@code ladder}），
 * 所以这两个日子在前端就能算出来，不用为节点页新开接口。
 *
 * <p>找不到就是<b>没有这一枚标</b>，不拿 D0 或今天硬凑一个位置：
 * 曲线上的点说的是「这只票那天真站在这个高度上」，凑出来的点比缺点更坏。
 */

/** 节点票存的是「名称(代码)」；手填的行可能只有名称。 */
export function splitStock(text) {
  const m = /^(.*?)\((\d{6})\)\s*$/.exec(text || '')
  return m ? { name: m[1], code: m[2] } : { name: text || '', code: '' }
}

/** 这只票当天在梯上的板高；不在梯上是 null（不是 0 板）。 */
function boardOn(row, code, name) {
  const hit = (row.ladder || []).find((s) => (code ? s.code === code : !!name && s.name === name))
  return hit ? hit.board : null
}

/**
 * @param rows height-range 映射后的日行，按日期升序，每行带 ladder:[{board,code,name}]
 * @param kind 'anchor' 龙头：D0 之前最后一次站上新高的那天；'stock' 票：D0 之后第一次到位的那天
 */
export function markDay(node, rows, kind) {
  if (!rows.length) return null
  const d0 = node.d0Date
  if (kind === 'anchor') {
    const anchor = splitStock(node.anchorStock)
    const name = node.anchorName || anchor.name
    const code = node.anchorCode || anchor.code
    const target = node.anchorMaxBoard
    if (!target) return null
    for (let i = rows.length - 1; i >= 0; i--) {
      const r = rows[i]
      if (d0 && r.date > d0) continue
      if (boardOn(r, code, name) === target) return { date: r.date, board: target }
    }
    return null
  }
  const target = node.nodeStockMaxBoard
  if (!target || !node.nodeStock) return null
  const { name, code } = splitStock(node.nodeStock)
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i]
    if (d0 && r.date < d0) continue
    if (boardOn(r, code, name) === target) return { date: r.date, board: target }
  }
  return null
}

/**
 * 全部节点 → 曲线的 nodeMarks。
 * @param infoOf 由调用方给的浮层读数（节点页才知道的中文标签与验证话）
 */
export function buildNodeMarks(nodes, rows, infoOf) {
  const out = []
  for (const n of nodes || []) {
    const info = infoOf(n)
    const a = markDay(n, rows, 'anchor')
    if (a) {
      out.push({
        id: n.id, kind: 'anchor', date: a.date, board: a.board, status: n.status, info,
        name: n.anchorName || splitStock(n.anchorStock).name || '龙头', code: n.anchorCode || ''
      })
    }
    const s = markDay(n, rows, 'stock')
    if (s) {
      const st = splitStock(n.nodeStock)
      out.push({ id: n.id, kind: 'stock', date: s.date, board: s.board, status: n.status, info, name: st.name, code: st.code })
    }
  }
  return out
}
