import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import pkg from './package.json'

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // Версия системы в справке: из package.json, при выпуске её можно подменить переменной APP_VERSION.
  define: { __APP_VERSION__: JSON.stringify(loadEnv(mode, '.', '').APP_VERSION || pkg.version) },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8080',
      '/idp': 'http://localhost:8080'
    }
  }
}))
