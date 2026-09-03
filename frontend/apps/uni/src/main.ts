import { createSSRApp } from 'vue'
import App from './App.vue'

// uni-app 规定：必须导出 createApp（H5/小程序共用入口）
export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
