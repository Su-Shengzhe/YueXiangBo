import { createApp } from 'vue'
import 'element-plus/theme-chalk/base.css'
const { default: App } = import.meta.env.MODE === 'business'
  ? await import('./business/BusinessApp.vue')
  : await import('./App.vue')
createApp(App).mount('#app')
