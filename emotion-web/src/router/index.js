import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  // /register 已下线：注册入口收拢到「账号管理」页，由超级管理员开户。
  // 后端 /api/auth/register 仍保留（要求超管身份），但不再有前端路由。
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/dashboard' },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue')
      },
      {
        path: 'review',
        name: 'Review',
        component: () => import('../views/ReviewView.vue')
      },
      {
        path: 'market',
        name: 'Market',
        component: () => import('../views/MarketView.vue')
      },
      {
        path: 'tianti',
        name: 'Tianti',
        component: () => import('../views/TiantiView.vue')
      },
      {
        path: 'shouban',
        name: 'Shouban',
        component: () => import('../views/ShoubanView.vue')
      },
      {
        path: 'mainline',
        name: 'Mainline',
        component: () => import('../views/MainlineView.vue')
      },
      {
        path: 'higheco',
        name: 'HighEco',
        component: () => import('../views/HighEcoView.vue')
      },
      {
        path: 'nodes',
        name: 'Nodes',
        component: () => import('../views/NodeView.vue')
      },
      {
        path: 'positions',
        name: 'Positions',
        component: () => import('../views/PositionsView.vue')
      },
      {
        path: 'scoring',
        name: 'Scoring',
        component: () => import('../views/ScoringAdminView.vue')
      },
      {
        // 路由本身不校验角色：后端会挡，前端这里也拦一道只是为了让地址栏直接敲
        // /admin 的普通用户看到「无权限」而不是一个永远空着的表格
        path: 'admin',
        name: 'Admin',
        component: () => import('../views/AdminView.vue')
      },
      {
        path: 'waverider',
        name: 'WaveRider',
        component: () => import('../views/WaveRiderView.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth) {
    const userStore = useUserStore()
    if (!userStore.isLoggedIn) {
      return { name: 'Login' }
    }
  }
})

export default router
