import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/app/react/' : '/',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/platform/access': 'http://127.0.0.1:8080',
    },
  },
}));
