import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],

  // sockjs-client expects Node's "global"
  define: {
    global: 'globalThis'
  },

  // The backend's CORS whitelist expects this port. strictPort makes Vite stop with a clear
  // error if 5173 is already taken, instead of silently switching to another port.
  server: {
    port: 5173,
    strictPort: true
  }
})
