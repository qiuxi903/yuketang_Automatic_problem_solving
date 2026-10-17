import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/ConsoleLayout.vue'),
      redirect: { name: 'console' },
      children: [
        {
          path: 'console',
          name: 'console',
          component: () => import('@/views/Console.vue'),
          meta: { title: '做题工作台' },
        },
        {
          path: 'community',
          name: 'community',
          component: () => import('@/views/Community.vue'),
          meta: { title: '答案中心' },
        },
        {
          path: 'admin',
          component: () => import('@/layouts/AdminLayout.vue'),
          redirect: { name: 'admin-overview' },
          meta: { requiresAdmin: true },
          children: [
            {
              path: 'overview',
              name: 'admin-overview',
              component: () => import('@/views/admin/AdminOverview.vue'),
              meta: { title: '管理后台 · 概览', requiresAdmin: true },
            },
            {
              path: 'users',
              name: 'admin-users',
              component: () => import('@/views/admin/AdminUsers.vue'),
              meta: { title: '管理后台 · 用户', requiresAdmin: true },
            },
            {
              path: 'tasks',
              name: 'admin-tasks',
              component: () => import('@/views/admin/AdminTasks.vue'),
              meta: { title: '管理后台 · 任务', requiresAdmin: true },
            },
            {
              path: 'library',
              name: 'admin-library',
              component: () => import('@/views/admin/AdminLibrary.vue'),
              meta: { title: '管理后台 · 答案库', requiresAdmin: true },
            },
            {
              path: 'invites',
              name: 'admin-invites',
              component: () => import('@/views/admin/AdminInvites.vue'),
              meta: { title: '管理后台 · 邀请码', requiresAdmin: true },
            },
            {
              path: 'settings',
              name: 'admin-settings',
              component: () => import('@/views/admin/AdminSettings.vue'),
              meta: { title: '管理后台 · 设置', requiresAdmin: true },
            },
          ],
        },
      ],
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'console' },
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()

  if (!to.meta.public && !auth.isLogin) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLogin) {
    return { name: 'console' }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'console' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · 雨课堂自动化做题平台` : '雨课堂自动化做题平台'
})

export default router
