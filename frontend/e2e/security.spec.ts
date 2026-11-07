import { expect, test } from '@playwright/test'
import {
  api, closeContexts, createReviewByApi, importBook, newContext, PASSWORD, reviewCard, send, signup, submitLogin, user, xsrfHeader,
} from './helpers.ts'
import type { Profile, ReviewView } from './helpers.ts'

test.afterEach(closeContexts)

// 権限と安全（要件定義書のセキュリティの要件）のうち、画面を通して確かめられるもの。
// API の層ごとの細かい確認は、API のテスト（*ApiTests）で行っている

// N-12
test('ログインしていないと、どの画面も G-1 に移り、API も 401 を返す', async ({ browser }) => {
  const context = await newContext(browser)
  const page = await context.newPage()
  for (const path of ['/', '/books/search?q=a', '/books/1', '/books/1/review', '/reviews/1', '/users/someone',
    '/users/someone/followers', '/settings/profile']) {
    await page.goto(path)
    await expect(page, path).toHaveURL(/\/login$/)
  }
  for (const path of ['/api/timeline?tab=all', '/api/books/1', '/api/reviews/1', '/api/users/someone',
    '/api/books/search?q=a', '/api/auth/me']) {
    expect((await page.request.get(path)).status(), path).toBe(401)
  }
})

// TC-03-6・N-13
// BR-7
test('他人のレビュー・コメントは、画面に操作が出ず、API を直接呼んでも変えられない', async ({ browser }) => {
  const taro = user('st', 'たろう')
  const hana = user('sh', 'はなこ')
  const taroPage = await signup(browser, taro)
  const id = await importBook(taroPage, '権限')
  const review = await createReviewByApi(taroPage, id, 'たろうの感想')
  const { id: commentId } = await send<{ id: number }>(taroPage, 'POST', `/api/reviews/${review.id}/comments`,
    { data: { body: 'たろうのコメント' } })

  const page = await signup(browser, hana)
  await page.goto(`/reviews/${review.id}`)
  await expect(reviewCard(page, taro)).toContainText('たろうの感想')
  // 他人のレビューには「…」メニューがなく、他人のコメントには「削除」がない
  await expect(reviewCard(page, taro).getByRole('button', { name: 'メニュー' })).toHaveCount(0)
  const comment = page.getByRole('listitem', { name: `${taro.displayName}のコメント` })
  await expect(comment).toContainText('たろうのコメント')
  await expect(comment.getByRole('button', { name: '削除' })).toHaveCount(0)

  // 画面を通さずに API を呼んでも、サーバー側で断る
  const headers = await xsrfHeader(page)
  const patch = await page.request.patch(`/api/reviews/${review.id}`, {
    headers, multipart: { rating: '1', body: '書き換え', spoiler: 'false' },
  })
  expect(patch.status()).toBe(403)
  expect((await page.request.delete(`/api/reviews/${review.id}`, { headers })).status()).toBe(403)
  expect((await page.request.delete(`/api/comments/${commentId}`, { headers })).status()).toBe(403)
  expect(await api<ReviewView>(page, `/api/reviews/${review.id}`)).toMatchObject({ body: 'たろうの感想', commentCount: 1 })

  // プロフィールの変更は、ログインした人のものにしか届かない（FN-11 8 章）
  await send(page, 'PATCH', '/api/me/profile', { data: { displayName: hana.displayName, bio: '書き換えた' } })
  expect((await api<Profile>(page, `/api/users/${taro.username}`)).bio).toBe('')
})

test('CSRF のトークンがない書き込みは断る', async ({ browser }) => {
  const page = await signup(browser, user('sc', 'たろう'))
  const res = await page.request.patch('/api/me/profile', { data: { displayName: 'x', bio: '' } })
  expect(res.status()).toBe(403)
})

// TC-03-10
test('感想・コメントの HTML は文字のまま出て、スクリプトは動かない', async ({ browser }) => {
  const jiro = user('sx', 'じろう')
  const page = await signup(browser, jiro)
  let dialogs = 0
  page.on('dialog', (dialog) => {
    dialogs++
    void dialog.dismiss()
  })
  const body = '<img src=x onerror="alert(1)"><script>alert(2)</script><b>太字</b>'
  const id = await importBook(page, 'HTML')
  const review = await createReviewByApi(page, id, body)
  await send(page, 'POST', `/api/reviews/${review.id}/comments`, { data: { body } })

  for (const path of [`/books/${id}`, `/users/${jiro.username}`, `/reviews/${review.id}`]) {
    await page.goto(path)
    const card = reviewCard(page, jiro)
    await expect(card.getByText(body, { exact: true })).toBeVisible()
    await expect(card.locator('script, b')).toHaveCount(0)
  }
  await expect(page.getByRole('listitem', { name: `${jiro.displayName}のコメント` }).locator('script, b')).toHaveCount(0)
  expect(dialogs).toBe(0)
})

// BR-8
// N-15
test('5 回続けてパスワードを間違えると、正しいパスワードでもログインできない', async ({ browser }) => {
  const saburo = user('sl', 'さぶろう')
  const page = await signup(browser, saburo)
  await page.getByRole('button', { name: 'ログアウト' }).click()
  await expect(page).toHaveURL(/\/login$/)

  const alert = page.getByRole('alert')
  for (let i = 1; i <= 4; i++) {
    await submitLogin(page, saburo.username, 'wrong-pass1')
    await expect(alert).toHaveText('ユーザー ID かパスワードが違います。')
  }
  await submitLogin(page, saburo.username, 'wrong-pass1')
  await expect(alert).toHaveText('ログインに続けて失敗したため、15 分間ログインできません。')
  await submitLogin(page, saburo.username, PASSWORD)
  await expect(alert).toHaveText('ログインに続けて失敗したため、15 分間ログインできません。')
  await expect(page).toHaveURL(/\/login$/)
})
