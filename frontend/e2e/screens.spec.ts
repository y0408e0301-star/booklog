import { AxeBuilder } from '@axe-core/playwright'
import { expect, test } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import {
  bookQuery, closeContexts, createReviewByApi, importBook, newContext, photoFile, PNG, reviewCard, runId, send, signup, user,
} from './helpers.ts'

test.afterEach(closeContexts)

// すべての画面（G-1〜G-13）を、データを入れた状態で開いて確かめる。pc（1280px）と mobile（375px）の両方で動かす。
// (1) アクセシビリティ：axe-core で WCAG 2.1 の A・AA に違反がない（コントラスト比を含む）
// (2) 画面の幅：横にスクロールしない（N-23）。長い表示名と、区切りのない長い感想も入れておく

const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']

async function check(page: Page, ready: Locator): Promise<void> {
  await expect(ready).toBeVisible()
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG).analyze()
  const found = violations.map((v) => `${v.id}（${v.help}）: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
  expect.soft(found, 'アクセシビリティの違反').toEqual([])
  const { scrollWidth, clientWidth } = await page.evaluate(() =>
    ({ scrollWidth: document.documentElement.scrollWidth, clientWidth: document.documentElement.clientWidth }))
  expect.soft(scrollWidth, '横にスクロールしない').toBeLessThanOrEqual(clientWidth)
}

// N-21・N-23
test('すべての画面に、アクセシビリティの違反と横のスクロールがない', async ({ browser }) => {
  // 表示名は 30 文字まで（「E2E 」と runId・suffix を足して 30 文字）
  const taro = user('ga', 'とてもながいなまえのユーザーです')
  const hana = user('gb', 'はなこ')
  const page = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  // 表紙の URL（偽物の本に付けたもの）には、ここで画像を返す
  await page.context().route('https://covers.e2e.test/**', (route) => route.fulfill({ contentType: 'image/png', body: PNG }))

  const coverQuery = bookQuery('表紙')
  const bookId = await importBook(page, '表紙')
  await createReviewByApi(page, await importBook(page, '長い感想'), 'あ'.repeat(2000))
  const review = await createReviewByApi(page, bookId, `写真つきの感想 ${runId()}`, { photo: photoFile('a.png') })
  const spoilerBook = await importBook(hanaPage, 'ネタバレ')
  await createReviewByApi(hanaPage, spoilerBook, 'ネタバレの感想', { spoiler: true })
  await send(page, 'PATCH', '/api/me/profile', { data: { displayName: taro.displayName, bio: `${'自己紹介'.repeat(40)}` } })
  await send(hanaPage, 'PUT', `/api/users/${taro.username}/follow`)
  await send(hanaPage, 'PUT', `/api/reviews/${review.id}/helpful`)
  await send(hanaPage, 'POST', `/api/reviews/${review.id}/comments`, { data: { body: '私も読みました' } })

  await test.step('G-1 ログイン・G-2 ユーザー登録', async () => {
    const guest = await (await newContext(browser)).newPage()
    await guest.goto('/login')
    await check(guest, guest.getByRole('button', { name: 'ログイン' }))
    await guest.goto('/signup')
    await check(guest, guest.getByRole('button', { name: '登録する' }))
    await guest.context().close()
  })

  await test.step('G-3 タイムライン（ネタバレの枠を含む）', async () => {
    await page.goto('/?tab=all')
    await check(page, reviewCard(page, hana).getByRole('button', { name: 'ネタバレを表示' }))
  })

  await test.step('G-4 書籍検索の結果', async () => {
    await page.goto(`/books/search?${new URLSearchParams({ q: coverQuery })}`)
    await check(page, page.getByRole('img', { name: `${coverQuery} 1巻の表紙` }))
  })

  await test.step('G-5 本のページ', async () => {
    await page.goto(`/books/${bookId}`)
    await check(page, reviewCard(page, taro))
  })

  await test.step('G-11 写真の拡大', async () => {
    await reviewCard(page, taro).getByRole('button', { name: '写真 1 / 1 を拡大' }).click()
    await check(page, page.getByRole('dialog', { name: '写真の拡大' }))
    await page.keyboard.press('Escape')
  })

  await test.step('G-6 レビューの投稿・編集（エラーを出した状態も）', async () => {
    await page.goto(`/books/${await importBook(page, '書く本')}/review`)
    await page.getByRole('button', { name: '投稿する' }).click()
    await check(page, page.getByText('星を 1〜5 から選んでください。'))
  })

  await test.step('G-7 レビューの詳細', async () => {
    await page.goto(`/reviews/${review.id}`)
    await check(page, page.getByRole('listitem', { name: `${hana.displayName}のコメント` }))
  })

  await test.step('G-12 確認ダイアログ', async () => {
    await reviewCard(page, taro).getByRole('button', { name: 'メニュー' }).click()
    await page.getByRole('menuitem', { name: '削除' }).click()
    await check(page, page.getByRole('alertdialog', { name: 'レビューを削除' }))
    await page.keyboard.press('Escape')
  })

  await test.step('G-8 プロフィール（レビュー・本棚）', async () => {
    await page.goto(`/users/${taro.username}`)
    await check(page, reviewCard(page, taro).first())
    await page.goto(`/users/${taro.username}?tab=bookshelf&shelf=read`)
    await check(page, page.getByRole('img', { name: `${coverQuery} 1巻の表紙` }))
  })

  await test.step('G-9 プロフィールの編集', async () => {
    await page.goto('/settings/profile')
    await check(page, page.getByRole('button', { name: '退会する' }))
  })

  await test.step('G-13 退会の確認ダイアログ（M-10 を出した状態）', async () => {
    await page.getByRole('button', { name: '退会する' }).click()
    const dialog = page.getByRole('alertdialog', { name: '退会' })
    await dialog.getByLabel('パスワード').fill('wrong-pass1')
    await dialog.getByRole('button', { name: '退会する' }).click()
    await check(page, dialog.getByText('パスワードが違います。'))
    await page.keyboard.press('Escape')
  })

  await test.step('G-10 フォロー中・フォロワーの一覧', async () => {
    await page.goto(`/users/${taro.username}/followers`)
    await check(page, page.getByRole('listitem', { name: hana.displayName }))
    await page.goto(`/users/${taro.username}/following`)
    await check(page, page.getByText('まだ誰もフォローしていません。'))
  })

  // S3 の写真を消す
  await send(page, 'DELETE', `/api/reviews/${review.id}`)
})
