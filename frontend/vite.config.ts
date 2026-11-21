import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The Spring Boot backend runs on 8080; the dev server forwards /api to it.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
