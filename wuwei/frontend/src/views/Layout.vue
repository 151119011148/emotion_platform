<template>
  <el-container style="height: 100%">
    <el-aside width="200px" style="background: #001529">
      <div style="color: #fff; font-size: 17px; font-weight: 700; padding: 20px 16px; letter-spacing: 1px">
        五维情绪导航
      </div>
      <el-menu
        :default-active="$route.path"
        router
        background-color="#001529"
        text-color="rgba(255,255,255,0.65)"
        active-text-color="#fff"
        style="border-right: none"
      >
        <el-menu-item index="/dashboard"><el-icon><Odometer /></el-icon>P1 仪表盘</el-menu-item>
        <el-menu-item index="/tianti"><el-icon><Trophy /></el-icon>P2 连板天梯</el-menu-item>
        <el-menu-item index="/shouban"><el-icon><Flag /></el-icon>P3 首板池</el-menu-item>
        <el-menu-item index="/monitor"><el-icon><Warning /></el-icon>P4 监管池</el-menu-item>
        <el-menu-item index="/node"><el-icon><Guide /></el-icon>P5 节点演变</el-menu-item>
        <el-menu-item index="/mainline"><el-icon><TrendCharts /></el-icon>P6 主线详情</el-menu-item>
        <el-menu-item index="/history"><el-icon><Clock /></el-icon>P7 历史回顾</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header height="56px" style="background: #fff; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #e4e7ed">
        <span class="muted" style="font-size: 13px">{{ today }}</span>
        <el-dropdown @command="onCommand">
          <span style="cursor: pointer">{{ userStore.nickname || userStore.username }}<el-icon><ArrowDown /></el-icon></span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main style="padding: 16px">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'long' })

function onCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    router.push('/login')
  }
}
</script>
