import api from './index'

export const authApi = {
  login: (data) => api.post('/auth/login', data),
  register: (data) => api.post('/auth/register', data)
}

export const recordApi = {
  create: (data) => api.post('/records', data),
  update: (id, data) => api.put(`/records/${id}`, data),
  getToday: () => api.get('/records/today'),
  getByDate: (date) => api.get(`/records/date/${date}`),
  getRange: (start, end) => api.get('/records/range', { params: { start, end } }),
  getLatest: (days = 20) => api.get('/records/latest', { params: { days } }),
  getAdvice: () => api.get('/records/advice'),
  getCurve: (days = 20) => api.get('/records/curve', { params: { days } }),
  // 只重算那一天。行情字段有改动后想立刻看分数落点时用得上
  recalc: (date) => api.post('/records/recalc', null, { params: { date }, timeout: 25000 }),
  // 只读复盘文档：把那天系统取数按手写版式排成 md（【一】…【九】），供下载补判断
  reviewDoc: (date) => api.get('/records/review-doc', { params: { date }, timeout: 30000 }),
  /**
   * 五维 score-detail：服务端现场装配 metrics + 生效树、走 BoardScoreCalculator 返回整棵 eval 树。
   * 只读、不落库；改了权重/阈值立刻在卡片反映，不用 recalc-all。超时对齐 score-context 那档 60s。
   * skipErrorToast：取不到时页面自己印「读数未取到」并退回落库维分，红条只会把一个降级说成事故。
   */
  scoreDetail: (date) => api.get('/records/score-detail', { params: { date }, timeout: 60000, skipErrorToast: true }),
  /**
   * 持仓台账：body 是这天的<b>全部</b>行，服务端整日替换，所以调用方必须把行发全——少发一行就是删掉一行。
   * 预判与对答案没有编辑口了：{@code t_prediction} 只由那天导入的 md 整日替换（PLAN 与 ANSWER 一起换）。
   */
  savePositions: (date, rows) => api.put('/records/positions', rows, { params: { date } })
}

export const nodeApi = {
  list: () => api.get('/nodes'),
  getCurrent: () => api.get('/nodes/current'),
  create: (data) => api.post('/nodes', data),
  update: (id, data) => api.put(`/nodes/${id}`, data),
  // 复算建议：只读，不写库。判据不齐时它自己会说"缺哪一样"
  suggest: (id) => api.get(`/nodes/${id}/suggest`),
  // 采纳只回传指纹，那八个值由服务端重算并逐字段比对后落库
  adopt: (id, fingerprint) => api.post(`/nodes/${id}/adopt`, { fingerprint }),
  // 「从今日天梯新增节点」的轻量预填：D0日期/涨停跌停家数/最高板/今日龙头候选。只读本地表
  ladderIntel: (date) => api.get('/nodes/ladder-intel', { params: { date }, skipErrorToast: true })
}

/**
 * PRD 2.0 三条只读页面：连板天梯 / 首板池 / 主线详情。
 * 明细是本地表，但主线判定与龙头分工可能顺带补拉日 K，超时放宽到 25s 与 snapshot 同档；
 * 取不到时页面自己印空态，不弹红条把"没数据"说成事故。
 */
export const prdApi = {
  tianti: (date) => api.get('/tianti', { params: { date }, timeout: 25000, skipErrorToast: true }),
  shouban: (date) => api.get('/shouban', { params: { date }, timeout: 25000, skipErrorToast: true }),
  mainline: (date) => api.get('/mainline', { params: { date }, timeout: 25000, skipErrorToast: true }),
  // 双轨 v0.2：雷达区「升级到主线区」写接口（落 t_mainline_mark）
  promote: (tradeDate, industry) => api.post('/mainline/promote', { tradeDate, industry }, { timeout: 25000 }),
  cancelPromote: (tradeDate, industry) => api.delete('/mainline/promote', { data: { tradeDate, industry }, timeout: 25000 })
}

/**
 * D5 高位生态（阵眼·抱团·监管）融合页：/api/d5/high。
 * 阵眼是你账号人工配置的 t_anchor（带起止区间）；抱团与监管名单是公开事实。
 * 服务端现场装配、可能顺带现推监管期，放宽到与 snapshot 同档 25s；取不到时页面印空态不弹红条。
 */
export const d5Api = {
  high: (date) => api.get('/d5/high', { params: { date }, timeout: 25000, skipErrorToast: true })
}

/**
 * 连板天梯人工总龙头：/api/leader。写侧只落库（不联网），GET 返回当前设定或 null。
 * 天梯自动最高板标"空间板"，"总龙头"是账号各自手动指定的身份。
 */
export const leaderApi = {
  get: (date) => api.get('/leader', { params: { date }, skipErrorToast: true }),
  save: (date, code) => api.put('/leader', { tradeDate: date, code }, { skipErrorToast: true }),
  clear: (date) => api.delete('/leader', { params: { date }, skipErrorToast: true })
}

