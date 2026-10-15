import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,        // 固定开发端口
    strictPort: true,  // 端口被占直接失败，避免"悄悄换了端口"的联调困惑
    // 说明：生产环境中，前端静态产物由 Nginx 托管，并把 /api 前缀反向代理到
    // Spring Boot 后端（同域部署），因此浏览器永远只请求同源相对路径 /api/**。
    // 下面的 proxy 仅服务于开发期：让 vite dev server(5173) 把 /api/** 转发到
    // 本机 8081 后端，从而免配 CORS、免改代码即可与真实后端联调。
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
