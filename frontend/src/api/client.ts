import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'

// 由 main.ts 注入跳转逻辑，避免 client -> router -> store -> api 的循环依赖
let redirectToLogin: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  redirectToLogin = handler
}

// 拦截器已把响应体解包，所以业务侧拿到的直接是契约里的出参对象
const client = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

client.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const status = error.response?.status
    const url: string = error.config?.url || ''
    const data = error.response?.data as { message?: string; detail?: string } | undefined
    // 后端无全局异常处理器时 ResponseStatusException 的文案在 ProblemDetail.detail 里
    const serverMsg = data?.message || data?.detail

    if (status === 401 && (url.startsWith('/auth/login') || url.startsWith('/auth/register'))) {
      // 登录/注册的 401 是「账号密码不对/账号不存在」，不是会话过期，不能误导
      ElMessage.error(serverMsg || '用户名或密码错误；没有账号就先注册')
    } else if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      warnExpiredOnce()
      redirectToLogin?.()
    } else {
      ElMessage.error(serverMsg || error.message || '请求失败，请稍后重试')
    }

    return Promise.reject(error)
  },
)

// 多个并发请求同时 401 时只弹一条过期提示（截图里连弹两条就是这个原因）
let lastExpiredTipAt = 0
function warnExpiredOnce() {
  const now = Date.now()
  if (now - lastExpiredTipAt > 3000) {
    lastExpiredTipAt = now
    ElMessage.error('登录已过期，请重新登录')
  }
}

export default client
