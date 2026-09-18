import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // listen on all interfaces so the dev server is reachable from outside the dev container
    host: true,
    port: 5173,
    proxy: {
      // the backend runs in the same dev container
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
