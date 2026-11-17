import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// In development the web app runs on Vite's port and forwards API calls to the backend.
// In production the backend serves the built files itself, so both share one origin.
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backend,
      '/actuator': backend,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test-setup.ts',
    restoreMocks: true,
    unstubGlobals: true,
  },
})
