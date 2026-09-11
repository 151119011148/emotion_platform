import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 这台 Windows 机器 localhost 解析在 IPv4/IPv6 间漂移（曾报 EAI_FAIL localhost），
    // 默认会让 dev server 只监听到 ::1，浏览器走 127.0.0.1 时静态资源走缓存、XHR 却 Network Error。
    // 钉死 IPv4 回环：127.0.0.1 与 localhost（Happy-Eyeballs 回退 IPv4）都稳。
    host: '127.0.0.1',
    port: 5173,
    // 端口被占时直接报错，不允许悄悄跳到 5174/5176——多实例并存会让人访问到旧代码
    strictPort: true,
    proxy: {
      '/api': {
        // 同样钉死 IPv4：Node 侧解析 localhost 也会 EAI_FAIL，直连 127.0.0.1 最稳
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
