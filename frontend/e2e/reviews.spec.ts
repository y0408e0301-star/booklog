import { expect, test } from '@playwright/test'
import { api, closeContexts, createReviewByApi, deleteReview, importBook, photoFile, reviewCard, signup, submitReview, user } from './helpers.ts'
import type { ReviewView } from './helpers.ts'

test.afterEach(closeContexts)

// レビュー（FN-03）・写真（FN-04）

test('星と感想がないと投稿できず、感想の上限を超えると押せない（TC-03-1・TC-03-2）', async ({ browser }) => {
  const page = await signup(browser, user('rv', 'たろう'))
  const id = await importBook(page, '入力')
  await page.goto(`/books/${id}/review`)
  const post = page.getByRole('button', { name: '投稿する' })

  await page.getByLabel('感想（必須）').fill('感想だけ')
  await post.click()
  await expect(page.getByText('星を 1〜5 から選んでください。')).toBeVisible()

  await page.getByRole('radio', { name: '星 3' }).check({ force: true })
  await page.getByLabel('感想（必須）').fill('  \n ')
  await post.click()
  await expect(page.getByText('感想を入力してください。')).toBeVisible()

  await page.getByLabel('感想（必須）').fill('あ'.repeat(2001))
  await expect(page.getByText('-1', { exact: true })).toBeVisible()
  await expect(post).toBeDisabled()
  await page.getByLabel('感想（必須）').fill('あ'.repeat(2000))
  await expect(post).toBeEnabled()
  await post.click()
  await expect(page).toHaveURL(new RegExp(`/books/${id}$`))
})

test('編集すると「（編集済み）」が付き、削除すると M-20 になる（TC-03-5・TC-03-7・TC-03-12）', async ({ browser }) => {
  const taro = user('re', 'たろう')
  const page = await signup(browser, taro)
  const id = await importBook(page, '編集')
  await page.goto(`/books/${id}/review`)
  await submitReview(page, 3, '編集する前')

  await reviewCard(page, taro).getByRole('button', { name: 'メニュー' }).click()
  await page.getByRole('menuitem', { name: '編集' }).click()
  // 何も変えていないと押せない
  await expect(page.getByRole('button', { name: '保存する' })).toBeDisabled()
  await expect(page.getByLabel('感想（必須）')).toHaveValue('編集する前')
  await page.getByLabel('感想（必須）').fill('編集した後')
  await page.getByRole('button', { name: '保存する' }).click()

  const card = reviewCard(page, taro)
  await expect(card.getByText('編集した後')).toBeVisible()
  await expect(card.getByText('（編集済み）')).toBeVisible()

  const { items } = await api<{ items: ReviewView[] }>(page, `/api/books/${id}/reviews`)
  await page.goto(`/reviews/${items[0].id}`)
  await card.getByRole('button', { name: 'メニュー' }).click()
  await page.getByRole('menuitem', { name: '削除' }).click()
  const dialog = page.getByRole('alertdialog', { name: 'レビューを削除' })
  // 「キャンセル」では消えない
  await dialog.getByRole('button', { name: 'キャンセル' }).click()
  await expect(dialog).toBeHidden()
  await card.getByRole('button', { name: 'メニュー' }).click()
  await page.getByRole('menuitem', { name: '削除' }).click()
  await dialog.getByRole('button', { name: '削除' }).click()

  await page.goto(`/reviews/${items[0].id}`)
  await expect(page.getByText('このレビューは削除されました。')).toBeVisible()
  // 本は残り、もう一度書ける
  await page.goto(`/books/${id}`)
  await expect(page.getByRole('link', { name: 'レビューを書く' })).toBeVisible()
})

// TC-03-3
test('同じ本に 2 つ目のレビューは書けない', async ({ browser }) => {
  const page = await signup(browser, user('r2', 'たろう'))
  const id = await importBook(page, '二つ目')
  await createReviewByApi(page, id, '1 つ目')
  await page.goto(`/books/${id}`)
  await expect(page.getByRole('link', { name: 'レビューを編集' })).toBeVisible()
})

