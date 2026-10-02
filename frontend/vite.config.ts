/// <reference types="vitest/config" />
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
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // East of UTC on purpose: a "today" computed with toISOString() (the UTC date) fails localDate.test.ts and E1.
    env: { TZ: 'Europe/Warsaw' },
    unstubGlobals: true, // undo vi.stubGlobal('fetch', …) after each test
    restoreMocks: true, // undo vi.spyOn(window, 'confirm') after each test
  },
})
