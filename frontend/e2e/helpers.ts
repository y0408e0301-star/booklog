import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext, Page } from '@playwright/test'

// E2E のテストで共通に使う部品。ユーザーを作る・ログインする・API を直接呼ぶ・ヘッダーで本を探す

export type User = { username: string, displayName: string }

export const PASSWORD = 'e2e-pass1'

// 1×1 の PNG（写真のテストで使う）
export const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

export const photoFile = (name: string) => ({ name, mimeType: 'image/png', buffer: PNG })

// 前の実行の消し残しと重ならないように、時刻を付ける
const run = Date.now().toString(36).slice(-6)

// この実行とこの project だけを表す文字（project の名前の頭文字）。ユーザー ID と表示名・本を探す文字に入れる
export const runId = () => `${run}${test.info().project.name[0]}`

// ユーザー ID は 15 文字まで（「e2e_」4 文字 ＋ runId 7 文字 ＋ suffix 4 文字まで）。suffix はファイルをまたいで重ならないようにする
export const user = (suffix: string, name: string): User =>
  ({ username: `e2e_${runId()}${suffix}`, displayName: `E2E ${name} ${runId()}${suffix}` })

// テストの中で開いたブラウザ。テストが終わったら closeContexts で閉じる
const opened = new Set<BrowserContext>()

// project の設定（baseURL・画面の幅・端末のエミュレーションなど）で、Cookie を分けた新しいブラウザを開く
export async function newContext(browser: Browser): Promise<BrowserContext> {
  const { baseURL, viewport, locale, timezoneId, isMobile, hasTouch, userAgent, deviceScaleFactor } = test.info().project.use
  const context = await browser.newContext({ baseURL, viewport, locale, timezoneId, isMobile, hasTouch, userAgent, deviceScaleFactor })
  opened.add(context)
  return context
}

// テストで開いたブラウザを閉じる。各ファイルの test.afterEach で呼ぶ
export async function closeContexts(): Promise<void> {
  await Promise.all([...opened].map((context) => context.close()))
  opened.clear()
}

// 新しいブラウザで G-2 から登録し、ログインした状態のページを返す
export async function signup(browser: Browser, { username, displayName }: User): Promise<Page> {
  const page = await (await newContext(browser)).newPage()
  await page.goto('/signup')
  await page.getByLabel('ユーザー ID').fill(username)
  await page.getByLabel('表示名').fill(displayName)
  await page.getByLabel('パスワード', { exact: true }).fill(PASSWORD)
  await page.getByLabel('パスワード（確認）').fill(PASSWORD)
  await page.getByRole('button', { name: '登録する' }).click()
  await expect(page.getByRole('link', { name: '自分のプロフィール' })).toBeVisible()
  return page
}

// G-1 で ID とパスワードを入れてログインのボタンを押す。結果は呼んだ側で確かめる
export async function submitLogin(page: Page, username: string, password: string): Promise<void> {
  await page.getByLabel('ユーザー ID').fill(username)
  await page.getByLabel('パスワード').fill(password)
  await page.getByRole('button', { name: 'ログイン' }).click()
}

// API を呼ぶ（そのページのログインの Cookie を使う。読むだけなので CSRF のトークンは要らない）
export async function api<T>(page: Page, path: string): Promise<T> {
  const res = await page.request.get(path)
  expect(res.ok(), `${path} → ${res.status()}`).toBe(true)
  return (await res.json()) as T
}