export const marketApi = {
  // 后端拉行情最长 12s，实例默认 10s 会先超时弹红条，这里必须单独放宽
  snapshot: (date, refresh = false) =>
    api.get('/market/snapshot', { params: { date, refresh }, timeout: 25000 }),
  // 明细是本地表，不打上游，用默认超时即可。取砸了只空掉复盘页那排大面 chips，不弹红条
  stocks: (date) => api.get('/market/stocks', { params: { date }, skipErrorToast: true }),
  // 大盘生态页·五大指数（公开表 t_index_close）；不传日期后端回落最近交易日
  indexes: (date) => api.get('/market/indexes', { params: { date }, skipErrorToast: true }),
  // 大盘生态页·实时涨跌家数（东财 f104/105/106，无历史日期）
  breadth: () => api.get('/market/breadth', { skipErrorToast: true }),
  premiumTiers: (date) => api.get('/market/premium-tiers', { params: { date } }),
  /**
   * 子项读数（第 4 维两条家数口径 + 第 8/9 维）。刻意不和 snapshot 并成一次：
   * 阵眼与监管名单逐只打日 K，最坏几十次上游，混进去就是"只要七个数却被上游拖成一整屏红"。
   * 所以它自己加载、自己降级，超时放宽到 60s（和导入预览同一档）。
   */
  scoreContext: (date) => api.get('/market/score-context', { params: { date }, timeout: 60000 })
}

export const importApi = {
  // 复盘页底部那块只读明细。md 导入本身还有四个端点（POST /records/import、
  // /import/template、/import/export），页面撤了但契约没撤，要用走 curl。
  detail: (date) => api.get('/records/import/detail', { params: { date } })
}

/**
 * 每日复盘 v2.0 的一键拉取编排 + D1-D5 分块数据 + 人工补录/导出。
 *
 * <p>{@code fetch} 是唯一不走 axios 的：{@code /api/review/fetch} 返回 SSE 事件流，
 * {@code EventSource} 只认 GET 且带不了 Authorization，所以用 {@code fetch}+流式 reader
 * 自己解析 {@code data:} 行，把每个任务的进度片推给回调。token 从 localStorage 取，
 * 与 axios 拦截器读写的是同一个 key。
 */
export const reviewApi = {
  // SSE 一键拉取：date 为该日，onEvent({task,status,rows,msg})，完成时 resolve(true)
  fetch: (date, onEvent) =>
    fetch(`/api/review/fetch?date=${encodeURIComponent(date)}`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${localStorage.getItem('token') || ''}`,
        Accept: 'text/event-stream'
      },
      body: null
    }).then(async (resp) => {
      if (!resp.ok || !resp.body) throw new Error(`拉取失败 HTTP ${resp.status}`)
      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let i
        while ((i = buf.indexOf('\n\n')) !== -1) {
          const block = buf.slice(0, i)
          buf = buf.slice(i + 2)
          for (const line of block.split('\n')) {
            if (line.startsWith('data:')) {
              const raw = line.slice(5).trim()
              if (raw) {
                try { onEvent(JSON.parse(raw)) } catch (e) { /* 忽略非 JSON 行 */ }
              }
            }
          }
        }
      }
      return true
    }),
  status: (date) => api.get('/review/fetch/status', { params: { date }, skipErrorToast: true }),
  detail: (date) => api.get('/review/detail', { params: { date }, timeout: 25000, skipErrorToast: true }),
  exportDoc: (date) => api.get('/review/export', { params: { date }, timeout: 30000 }),
  save: (data) => api.post('/review/save', data, { timeout: 25000 }),
  // T7 无自动源时人工补录监管：{code, name, kind, title, date}
  manualSurveillance: (data) => api.post('/surveillance/manual', data),
  // D2 题材索引：低频全量重建（约 500 板块，耗时长）/ 查当前索引规模
  buildConcepts: () => api.post('/review/concepts/build', null, { timeout: 600000 }),
  conceptsStatus: () => api.get('/review/concepts/status', { skipErrorToast: true })
}

/**
 * 阵眼配置（t_anchor，绑用户）。config 是「某日在位阵眼」的轻量列表：id/名称/角色/跨度，
 * 供节点页的新建弹框选「锚定龙头」；只查库不打行情，静默降级。
 */
export const anchorsApi = {
  config: (date) => api.get('/anchors/config', { params: { date }, skipErrorToast: true }),
  add: (data) => api.post('/anchors', data)
}

/**
 * 打分配置（模型 / 维度 / 计算规则）。后端三张表平台全局共享、不绑用户。
 *
 * <p>{@code effective} 是当前生效模型的维度/权重视图，卡片与复盘页的维序、权重、名称都从这里来；
 * 加了 skipErrorToast——后端读不到配置时前端要静默退回 utils/scores 的本地兜底，而不是每次加载弹红条。
 */
export const scoringApi = {
  effective: () => api.get('/scoring/effective', { skipErrorToast: true }),
  listModels: () => api.get('/scoring/models'),
  model: (id) => api.get(`/scoring/models/${id}`),
  createModel: (data) => api.post('/scoring/models', data),
  updateModel: (id, data) => api.put(`/scoring/models/${id}`, data),
  deleteModel: (id) => api.delete(`/scoring/models/${id}`),
  activate: (id) => api.post(`/scoring/models/${id}/activate`),
  createDim: (data) => api.post('/scoring/dims', data),
  updateDim: (id, data) => api.put(`/scoring/dims/${id}`, data),
  deleteDim: (id) => api.delete(`/scoring/dims/${id}`),
  createSub: (data) => api.post('/scoring/subs', data),
  updateSub: (id, data) => api.put(`/scoring/subs/${id}`, data),
  deleteSub: (id) => api.delete(`/scoring/subs/${id}`),
  createRule: (data) => api.post('/scoring/rules', data),
  updateRule: (id, data) => api.put(`/scoring/rules/${id}`, data),
  deleteRule: (id) => api.delete(`/scoring/rules/${id}`)
}
