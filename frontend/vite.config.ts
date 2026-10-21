import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({ mode }) => {
const gateway = `http://127.0.0.1:${loadEnv(mode, '.', '').GATEWAY_PORT || '8080'}`
return {
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy: { '/api': gateway } },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true, proxy: { '/api': gateway } }
}
})
