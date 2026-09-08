import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 临时 e2e 配置：连 8081 上那个独立实例，跑完就删。
// 必须把 origin 改回 5173：后端 CORS 白名单只认那一个，否则同源 POST 会被判 Invalid CORS request。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.setHeader('origin', 'http://localhost:5173'))
        }
      }
    }
  }
})
