<template>
  <el-container class="layout">
    <el-aside width="200px" class="sidebar">
      <div class="logo">
        <h3>情绪周期</h3>
      </div>
      <el-menu :default-active="activeMenu" router background-color="#1a2332"
        text-color="#8899a6" active-text-color="#3b82f6">
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon>
          <span>D1 仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <el-icon><EditPen /></el-icon>
          <span>D2 每日复盘</span>
        </el-menu-item>
        <el-menu-item index="/market">
          <el-icon><DataLine /></el-icon>
          <span>D3 大盘生态</span>
        </el-menu-item>
        <el-menu-item index="/mainline">
          <el-icon><Aim /></el-icon>
          <span>D4 日内核心</span>
        </el-menu-item>
        <el-menu-item index="/tianti">
          <el-icon><Histogram /></el-icon>
          <span>D5 连板生态</span>
        </el-menu-item>
        <el-menu-item index="/shouban">
          <el-icon><Wallet /></el-icon>
          <span>D6 首板生态</span>
        </el-menu-item>
        <el-menu-item index="/higheco">
          <el-icon><Odometer /></el-icon>
          <span>D7 高位生态</span>
        </el-menu-item>
        <el-menu-item index="/nodes">
          <el-icon><Connection /></el-icon>
          <span>D8 节点追踪</span>
        </el-menu-item>
        <el-menu-item index="/waverider">
          <el-icon><TrendCharts /></el-icon>
          <span>D9 策略选股</span>
        </el-menu-item>
        <el-menu-item index="/positions">
          <el-icon><Tickets /></el-icon>
          <span>D10 持仓与台账</span>
        </el-menu-item>
        <el-menu-item index="/scoring">
          <el-icon><SetUp /></el-icon>
          <span>D11 打分配置</span>
        </el-menu-item>
        <!-- 只对超级管理员露出：注册入口收拢之后，开户/管号就只有这一个入口。
             隐藏只是少个点，真正的判据在后端（角色不符一律 400）。 -->
        <el-menu-item v-if="userStore.isSuperAdmin" index="/admin">
          <el-icon><UserFilled /></el-icon>
          <span>账号管理</span>
        </el-menu-item>
      </el-menu>
      <div class="sidebar-footer">
        <span class="user-name">
          {{ userStore.nickname || userStore.username }}
          <span v-if="userStore.isSuperAdmin" class="role-tag">超管</span>
        </span>
        <el-button text size="small" @click="handleLogout">退出</el-button>
      </div>
    </el-aside>
    <el-main class="main-content">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useScoringStore } from '../stores/scoring'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const scoringStore = useScoringStore()

const activeMenu = computed(() => route.path)

// 生效打分模型的维度/权重：进外壳就拉一次，供仪表盘卡片与复盘页用（读不到则退回本地兜底常量）。
// 顺带刷一次身份：角色被改过或被管理员强制下线时，不用来到下一页才发现菜单不对。
onMounted(() => {
  scoringStore.load()
  userStore.loadMe().catch(() => {})
})

async function handleLogout() {
  await userStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
}
.sidebar {
  background: #1a2332;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #2d3748;
}
.logo {
  padding: 20px;
  text-align: center;
  border-bottom: 1px solid #2d3748;
}
.logo h3 {
  margin: 0;
  color: #e1e8ed;
  font-size: 18px;
}
.sidebar :deep(.el-menu) {
  border-right: none;
  flex: 1;
}
.sidebar :deep(.el-menu-item) {
  font-size: 14px;
}
.sidebar :deep(.el-menu-item.is-active) {
  background: rgba(59, 130, 246, 0.1) !important;
}
.sidebar-footer {
  padding: 16px 20px;
  border-top: 1px solid #2d3748;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.user-name {
  color: #8899a6;
  font-size: 13px;
}
.role-tag {
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 3px;
  border: 1px solid #7a3b3b;
  color: #f78989;
  font-size: 11px;
}
.main-content {
  background: #0f1419;
  padding: 24px;
}
</style>
