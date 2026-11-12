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
    // 项目在 G: 盘（可移动/非系统盘）上 chokidar 事件监听经常失效，
    // 改用轮询保证源码修改能触发热更（dev 模式必备）
    watch: { usePolling: true, interval: 500 },
    allowedHosts: true,
    port: 25173,
    proxy: {
      '/api': {
        target: `http://localhost:${backendPort}`,
        changeOrigin: true,
        // 透传真实客户端 IP（X-Forwarded-For）：登录日志/限流据此记录来源
        xfwd: true,
      },
      '/ws': {
        target: `ws://localhost:${backendPort}`,
        ws: true,
      },
    },
  },
})
