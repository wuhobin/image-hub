import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'

const envDir = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  build: { manifest: true },
  server: { port: 5173, strictPort: true, allowedHosts: ['tb4ea67a.natappfree.cc'], proxy: { '/api': { target: loadEnv(mode, envDir, 'IMAGE_HUB_').IMAGE_HUB_API_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } } },
}))
