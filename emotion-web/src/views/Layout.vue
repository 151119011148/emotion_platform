<template>
  <el-container class="layout" :class="{ 'is-narrow': isNarrow }">
    <!-- 窄屏档（手机竖屏）。200px 侧栏在 390 屏上会把正文吃到只剩约 142px
         （390 − 侧栏 200 − .main-content 左右内边距 48），任何一页都读不了。
         所以整条侧栏换成顶栏＋一条横向滑动的页签：先保证 12 个页面「能进、能点、能读结论」，
         各页内容在这一步一行都不改。
         这里用 el-header 而不是裸 <header>：el-container 是按子节点里有没有
         el-header / el-footer 来决定改成纵向排列的，裸标签它认不出来，容器会留在 horizontal。 -->
    <el-header v-if="isNarrow" class="m-topbar" height="auto">
      <div class="m-top">
        <h3 class="m-logo">情绪周期</h3>
        <span class="m-user">
          {{ userStore.nickname || userStore.username }}
          <span v-if="userStore.isSuperAdmin" class="role-tag">超管</span>
        </span>
        <el-button text size="small" @click="handleLogout">退出</el-button>
      </div>
      <nav class="m-tabs" ref="tabsRef">
        <router-link v-for="m in menus" :key="m.index" :to="m.index"
          :class="['m-tab', { active: m.index === activeMenu }]">{{ m.label }}</router-link>
      </nav>
    </el-header>

    <el-aside v-else width="200px" class="sidebar">
      <div class="logo">
        <h3>情绪周期</h3>
      </div>
      <el-menu :default-active="activeMenu" router background-color="#1a2332"
        text-color="#8899a6" active-text-color="#3b82f6">
        <el-menu-item v-for="m in menus" :key="m.index" :index="m.index">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import { useScoringStore } from '../stores/scoring'
import { authApi } from '../api/modules'
import { useNarrow } from '../utils/narrowScreen'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const scoringStore = useScoringStore()
const { isNarrow } = useNarrow()

const activeMenu = computed(() => route.path)
const tabsRef = ref(null)

/**
 * 全站导航的唯一一份名单：桌面侧栏与窄屏页签都从它渲染。
 *
 * <p>以前只有模板里那 12 个 `el-menu-item`，加页面只改一处；现在两条渲染分支共用一份，
 * 漏掉任何一边都会出现「电脑上有点不到、手机上看不见」的页——所以把它们并成一个数组。
 * 图标按**全局注册名**给（`main.js` 把 `@element-plus/icons-vue` 整个注册了），
 * 窄屏页签不画图标，只留字。
 *
 * <p>`adminOnly` 沿用原口径：这两个管理页只对超级管理员露出——打分配置改的是全站生效的模型，
 * 账号管理是开户/管号。隐藏只是少个点，真正的判据在后端（角色不符一律 400）。
 */
const MENUS = [
  { index: '/dashboard', icon: 'DataAnalysis', label: 'D1 仪表盘' },
  { index: '/review', icon: 'EditPen', label: 'D2 每日复盘' },
  { index: '/market', icon: 'DataLine', label: 'D3 大盘生态' },
  { index: '/mainline', icon: 'Aim', label: 'D4 日内核心' },
  { index: '/tianti', icon: 'Histogram', label: 'D5 连板生态' },
  { index: '/shouban', icon: 'Wallet', label: 'D6 首板生态' },
  { index: '/higheco', icon: 'Odometer', label: 'D7 高位生态' },
  { index: '/nodes', icon: 'Connection', label: 'D8 节点追踪' },
  { index: '/waverider', icon: 'TrendCharts', label: 'D9 策略选股' },
  { index: '/positions', icon: 'Tickets', label: 'D10 持仓与台账' },
  { index: '/scoring', icon: 'SetUp', label: 'D11 打分配置', adminOnly: true },
  { index: '/admin', icon: 'UserFilled', label: '账号管理', adminOnly: true }
]

const menus = computed(() => MENUS.filter(m => !m.adminOnly || userStore.isSuperAdmin))

