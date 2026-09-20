import process from 'node:process'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Local-development default for the backend API. Only used by `npm run dev` when
// VITE_API_BASE_URL is not set (see client/.env.example); a production build must set it.
const DEV_API_BASE_URL = 'http://localhost:8080/api/v1.0'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  let apiBaseUrl = env.VITE_API_BASE_URL
  if (!apiBaseUrl) {
    if (mode === 'production') {
      throw new Error('VITE_API_BASE_URL must be set for a production build (see client/.env.example)')
    }
    apiBaseUrl = DEV_API_BASE_URL
  }
  return {
    plugins: [react(), tailwindcss()],
    define: {
      'import.meta.env.VITE_API_BASE_URL': JSON.stringify(apiBaseUrl),
    },
  }
})
