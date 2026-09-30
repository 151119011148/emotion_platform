import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const api = axios.create({
  baseURL: '/api',
  timeout: 10000
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
    return res
  },
  error => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      // skipAuthRedirect：退出登录自己发的那个请求。它本来就是把 token 作废的，
      // 若返回 401（token 其实早就失效了）再走一遍重定向+红条，等于把一次正常退出说成掉线。
      if (!error.config?.skipAuthRedirect) {
        // 单点登录：账号在别处登录（或被管理员强制下线）时也会走到这里——
        // 旧 token 版本号对不上，后端一律按未登录回 401。
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
