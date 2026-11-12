import { execSync } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// —— 端口强占：25173 被平台残留的 vite/野进程占用时直接结束占用者，绝不漂移换端口 ——
// 换端口会让内网穿透隧道、手机收藏夹、桌面快捷方式全部失效
try {
  const out = execSync('netstat -ano | findstr ":25173" | findstr "LISTENING"', { shell: 'cmd.exe' }).toString()
  const pids = [...new Set(out.split('\n').map((l) => l.trim().split(/\s+/).pop() ?? '').filter((p) => /^\d+$/.test(p)))]
  for (const pid of pids) {
    try {
      execSync('taskkill /PID ' + pid + ' /F /T', { shell: 'cmd.exe' })
      console.log('[port] 已结束占用 25173 的残留进程 PID ' + pid)
    } catch {
      // 进程可能恰好自行退出
    }
  }
} catch {
  // findstr 无匹配时非零退出 = 端口空闲
}

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
    // 端口被占时不漂移：顶层强杀已处理常见占用；真杀不掉宁可启动失败也不换口
    strictPort: true,
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
