import { expect, test } from '@playwright/test'
import type { BrowserContext, Cookie } from '@playwright/test'
import { closeContexts, importBook, reviewCard, signup, submitReview, user, xsrfHeader } from './helpers.ts'

test.afterEach(closeContexts)

// ログインの続き（FN-01 A-8）。JWT（access_token）は 15 分で Cookie ごと消えるので、
// 15 分待つ代わりに Cookie を消して、リフレッシュトークン（refresh_token）で取り直せることを確かめる

const cookie = async (context: BrowserContext, name: string): Promise<Cookie | undefined> =>
  (await context.cookies()).find((c) => c.name === name)

// TC-01-10
test('JWT が切れても、リフレッシュトークンで取り直して使い続けられる', async ({ browser }) => {
  const taro = user('ar', 'たろう')
  const page = await signup(browser, taro)
  const context = page.context()
  const id = await importBook(page, '取り直し')

  await context.clearCookies({ name: 'access_token' })
  await page.goto(`/books/${id}/review`)
  await expect(page.getByLabel('感想（必須）')).toBeVisible()
  // 書き込みもできる（取り直しの後に CSRF のトークンも使える）
  await context.clearCookies({ name: 'access_token' })
  await submitReview(page, 4, '取り直した後のレビュー')
  await expect(reviewCard(page, taro).getByText('取り直した後のレビュー')).toBeVisible()
  expect(await cookie(context, 'access_token')).toBeDefined()

  // リフレッシュトークンも消えたら、G-1 に戻る
  await context.clearCookies({ name: 'access_token' })
  await context.clearCookies({ name: 'refresh_token' })
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
})

// TC-01-5・A-8
test('ブラウザを閉じて開き直してもログインしたままで、ログアウトすると G-1 になる', async ({ browser }) => {
  const taro = user('ax', 'たろう')
  const page = await signup(browser, taro)
  // ブラウザを閉じると消える Cookie（expires が -1）ではない
  const refresh = await cookie(page.context(), 'refresh_token')
  expect(refresh!.expires).toBeGreaterThan(Date.now() / 1000)

  const state = await page.context().storageState()
  await page.context().close()
  const { baseURL, viewport, locale, timezoneId, isMobile, hasTouch, userAgent, deviceScaleFactor } = test.info().project.use
  const reopened = await browser.newContext({
    baseURL, viewport, locale, timezoneId, isMobile, hasTouch, userAgent, deviceScaleFactor, storageState: state,
  })
  try {
    const again = await reopened.newPage()
    await again.goto('/')
    await expect(again.getByRole('link', { name: '自分のプロフィール' })).toBeVisible()

    await again.getByRole('button', { name: 'ログアウト' }).click()
    await expect(again).toHaveURL(/\/login$/)
    await again.goto('/')
    await expect(again).toHaveURL(/\/login$/)
  } finally {
    await reopened.close()
  }
})

test('ログアウトした後は、前のリフレッシュトークンを使えない', async ({ browser }) => {
  const page = await signup(browser, user('ao', 'はなこ'))
  const context = page.context()
  const refresh = await cookie(context, 'refresh_token')
  expect(refresh).toBeDefined()

  await page.getByRole('button', { name: 'ログアウト' }).click()
  await expect(page).toHaveURL(/\/login$/)

  // 盗まれた Cookie を後から使われた場合と同じ
  await context.addCookies([refresh!])
  const res = await page.request.post('/api/auth/refresh', { headers: await xsrfHeader(page) })
  expect(res.status()).toBe(401)
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
})

// TC-01-11
test('一度使ったリフレッシュトークンをもう一度使うと、その人のトークンがすべて無効になる', async ({ browser }) => {
  const page = await signup(browser, user('aw', 'じろう'))
  const context = page.context()
  const first = await cookie(context, 'refresh_token')

  // 取り直すと、リフレッシュトークンも新しくなる（データ設計書 BR-9）
  await context.clearCookies({ name: 'access_token' })
  await page.goto('/')
  await expect(page.getByRole('link', { name: '自分のプロフィール' })).toBeVisible()
  expect((await cookie(context, 'refresh_token'))?.value).not.toBe(first?.value)

  // 古いもの（盗まれたもの）を別のブラウザで使うと断られ、本人の新しいトークンも無効になる。
  // 画面を開くと画面自身が取り直しを試みて競合するので、API だけを呼ぶ（GET で XSRF-TOKEN の Cookie を受け取ってから送る）
  const thief = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  await thief.addCookies([first!])
  await thief.request.get('/api/auth/me')
  const xsrf = (await thief.cookies()).find((c) => c.name === 'XSRF-TOKEN')
  expect(xsrf).toBeDefined()
  const stolen = await thief.request.post('/api/auth/refresh', { headers: { 'X-XSRF-TOKEN': decodeURIComponent(xsrf!.value) } })
  expect(stolen.status()).toBe(401)
  await thief.close()

  await context.clearCookies({ name: 'access_token' })
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
})

test('通信が切れたらお知らせ欄に M-31 を出し、戻ったら消す', async ({ browser }) => {
  const page = await signup(browser, user('of', 'じろう'))
  const context = page.context()
  const notice = page.getByRole('banner').getByRole('alert')

  await context.setOffline(true)
  await expect(notice).toHaveText('インターネットに接続されていません。接続を確認してください。')
  await context.setOffline(false)
  await expect(notice).toBeHidden()
})
