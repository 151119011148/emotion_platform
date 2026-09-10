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
  // 阵眼一改，跨度里每一天的第 8 维都变，只能整体重跑（逐日要拉行情，慢但必须一致）
  recalcAll: () => api.post('/records/recalc-all', null, { timeout: 180000 }),
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

export const themeApi = {
  list: () => api.get('/themes'),
  create: (data) => api.post('/themes', data),
  update: (id, data) => api.put(`/themes/${id}`, data),
  listStocks: (themeId) => api.get(`/themes/${themeId}/stocks`),
  addStock: (themeId, data) => api.post(`/themes/${themeId}/stocks`, data)
}

export const nodeApi = {
  list: () => api.get('/nodes'),
  getCurrent: () => api.get('/nodes/current'),
  create: (data) => api.post('/nodes', data),
  update: (id, data) => api.put(`/nodes/${id}`, data),
  // 复算建议：只读，不写库。判据不齐时它自己会说"缺哪一样"
  suggest: (id) => api.get(`/nodes/${id}/suggest`),
  // 采纳只回传指纹，那八个值由服务端重算并逐字段比对后落库
  adopt: (id, fingerprint) => api.post(`/nodes/${id}/adopt`, { fingerprint })
}

/**
 * PRD 2.0 三条只读页面：连板天梯 / 首板池 / 主线详情。
 * 明细是本地表，但主线判定与龙头分工可能顺带补拉日 K，超时放宽到 25s 与 snapshot 同档；
 * 取不到时页面自己印空态，不弹红条把"没数据"说成事故。
 */
export const prdApi = {
  tianti: (date) => api.get('/tianti', { params: { date }, timeout: 25000, skipErrorToast: true }),
  shouban: (date) => api.get('/shouban', { params: { date }, timeout: 25000, skipErrorToast: true }),
  mainline: (date) => api.get('/mainline', { params: { date }, timeout: 25000, skipErrorToast: true })
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
  scoreContext: (date) => api.get('/market/score-context', { params: { date }, timeout: 60000 }),
  // 名单本地推，但可能顺带补拉公告，所以和 snapshot 一样放宽
  surveillance: (date) => api.get('/market/surveillance', { params: { date }, timeout: 25000 })
}

export const anchorApi = {
  list: (date) => api.get('/anchors', { params: { date }, timeout: 25000 }),
  spans: (days = 120) => api.get('/anchors/span', { params: { days } }),
  series: (days = 120) => api.get('/anchors/series', { params: { days }, timeout: 25000 }),
  create: (data) => api.post('/anchors', data),
  update: (id, data) => api.put(`/anchors/${id}`, data),
  remove: (id) => api.delete(`/anchors/${id}`)
}

export const stockApi = {
  // 全量名单要翻 60 页上游
  refresh: () => api.post('/stocks/refresh', null, { timeout: 120000 }),
  search: (q) => api.get('/stocks/search', { params: { q } })
}

export const importApi = {
  // 复盘页底部那块只读明细。md 导入本身还有四个端点（POST /records/import、
  // /import/template、/import/export），页面撤了但契约没撤，要用走 curl。
  detail: (date) => api.get('/records/import/detail', { params: { date } })
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
