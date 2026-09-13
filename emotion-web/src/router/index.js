import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue')
  },
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
        path: 'scoring',
        name: 'Scoring',
        component: () => import('../views/ScoringAdminView.vue')
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