// TC-03-8・TC-03-9
test('ネタバレありは感想とコメントを伏せ、開くと出て、読み込み直すと伏せた状態に戻る', async ({ browser }) => {
  const taro = user('rs', 'たろう')
  const hana = user('rh', 'はなこ')
  const taroPage = await signup(browser, taro)
  const id = await importBook(taroPage, 'ネタバレ')
  const review = await createReviewByApi(taroPage, id, '犯人はこの人です', { spoiler: true, rating: 2 })
  await taroPage.close()

  const page = await signup(browser, hana)
  for (const path of [`/books/${id}`, `/users/${taro.username}`, '/?tab=all']) {
    await page.goto(path)
    const card = reviewCard(page, taro)
    await expect(card.getByRole('button', { name: 'ネタバレを表示' }), path).toBeVisible()
    await expect(card.getByText('犯人はこの人です'), path).toHaveCount(0)
    // 星は見える
    await expect(card.getByText('星 2（5 段階中）'), path).toBeAttached()
  }

  await page.goto(`/reviews/${review.id}`)
  await expect(page.getByRole('textbox', { name: 'コメントの本文' })).toHaveCount(0)
  await reviewCard(page, taro).getByRole('button', { name: 'ネタバレを表示' }).click()
  await expect(reviewCard(page, taro).getByText('犯人はこの人です')).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'コメントの本文' })).toBeVisible()

  await page.reload()
  await expect(reviewCard(page, taro).getByRole('button', { name: 'ネタバレを表示' })).toBeVisible()
})

// TC-03-11
test('一覧で 6 行以上の感想は「続きを読む」が出て、G-7 では全部出る', async ({ browser }) => {
  const taro = user('rl', 'たろう')
  const page = await signup(browser, taro)
  const id = await importBook(page, '長い感想')
  const review = await createReviewByApi(page, id, Array.from({ length: 8 }, (_, i) => `${i + 1} 行目`).join('\n'))
  await page.goto(`/books/${id}`)
  await reviewCard(page, taro).getByRole('link', { name: '続きを読む' }).click()
  await expect(page).toHaveURL(new RegExp(`/reviews/${review.id}$`))
  await expect(reviewCard(page, taro).getByRole('link', { name: '続きを読む' })).toHaveCount(0)
  await expect(reviewCard(page, taro)).toContainText('8 行目')
})

// FN-04：PH-1〜PH-5、TC-03-7。S3 に置くので、最後にレビューを消す
test('写真は 4 枚まで添えられ、拡大でき、レビューを消すと一緒に消える', async ({ browser }) => {
  const taro = user('rp', 'たろう')
  const page = await signup(browser, taro)
  const id = await importBook(page, '写真')
  await page.goto(`/books/${id}/review`)
  const input = page.getByLabel('添える写真のファイル')
  const photos = page.getByRole('list', { name: '添える写真' }).getByRole('listitem')

  await input.setInputFiles(['a', 'b', 'c', 'd', 'e'].map((name) => photoFile(`${name}.png`)))
  await expect(page.getByText('写真は 1 つのレビューに 4 枚までです。')).toBeVisible()
  await expect(photos).toHaveCount(4)
  await page.getByRole('button', { name: '写真 4 を外す' }).click()
  await expect(photos).toHaveCount(3)
  await expect(page.getByRole('button', { name: '写真を選ぶ' })).toBeEnabled()
  await submitReview(page, 4, '写真つきの感想')

  const card = reviewCard(page, taro)
  await card.getByRole('button', { name: '写真 2 / 3 を拡大' }).click()
  const dialog = page.getByRole('dialog', { name: '写真の拡大' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: '次の写真' }).click()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()

  const { items } = await api<{ items: ReviewView[] }>(page, `/api/books/${id}/reviews`)
  const url = items[0].photos[0].url
  expect((await page.request.get(url)).status()).toBe(200)
  await deleteReview(page, taro)
  // S3 からも消えた
  await expect.poll(async () => (await page.request.get(url)).status()).toBe(404)
})

test('添えられない写真は M-23', async ({ browser }) => {
  const page = await signup(browser, user('rq', 'たろう'))
  const id = await importBook(page, '写真の条件')
  await page.goto(`/books/${id}/review`)
  await page.getByLabel('添える写真のファイル').setInputFiles({ name: 'a.txt', mimeType: 'text/plain', buffer: Buffer.from('a') })
  await expect(page.getByText('添えられるのは 5MB までの JPEG・PNG・WebP の画像です。')).toBeVisible()
})
