import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端端口可由启动器注入（8080 被 Hyper-V 保留区间吞掉时会自动漂移）；
// 手动 npm run dev 时未设置环境变量则保持默认 8080
const backendPort = Number(process.env.YKT_BACKEND_PORT || 28080)

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 内网穿透对外开放：监听全部网卡 + 放行隧道域名 Host
    host: true,
    allowedHosts: true,
    port: 25173,
    proxy: {
      '/api': {
        target: `http://localhost:${backendPort}`,
        changeOrigin: true,
      },
      '/ws': {
        target: `ws://localhost:${backendPort}`,
        ws: true,
      },
    },
  },
})