/**
 * 心跳间隔。被别的设备顶下线、或晾过了后端那个空闲窗口（session.idle-timeout-minutes）之后，
 * 标签页最迟 60 秒自己弹回登录页——在此之前它要等用户下一次点击才发现，
 * 中间那一屏看着正常的数据，读的是已经作废的旧身份。
 */
const PING_EVERY_MS = 60000
let pingTimer = null

function ping() {
  // 隐藏页直接跳过：Chrome 会把后台定时器节流到约一分钟一次甚至冻住，
  // 攒着发也没意义——切回前台时下面那个监听会立刻补一次。
  if (document.hidden) return
  // 失败不在这里处理：401 由 api 拦截器统一清场跳登录，这里只管把请求发出去。
  authApi.ping().catch(() => {})
}

function onVisibility() {
  if (!document.hidden) ping()
}

/**
 * 窄屏那排页签总共 12 个、约 1100px 宽，而屏幕只有 390px：不把当前页滚进视野，
 * 手机上打开 D8 就会看到一排不认识的页签、找不到自己在哪。
 * `block: 'nearest'` 是关键——不写它浏览器会顺手把整页也竖着滚一下，进页面就跳到底部。
 */
function scrollActiveTabIntoView() {
  if (!isNarrow.value || !tabsRef.value) return
  const el = tabsRef.value.querySelector('.m-tab.active')
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ inline: 'center', block: 'nearest' })
  }
}

watch([() => route.path, isNarrow], scrollActiveTabIntoView, { flush: 'post' })

// 生效打分模型的维度/权重：进外壳就拉一次，供仪表盘卡片与复盘页用（读不到则退回本地兜底常量）。
// 顺带刷一次身份：角色被改过或被管理员强制下线时，不用来到下一页才发现菜单不对。
onMounted(() => {
  scoringStore.load()
  userStore.loadMe().catch(() => {})
  pingTimer = setInterval(ping, PING_EVERY_MS)
  document.addEventListener('visibilitychange', onVisibility)
  scrollActiveTabIntoView()
})

onUnmounted(() => {
  clearInterval(pingTimer)
  document.removeEventListener('visibilitychange', onVisibility)
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

/* ===== 窄屏档 ===== */
/* 顶栏吸顶：正文比一屏长得多，滚下去之后还能横向切页，不然手机上每换一个页
   都得先回顶部。z-index 要压过 el-table 的固定列（Element 给它的是 2，实测最高到 3）。 */
.m-topbar {
  position: sticky;
  top: 0;
  z-index: 50;
  padding: 0;
  background: #1a2332;
  border-bottom: 1px solid #2d3748;
}
.m-top {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 6px;
}
.m-logo {
  margin: 0;
  flex: 1;
  color: #e1e8ed;
  font-size: 16px;
}
.m-user {
  color: #8899a6;
  font-size: 12.5px;
}
.m-tabs {
  display: flex;
  gap: 6px;
  padding: 0 12px 10px;
  overflow-x: auto;
  /* 手机上横向拖是这一排的正当用法：12 个页签约 1100px，屏只有 390px。
     滚动条本身藏掉——iOS Safari 上留着会占一行高度，而它能滚这件事由阴影渐变暗示。 */
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
.m-tabs::-webkit-scrollbar {
  display: none;
}
.m-tab {
  flex: none;
  padding: 7px 12px;
  border-radius: 16px;
  border: 1px solid #2d3748;
  background: #0f1419;
  color: #8899a6;
  font-size: 13px;
  line-height: 1.4;
  white-space: nowrap;
  text-decoration: none;
}
.m-tab.active {
  color: #3b82f6;
  border-color: rgba(59, 130, 246, 0.45);
  background: rgba(59, 130, 246, 0.12);
}
/* 正文内边距 24 → 12：390 屏上每省 1px 都是真的。
   双类（.is-narrow .main-content）提特异性，单类会和上面那条 .main-content 打平。 */
.layout.is-narrow .main-content {
  padding: 12px;
}
</style>
