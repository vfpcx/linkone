import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { fileURLToPath, URL } from 'node:url'

// uni-app 移动端（H5 + 微信小程序同源码）
// 注意：uni-app 的 vite 插件要求 vite 5.2.8（peer 精确锁定），勿随意升级
export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // workspace 类型包直接引源码（与 admin 同款），免 pnpm install 依赖
      '@cangchu/api-types': fileURLToPath(new URL('../../packages/api-types/src/index.ts', import.meta.url)),
    },
  },
  server: {
    port: 5175,
    strictPort: true,
    host: true,
    proxy: {
      // 与 admin 一致：本地后端联调
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/files': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
