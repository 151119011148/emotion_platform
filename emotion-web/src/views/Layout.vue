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
          <span>仪表盘</span>
        </el-menu-item>
        <el-menu-item index="/review">
          <el-icon><EditPen /></el-icon>
          <span>每日复盘</span>
        </el-menu-item>
        <el-menu-item index="/market">
          <el-icon><DataLine /></el-icon>
          <span>大盘生态</span>
        </el-menu-item>
        <el-menu-item index="/tianti">
          <el-icon><Histogram /></el-icon>
          <span>连板生态</span>
        </el-menu-item>
        <el-menu-item index="/shouban">
          <el-icon><Wallet /></el-icon>
          <span>首板生态</span>
        </el-menu-item>
        <el-menu-item index="/mainline">
          <el-icon><Aim /></el-icon>
          <span>主线生态</span>
        </el-menu-item>
        <el-menu-item index="/surveillance">
          <el-icon><Warning /></el-icon>
          <span>异动监管</span>
        </el-menu-item>
        <el-menu-item index="/nodes">
          <el-icon><Connection /></el-icon>
          <span>节点理论</span>
        </el-menu-item>
        <el-menu-item index="/history">
          <el-icon><Calendar /></el-icon>
          <span>历史回看</span>
        </el-menu-item>
        <el-menu-item index="/themes">
          <el-icon><TrendCharts /></el-icon>
          <span>主线龙头</span>
        </el-menu-item>
        <el-menu-item index="/scoring">
          <el-icon><SetUp /></el-icon>
          <span>打分配置</span>
        </el-menu-item>
      </el-menu>
      <div class="sidebar-footer">
        <span class="user-name">{{ userStore.nickname || userStore.username }}</span>
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
onMounted(() => scoringStore.load())

function handleLogout() {
  userStore.logout()
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
.main-content {
  background: #0f1419;
  padding: 24px;
}
</style>
