import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler, setVipRequiredHandler } from './api/client'
import { initTheme } from './utils/theme'
import './styles/main.css'

setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.name === 'login') return
  router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

// 付费闸门 402：统一跳付费页并带上未解锁批次，付费页会自动预选该日期
setVipRequiredHandler((batch) => {
  const current = router.currentRoute.value
  if (current.path.startsWith('/vip')) return
  router.push({ path: '/vip', query: batch ? { batch } : undefined })
})

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