// GET 以外の API を画面の外から呼ぶときの CSRF のヘッダー（機能定義書 3.2）。
// XSRF-TOKEN の Cookie は API の応答で届くので、ページを開いた直後などでまだなければ、先に GET で受け取る
export async function xsrfHeader(page: Page): Promise<Record<string, string>> {
  const find = async () => (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')
  let token = await find()
  if (!token) {
    await page.request.get('/api/auth/me')
    token = await find()
  }
  expect(token, 'XSRF-TOKEN の Cookie がありません').toBeDefined()
  return { 'X-XSRF-TOKEN': decodeURIComponent(token!.value) }
}

type Multipart = Record<string, string | { name: string, mimeType: string, buffer: Buffer }>

// GET 以外の API を画面の外から呼び、成功したことを確かめて応答の本文を返す（データを用意するとき）
export async function send<T>(page: Page, method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string,
  options: { data?: unknown, multipart?: Multipart } = {}): Promise<T> {
  const res = await page.request.fetch(path, { method, headers: await xsrfHeader(page), ...options })
  expect(res.ok(), `${method} ${path} → ${res.status()} ${await res.text()}`).toBe(true)
  return (res.status() === 204 ? undefined : await res.json()) as T
}

export type ReviewView = {
  id: number
  body: string
  rating: number
  spoiler: boolean
  helpfulCount: number
  commentCount: number
  helpfulByMe: boolean
  editedAt: string | null
  photos: { id: number, url: string }[]
}

export type Profile = {
  displayName: string
  bio: string
  reviewCount: number
  followingCount: number
  followerCount: number
  bookshelfCounts: { WANT: number, READING: number, READ: number }
  followedByMe: boolean
}

export type BookDetail = {
  id: number
  title: string
  averageRating: number | null
  reviewCount: number
  myStatus: string | null
  myReviewId: number | null
}

// この実行だけの本を探す文字（Google Books API の偽物は、文字ごとに決まった本を返す）
export const bookQuery = (label: string) => `e2e ${runId()} ${label}`

// 画面を通さずに本を取り込み、本の ID を返す（G-4 を確かめないテストで）
export async function importBook(page: Page, label: string): Promise<number> {
  const q = new URLSearchParams({ q: bookQuery(label) })
  const found = await api<{ items: { googleBooksId: string }[] }>(page, `/api/books/search?${q}`)
  const { id } = await send<{ id: number }>(page, 'POST', '/api/books', { data: { googleBooksId: found.items[0].googleBooksId } })
  return id
}

// 画面を通さずにレビューを書く（一覧の確かめなど、数をそろえたいとき）
export const createReviewByApi = (page: Page, bookId: number, body: string,
  options: { rating?: number, spoiler?: boolean, photo?: Multipart[string] } = {}): Promise<ReviewView> =>
  send<ReviewView>(page, 'POST', `/api/books/${bookId}/reviews`, {
    multipart: {
      rating: String(options.rating ?? 4),
      body,
      spoiler: String(options.spoiler ?? false),
      ...(options.photo ? { photos: options.photo } : {}),
    },
  })

export const reviewCard = (page: Page, author: User) => page.getByRole('article', { name: `${author.displayName}のレビュー` })

// ヘッダーの検索欄で本を探す。600px 未満では、先に虫めがねのボタンで検索欄を開く（画面設計書 2.6）
export async function searchBooks(page: Page, q: string): Promise<void> {
  const toggle = page.getByRole('button', { name: '本を探す欄を開く' })
  if (await toggle.isVisible()) await toggle.click()
  const search = page.getByRole('searchbox', { name: '本を探す文字' })
  await search.fill(q)
  await search.press('Enter')
}

// G-6 で星と感想を入れて送る
export async function submitReview(page: Page, rating: number, body: string): Promise<void> {
  await page.getByRole('radio', { name: `星 ${rating}` }).check({ force: true })
  await page.getByLabel('感想（必須）').fill(body)
  await page.getByRole('button', { name: '投稿する' }).click()
}

// レビューの「…」メニューから「削除」を選び、確認ダイアログ（G-12）で削除する
export async function deleteReview(page: Page, author: User): Promise<void> {
  await reviewCard(page, author).getByRole('button', { name: 'メニュー' }).click()
  await page.getByRole('menuitem', { name: '削除' }).click()
  await page.getByRole('alertdialog', { name: 'レビューを削除' }).getByRole('button', { name: '削除' }).click()
  await expect(reviewCard(page, author)).toHaveCount(0)
}
