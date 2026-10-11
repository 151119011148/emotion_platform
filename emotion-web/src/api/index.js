import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000
})

/**
 * 写请求回调：后端「引擎现算」类读数（如 /records/score-detail）按库里当前的数算，
 * 任何一次成功的写都可能让它变。与其在十来个页面各记一行「写完记得作废」——漏一处就是
 * 一屏旧分数——不如在唯一的出口上统一通知。目前只有一个订阅方（stores/scoring.js）。
 */
const writeHooks = []
export function onApiWrite(fn) {
  writeHooks.push(fn)
}

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * 一次「掉线清场」的闩。
 *
 * 一个页面开屏就是十几个请求，被顶下线时它们会同时收到 401——不设闩就是叠十几条红条、
 * 十几趟 router.push。1.5 秒内只认第一次，后面的 401 静默失败（各页面的 catch 自己收尾）。
 */
let bouncedAt = 0

api.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      // 后台配置类读取（如 /scoring/effective）失败要静默降级到本地兜底，不弹红条
      if (!response.config?.skipErrorToast) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message))
    }
    const method = (response.config?.method || 'get').toLowerCase()
    // 落库成功的写：现算类读数当场作废。写是低频动作，宁可多失效一次，不冒显示旧数的险。
    if (method !== 'get' && method !== 'head') {
      writeHooks.forEach((fn) => { try { fn(response.config) } catch (e) { /* 回调不许把响应链打断 */ } })
    }
    return res
  },
  error => {
    const status = error.response?.status
    // 只认 401：那是后端唯一的「未登录」出口（SecurityConfig.Json401EntryPoint）——
    // 未登录、被别的设备顶下线、空闲超时都走它。
    // 403 只代表越权与 CORS，不该顺手清 token；业务越权本来走 BizException
    // （HTTP 200 + code=400），压根到不了这个分支。
    if (status === 401 && !error.config?.skipAuthRedirect) {
      const now = Date.now()
      if (now - bouncedAt > 1500) {
        bouncedAt = now
        // 单点登录：账号在别处登录（或被管理员强制下线、或晾过空闲窗口）时也会走到这里——
        // 令牌版本号或活动时间对不上，后端一律按未登录回 401。
        // skipAuthRedirect 留给退出登录自己发的那个请求：它本来就是来作废 token 的，
        // 回 401 属于正常，不该被说成掉线。
        localStorage.removeItem('token')
        localStorage.removeItem('username')
        localStorage.removeItem('nickname')
        localStorage.removeItem('role')
        router.push('/login')
        ElMessage.error('登录已失效，请重新登录')
      }
    } else if (!error.config?.skipErrorToast) {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default api
