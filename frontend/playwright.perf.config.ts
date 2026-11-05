import { defineConfig } from '@playwright/test'

// 画面の性能テスト（開発計画書 2-2、基本設計書 2.4）。npm run test:e2e（e2e/）とは分け、CI でも動かさない。
// 画面も入れた jar を perf プロファイルで 8080 に起動し、perf/seed.sql のデータを入れてから、npm run perf -- ui で動かす。
// 公開先で測るときは、環境変数 PERF_BASE_URL に CloudFront の URL を入れる
export default defineConfig({
  testDir: './perf',
  // 同じユーザーで続けて測るので、1 つずつ順に動かす
  workers: 1,
  fullyParallel: false,
  // 20 回ずつ測るので長めにする
  timeout: 20 * 60_000,
  reporter: 'list',
  use: {
    // 画面は Vite（5173）ではなく、ビルド済みのものを測る（公開先と同じ形）
    baseURL: process.env.PERF_BASE_URL ?? 'http://localhost:8080',
    // パソコンに入っている Microsoft Edge を使う（回線・CPU を遅くする CDP は Chromium でしか使えない）
    channel: 'msedge',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'off',
  },
  projects: [
    { name: 'pc', use: { viewport: { width: 1280, height: 900 } } },
    // スマートフォン（N-23 の 375px）。CPU を 4 倍遅くする（perf/measure.ts）
    { name: 'mobile', use: { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
  ],
})
