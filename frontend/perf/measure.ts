import { mkdirSync, writeFileSync } from 'node:fs'
import type { Browser, BrowserContext, Page, TestInfo } from '@playwright/test'

// 画面の性能テスト（ui.spec.ts）で使う部品。回線・CPU の条件、ログイン、測った値の集計と書き出し

// 結果を書き出す場所。frontend/perf/cli.ts が集めて表にする
export const UI_RESULTS_DIR = new URL('../../perf/results/ui/', import.meta.url)

// 1 つの項目を測る回数。20 回なら P95 は遅い方から 2 番目（基本設計書 2.4）
export const RUNS = Number(process.env.PERF_RUNS ?? 20)

// k6 で負荷をかけながら測っているか（npm run perf -- mixed）
export const UNDER_LOAD = process.env.PERF_UNDER_LOAD === '1'

// perf/seed.sql で入れたユーザーと本。k6 は 1〜30 番を使うので、画面の測定は 41〜44 番を使う
export const PASSWORD = 'perf-pass1'
export const username = (n: number) => `perf_${String(n).padStart(4, '0')}`
export const bookId = (n: number) => 900000000 + n

// 測定の条件（要件定義書 11.1）：下り 10Mbps・上り 30Mbps・遅延 20ms。
// スマートフォン（project の mobile）は、CPU も 4 倍遅くする。Chromium（Edge）でしか使えない
export async function throttle(page: Page, info: TestInfo): Promise<void> {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Network.enable')
  await cdp.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 20,
    downloadThroughput: 10_000_000 / 8,
    uploadThroughput: 30_000_000 / 8,
  })
  if (info.project.name === 'mobile') {
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 })
  }
}

// project の設定（baseURL・画面の幅など）で、Cookie もキャッシュもない新しいブラウザを開く
export async function newContext(browser: Browser, info: TestInfo): Promise<BrowserContext> {
  const { baseURL, viewport, locale, timezoneId, isMobile, hasTouch } = info.project.use
  return browser.newContext({ baseURL, viewport, locale, timezoneId, isMobile, hasTouch })
}

// G-1 からログインする。ボタンを押すところは呼んだ側で行う（N-1 は押してからの時間を測るため）
export async function fillLogin(page: Page, n: number): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('ユーザー ID').fill(username(n))
  await page.getByLabel('パスワード').fill(PASSWORD)
}

// GET 以外の API を画面の外から呼ぶときの CSRF のヘッダー（機能定義書 3.2）
export async function xsrfHeader(context: BrowserContext): Promise<Record<string, string>> {
  const token = (await context.cookies()).find((c) => c.name === 'XSRF-TOKEN')
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token.value) } : {}
}

// 小さい順に並べて、p % の位置の値（nearest-rank 法）
export function percentile(samples: number[], p: number): number {
  const sorted = samples.toSorted((a, b) => a - b)
  return sorted[Math.max(0, Math.ceil((p / 100) * sorted.length) - 1)]
}

export type Result = {
  // 要件の ID（N-1 など）。目安として記録するだけの項目は「参考」
  id: string
  label: string
  project: string
  underLoad: boolean
  unit: string
  samples: number[]
  p50: number
  p95: number
  max: number
  // 目標（P95 で判定）。参考の項目にはない
  target?: number
  ok?: boolean
}

// 測った値を集計して、perf/results/ui/ に JSON で書き出す。合否は呼んだ側が expect.soft で確かめる
export function record(info: TestInfo, key: string, id: string, label: string, samples: number[],
  { target, unit = 'ms' }: { target?: number, unit?: string } = {}): Result {
  const p95 = percentile(samples, 95)
  const result: Result = {
    id, label, project: info.project.name, underLoad: UNDER_LOAD, unit, samples,
    p50: percentile(samples, 50), p95, max: Math.max(...samples),
    target, ok: target === undefined ? undefined : p95 <= target,
  }
  mkdirSync(UI_RESULTS_DIR, { recursive: true })
  const file = `${info.project.name}-${key}${UNDER_LOAD ? '-load' : ''}.json`
  writeFileSync(new URL(file, UI_RESULTS_DIR), JSON.stringify(result, null, 2))
  return result
}
