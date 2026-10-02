/**
 * 节点页曲线上那两条轨迹的落点算法（●龙头票 / ◆节点票，逐日板高）。
 *
 * <p>后端 height-range 每天都带一份「当天 2 板及以上、各自几板」的名单（{@code ladder}），
 * 所以这两条线在前端就能算出来，不用为节点页新开接口。
 *
 * <p>一只票在窗口里可能有<b>两段行情</b>（金健米业 08-18→08-20 打到 4 板，09-18 又以 2 板回来），
 * 所以断板那天不能取「最后一次在梯」，得取关键日所在那一段连板的末尾。
 *
 * <p>一天都不在梯就是<b>没有这条轨迹</b>，不拿 D0 或今天硬凑位置：
 * 线上的点说的是「这只票那天真站在这个高度上」，凑出来的点比缺点更坏。
 */

/** 节点票存的是「名称(代码)」；手填的龙头行可能只有名称。 */
export function splitStock(text) {
  const m = /^(.*?)\((\d{6})\)\s*$/.exec(text || '')
  return m ? { name: m[1], code: m[2] } : { name: text || '', code: '' }
}

/** 这只票当天在梯上的板高；不在梯上是 null（不是 0 板）。 */
export function boardOn(row, code, name) {
  const hit = (row.ladder || []).find((s) => (code ? s.code === code : !!name && s.name === name))
  return hit ? hit.board : null
}

/** 逐日板高：与 rows 同长同序，不在梯那天是 null——null 就是曲线上的断口。 */
function trackOf(rows, code, name) {
  return rows.map((r) => boardOn(r, code, name))
}

/** 龙头票要标的那天：D0 之前（含当天）最后一次站上 anchorMaxBoard。 */
function anchorKeyIdx(data, max, rows, d0) {
  for (let i = data.length - 1; i >= 0; i--) {
    if (d0 && rows[i].date > d0) continue
    if (data[i] === max) return i
  }
  return null
}

/** 节点票要标的那天：D0 之后（含当天）第一次站上 nodeStockMaxBoard。 */
function stockKeyIdx(data, max, rows, d0) {
  for (let i = 0; i < data.length; i++) {
    if (d0 && rows[i].date < d0) continue
    if (data[i] === max) return i
  }
  return null
}

/** 全程最高那天：记的板高在天梯里找不到时退到这里，标签报的是实测值不是记的那个。 */
function peakIdx(data) {
  let best = -1
  let at = null
  data.forEach((v, i) => {
    if (v != null && v > best) { best = v; at = i }
  })
  return at
}

/** 关键日所在那段连板的末尾：往后只要还高一板就仍是同一段。 */
function runEndIdx(data, from) {
  let i = from
  while (i + 1 < data.length && data[i + 1] === data[i] + 1) i++
  return i
}

/**
 * 全部节点 → 曲线的 nodeTracks。下标一律按**全量** rows 算，组件再按当前窗口切片。
 * @param rows height-range 映射后的日行，按日期升序，每行带 ladder:[{board,code,name}]
 */
export function buildNodeTracks(nodes, rows) {
  const out = []
  for (const n of nodes || []) {
    const track = (name, code, max, which) => {
      if (!name || !max || !rows.length) return null
      const data = trackOf(rows, code, name)
      const byRule = which === 'anchor'
        ? anchorKeyIdx(data, max, rows, n.d0Date)
        : stockKeyIdx(data, max, rows, n.d0Date)
      const keyIdx = byRule != null ? byRule : peakIdx(data)
      if (keyIdx == null) return null
      return { name, code, max, data, keyIdx, endIdx: runEndIdx(data, keyIdx) }
    }
    const a = splitStock(n.anchorStock)
    const s = splitStock(n.nodeStock)
    const anchor = track(n.anchorName || a.name, n.anchorCode || a.code, n.anchorMaxBoard, 'anchor')
    const stock = n.nodeStock ? track(s.name, s.code, n.nodeStockMaxBoard, 'stock') : null
    if (!anchor && !stock) continue
    out.push({ id: n.id, status: n.status, d0Date: n.d0Date, anchor, stock })
  }
  return out
}
