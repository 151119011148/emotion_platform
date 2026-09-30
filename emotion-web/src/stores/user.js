import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '../api/modules'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')
  // 角色跟着登录一起落 localStorage：刷新页面时路由守卫要立刻知道能不能进账号管理页，
  // 不能等 /me 回来——那会让「账号管理」菜单先闪一下再消失。
  const role = ref(localStorage.getItem('role') || '')

  const isLoggedIn = computed(() => !!token.value)
  const isSuperAdmin = computed(() => role.value === 'SUPER_ADMIN')

  async function login(form) {
    const res = await authApi.login(form)
    setAuth(res.data)
    return res.data
  }

  /**
   * 进外壳时拉一次当前身份：管理员改了我的角色、或把我强制下线，
   * 不用等下次登录才生效。失败一律静默——真正的掉线由其它请求的 401 统一处理，
   * 这里再弹一次红条等于同一个事故报两遍。
   */
  async function loadMe() {
    const res = await authApi.me()
    const data = res?.data
    if (data) {
      username.value = data.username || username.value
      nickname.value = data.nickname || username.value
      role.value = data.role || 'USER'
      localStorage.setItem('username', username.value)
      localStorage.setItem('nickname', nickname.value)
      localStorage.setItem('role', role.value)
    }
    return data
  }

  function setAuth(data) {
    token.value = data.token
    username.value = data.username || ''
    nickname.value = data.nickname || ''
    role.value = data.role || 'USER'
    localStorage.setItem('token', data.token)
    localStorage.setItem('username', username.value)
    localStorage.setItem('nickname', nickname.value)
    localStorage.setItem('role', role.value)
  }

  /**
   * 退出：先通知后端把令牌版本号 +1（单点登录——这个 token 当场作废），
   * 再清本地。后端那一步失败不影响退出：本地清干净了，浏览器这边就已经登出了。
   */
  async function logout() {
    try {
      await authApi.logout()
    } catch (e) {
      /* 后端不可用 / token 本就失效时也要把本地清掉 */
    }
    clear()
  }

  /** 被 401 拦截器判定掉线时的清场入口：不调后端，避免再打一次必然失败的请求。 */
  function clear() {
    token.value = ''
    username.value = ''
    nickname.value = ''
    role.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
    localStorage.removeItem('role')
  }

  return { token, username, nickname, role, isLoggedIn, isSuperAdmin, login, loadMe, setAuth, logout, clear }
})
