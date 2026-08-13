import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: Number(process.env.VITE_DEV_PORT || 5173),
    proxy: {
      '/auth': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080', changeOrigin: true },
      '/api': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080', changeOrigin: true }
    }
  }
})
