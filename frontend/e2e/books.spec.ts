import { expect, test } from '@playwright/test'
import { api, bookQuery, closeContexts, createReviewByApi, importBook, reviewCard, searchBooks, signup, user } from './helpers.ts'
import type { BookDetail } from './helpers.ts'

test.afterEach(closeContexts)

// 書籍検索（FN-02）・本のページ（FN-10）・読書ステータス（FN-08）

test('ISBN・「もっと見る」・0 件・直接開いた URL', async ({ browser }) => {
  const page = await signup(browser, user('bs', 'たろう'))

  await test.step('ハイフンつきの ISBN で 1 冊に決まる（TC-02-1）', async () => {
    await searchBooks(page, '978-4-10-101001-4')
    const results = page.getByRole('list', { name: '検索結果' })
    await expect(results.getByRole('listitem')).toHaveCount(1)
    await results.getByRole('button').click()
    await expect(page.getByText('ISBN 9784101010014')).toBeVisible()
  })

  await test.step('20 件ずつ「もっと見る」で足す（TC-02-3）', async () => {
    const q = bookQuery('多数')
    await page.goto(`/books/search?${new URLSearchParams({ q })}`)
    // 直接開くと検索欄に文字が入り、結果が出る（TC-02-11）
    await expect(page.getByRole('searchbox', { name: '本を探す文字' })).toHaveValue(q)
    const rows = page.getByRole('list', { name: '検索結果' }).getByRole('listitem')
    await expect(rows).toHaveCount(20)
    await page.getByRole('button', { name: 'もっと見る' }).click()
    await expect(rows).toHaveCount(40)
  })

  await test.step('0 件のときは M-13', async () => {
    const q = bookQuery('なし')
    await searchBooks(page, q)
    await expect(page.getByText(`「${q}」に一致する本は見つかりませんでした。`)).toBeVisible()
  })

  await test.step('空白だけなら M-12 を出し、探さない（TC-02-2）', async () => {
    await searchBooks(page, '   ')
    await expect(page.getByText('ISBN か書名を入力してください。')).toBeVisible()
  })
})

// TC-02-6・TC-10-5
test('Google Books API が使えないときは M-14 を出し、取り込んだ本のページは開ける', async ({ browser }) => {
  const page = await signup(browser, user('bd', 'たろう'))
  const id = await importBook(page, '使える本')

  await searchBooks(page, bookQuery('障害'))
  await expect(page.getByRole('alert')).toContainText('本の検索がいまできません。')
  await page.goto(`/books/${id}`)
  await expect(page.getByRole('heading', { name: `${bookQuery('使える本')} 1巻` })).toBeVisible()
})

// TC-02-9・TC-10-7・N-17
test('本の情報の HTML の記号は文字のまま出て、スクリプトは動かない', async ({ browser }) => {
  const page = await signup(browser, user('bx', 'たろう'))
  let dialogs = 0
  page.on('dialog', (dialog) => {
    dialogs++
    void dialog.dismiss()
  })
  const id = await importBook(page, '記号')
  await page.goto(`/books/${id}`)
  await expect(page.getByRole('heading', { name: /<script>alert\(1\)<\/script>/ })).toBeVisible()
  await expect(page.getByText('紹介文に a < b & c > d の記号があります。')).toBeVisible()
  expect(dialogs).toBe(0)
})

// TC-02-10・N-18
test('画面の通信に Google Books API も API キーも出てこない', async ({ browser }) => {
  const page = await signup(browser, user('bk', 'たろう'))
  const urls: string[] = []
  page.on('request', (request) => urls.push(request.url()))
  await searchBooks(page, bookQuery('通信'))
  await expect(page.getByRole('list', { name: '検索結果' }).getByRole('listitem')).toHaveCount(3)
  expect(urls.filter((url) => url.includes('googleapis') || url.includes('key='))).toEqual([])
})

// TC-10-1・TC-10-2・TC-10-6
test('平均評価と件数はレビューに合わせて変わり、ない本は M-15', async ({ browser }) => {
  const taro = user('ba', 'たろう')
  const hana = user('bb', 'はなこ')
  const taroPage = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  const id = await importBook(taroPage, '平均')

  await createReviewByApi(taroPage, id, 'たろうの感想', { rating: 4 })
  await createReviewByApi(hanaPage, id, 'はなこの感想', { rating: 5 })
  await taroPage.goto(`/books/${id}`)
  await expect(taroPage.getByText('レビュー 2 件')).toBeVisible()
  await expect(taroPage.getByText('星 4.5（5 段階中）')).toBeAttached()
  await expect(reviewCard(taroPage, hana)).toContainText('はなこの感想')
  expect(await api<BookDetail>(taroPage, `/api/books/${id}`)).toMatchObject({ averageRating: 4.5, reviewCount: 2 })

  await taroPage.goto('/books/999999999')
  await expect(taroPage.getByText('この本は見つかりません。')).toBeVisible()
})

// ST-1〜ST-3・TC-03-4
test('読書ステータスは付ける・変える・外すができ、レビューを書いても「読書中」は変わらない', async ({ browser }) => {
  const page = await signup(browser, user('bt', 'たろう'))
  const id = await importBook(page, 'ステータス')
  const status = () => api<BookDetail>(page, `/api/books/${id}`).then((book) => book.myStatus)
  await page.goto(`/books/${id}`)
  const buttons = page.getByRole('group', { name: '読書ステータス' })

  await buttons.getByRole('button', { name: '読みたい' }).click()
  await expect(buttons.getByRole('button', { name: '読みたい' })).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(status).toBe('WANT')

  await buttons.getByRole('button', { name: '読書中' }).click()
  await expect(buttons.getByRole('button', { name: '読みたい' })).toHaveAttribute('aria-pressed', 'false')
  await expect.poll(status).toBe('READING')

  await createReviewByApi(page, id, '感想')
  expect(await status()).toBe('READING')

  // もう一度押すと外れる。確認は出さず、レビューは残る（ST-3）
  await buttons.getByRole('button', { name: '読書中' }).click()
  await expect.poll(status).toBeNull()
  expect((await api<BookDetail>(page, `/api/books/${id}`)).reviewCount).toBe(1)
})
