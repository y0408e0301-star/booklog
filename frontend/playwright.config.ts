import { defineConfig, devices } from '@playwright/test'
import type { Project } from '@playwright/test'

// 画面を操作して、本物の API と DB で動くかを確かめる E2E テスト（開発計画書 2-1）。
// サーバー（DB 5432・API 8080・画面 5173）は起動しておく（README「画面を通したテスト」）。Google Books API の
// 偽物（9100）だけは、ここで起動する。CI では jar（8080）に画面も入れて動かすので、環境変数 E2E_BASE_URL で行き先を変える。
// テストで作るユーザーは ID が「e2e_」で始まり、始める前と終わった後に DB から消す（e2e/cleanup.ts）

// スマートフォンの幅で流すテスト。主な流れと、すべての画面の確認だけ
const MOBILE_TESTS = ['main-flow.spec.ts', 'screens.spec.ts']

// ふだん（CI も）は、パソコンに入っている Microsoft Edge の pc と mobile だけを流す
const projects: Project[] = [
  { name: 'pc', use: { channel: 'msedge', viewport: { width: 1280, height: 900 } } },
  // N-23 の 375px
  {
    name: 'mobile',
    use: { channel: 'msedge', viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true },
    testMatch: MOBILE_TESTS,
  },
]

// E2E_BROWSERS=all のときは、対応環境の代わりのブラウザでも流す（開発計画書 2-6）。
// Firefox と WebKit（Safari の代わり）は Playwright のもの（npx playwright install firefox webkit）。
// project の名前の頭文字は、テストのユーザー ID に入るので重ならないようにする（e2e/helpers.ts の runId）
if (process.env.E2E_BROWSERS === 'all') {
  projects.push(
    { name: 'chrome', use: { channel: 'chrome', viewport: { width: 1280, height: 900 } } },
    { name: 'firefox', use: { browserName: 'firefox', viewport: { width: 1280, height: 900 } } },
    { name: 'webkit', use: { browserName: 'webkit', viewport: { width: 1280, height: 900 } } },
    // iOS の Safari の代わり（WebKit で iPhone の画面の大きさ・User-Agent・タッチ）
    { name: 'iphone', use: { ...devices['iPhone 15'] }, testMatch: MOBILE_TESTS },
    // Android の Chrome の代わり（パソコンの Chrome で Pixel の画面の大きさ・User-Agent・タッチ）
    { name: 'android', use: { ...devices['Pixel 7'], channel: 'chrome' }, testMatch: MOBILE_TESTS },
  )
}

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/cleanup.ts',
  globalTeardown: './e2e/cleanup.ts',
  // 同じ DB を使うので 1 つずつ順に動かす
  workers: 1,
  fullyParallel: false,
  // 対応環境の代わりのブラウザで流すときは 1 回だけやり直す。ふだん（CI も）はやり直さない
  retries: process.env.E2E_BROWSERS === 'all' ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['github']] : 'list',
  // API の Google Books の接続先（環境変数 GOOGLE_BOOKS_BASE_URL）を、ここに向けておく
  webServer: {
    command: 'node e2e/fake-google-books.ts',
    url: 'http://localhost:9100/health',
    reuseExistingServer: true,
  },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    locale: 'ja-JP',
    timezoneId: 'Asia/Tokyo',
    trace: 'retain-on-failure',
  },
  projects,
})
