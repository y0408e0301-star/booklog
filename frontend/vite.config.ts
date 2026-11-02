/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // /api へのリクエストは Spring Boot（backend）へ転送する。
    // 画面と API が同じオリジン（localhost:5173）に見えるので、Cookie がそのまま使える
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  // ユニットテスト（npm test）。画面の部品を表示するテストがあるので jsdom で動かす
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],
  },
})
