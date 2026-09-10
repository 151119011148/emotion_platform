import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '../api/modules'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')
  const nickname = ref(localStorage.getItem('nickname') || '')

  const isLoggedIn = computed(() => !!token.value)

  async function login(form) {
    const res = await authApi.login(form)
    setAuth(res.data)
    return res.data
  }

  async function register(form) {
    const res = await authApi.register(form)
    setAuth(res.data)
    return res.data
  }

  function setAuth(data) {
    token.value = data.token
    username.value = data.username
    nickname.value = data.nickname
    localStorage.setItem('token', data.token)
    localStorage.setItem('username', data.username)
    localStorage.setItem('nickname', data.nickname)
  }

  function logout() {
    token.value = ''
    username.value = ''
    nickname.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('nickname')
  }

  return { token, username, nickname, isLoggedIn, login, register, logout }
})
