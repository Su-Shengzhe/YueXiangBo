import { createApp } from 'vue'
import 'element-plus/theme-chalk/base.css'
const { default: App } = import.meta.env.MODE === 'business'
  ? await import('./business/BusinessApp.vue')
  : await import('./App.vue')
const app = createApp(App)
if (import.meta.env.MODE === 'business') { const { router } = await import('./business/router'); app.use(router) }
app.mount('#app')
