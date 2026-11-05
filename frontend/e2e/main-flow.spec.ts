import { expect, test } from '@playwright/test'
import { api, bookQuery, closeContexts, PASSWORD, reviewCard, searchBooks, signup, submitLogin, submitReview, user } from './helpers.ts'
import type { BookDetail, Profile, ReviewView } from './helpers.ts'

test.afterEach(closeContexts)

// 主な流れ（登録 → 本を探す → レビュー → フォロー → 参考になった・コメント）を、本物の API と DB につないで確かめる。
// 2 人のユーザーをそれぞれ別のブラウザで操作し、操作の後は API（＝ DB）の中身と、相手の画面にも出ることを確かめる

test('登録 → 本を探す → レビュー → フォロー → 参考になった・コメントが、相手の画面と DB に反映される', async ({ browser }) => {
  const taro = user('t', 'たろう')
  const hana = user('h', 'はなこ')
  const body = `はじめてのレビューです ${taro.username}`
  const q = bookQuery('主な流れ')

  const taroPage = await signup(browser, taro)
  let bookId = 0
  let reviewId = 0

  await test.step('たろうが本を探してレビューを書く', async () => {
    // まだ誰もフォローしていないので「フォロー中」タブは M-25
    await expect(taroPage.getByText('まだ誰もフォローしていません。')).toBeVisible()
    await searchBooks(taroPage, q)
    await expect(taroPage.getByRole('heading', { name: `「${q}」の検索結果` })).toBeVisible()
    const results = taroPage.getByRole('list', { name: '検索結果' })
    await expect(results.getByRole('listitem')).toHaveCount(3)
    await results.getByRole('button', { name: `${q} 1巻` }).click()

    await expect(taroPage.getByRole('heading', { name: `${q} 1巻` })).toBeVisible()
    await expect(taroPage.getByLabel('平均評価なし')).toBeVisible()
    bookId = Number(/\/books\/(\d+)$/.exec(taroPage.url())?.[1])
    await taroPage.getByRole('link', { name: 'レビューを書く' }).click()
    await submitReview(taroPage, 5, body)

    await expect(taroPage).toHaveURL(new RegExp(`/books/${bookId}$`))
    await expect(reviewCard(taroPage, taro).getByText(body)).toBeVisible()
    await expect(taroPage.getByRole('link', { name: 'レビューを編集' })).toBeVisible()
    // 本棚になければ「読了」で入る（RV-5）
    await expect(taroPage.getByRole('button', { name: '読了' })).toHaveAttribute('aria-pressed', 'true')
    expect(await api<BookDetail>(taroPage, `/api/books/${bookId}`)).toMatchObject({ averageRating: 5, reviewCount: 1, myStatus: 'READ' })
    const reviews = await api<{ items: ReviewView[] }>(taroPage, `/api/users/${taro.username}/reviews`)
    expect(reviews.items.map((r) => r.body)).toEqual([body])
    reviewId = reviews.items[0].id
  })

  const hanaPage = await signup(browser, hana)

  await test.step('はなこがタイムラインからたろうのプロフィールを開いてフォローする', async () => {
    await hanaPage.goto('/?tab=all')
    await reviewCard(hanaPage, taro).getByRole('link', { name: new RegExp(`@${taro.username}`) }).click()
    await expect(hanaPage.getByRole('heading', { name: taro.displayName })).toBeVisible()
    await hanaPage.getByRole('button', { name: 'フォローする' }).click()
    await expect(hanaPage.getByRole('link', { name: '1 フォロワー' })).toBeVisible()
    await expect.poll(() => api<Profile>(hanaPage, `/api/users/${taro.username}`))
      .toMatchObject({ followedByMe: true, followerCount: 1 })

    // フォローしたので「フォロー中」タブに出る
    await hanaPage.goto('/?tab=following')
    await expect(reviewCard(hanaPage, taro).getByText(body)).toBeVisible()
  })

  await test.step('はなこが参考になったを付けてコメントする', async () => {
    await reviewCard(hanaPage, taro).getByRole('button', { name: /参考になった/ }).click()
    await expect.poll(async () => (await api<ReviewView>(hanaPage, `/api/reviews/${reviewId}`)).helpfulCount).toBe(1)

    await reviewCard(hanaPage, taro).getByRole('link', { name: 'コメント 0 件' }).click()
    await expect(hanaPage).toHaveURL(new RegExp(`/reviews/${reviewId}$`))
    await hanaPage.getByRole('textbox', { name: 'コメントの本文' }).fill('参考になりました')
    await hanaPage.getByRole('button', { name: 'コメントする' }).click()
    await expect(hanaPage.getByRole('listitem', { name: `${hana.displayName}のコメント` })).toContainText('参考になりました')
  })

  await test.step('たろうの画面にも、参考になった・コメント・フォロワーが出る', async () => {
    await taroPage.goto(`/reviews/${reviewId}`)
    const card = reviewCard(taroPage, taro)
    await expect(card.getByLabel('参考になった 1 件')).toBeVisible()
    await expect(card.getByLabel('コメント 1 件')).toBeVisible()
    await expect(taroPage.getByRole('listitem', { name: `${hana.displayName}のコメント` })).toContainText('参考になりました')
    await taroPage.goto(`/users/${taro.username}`)
    await expect(taroPage.getByRole('link', { name: '1 フォロワー' })).toBeVisible()
  })

  await test.step('ログアウトしてログインし直しても続きが見られる', async () => {
    await taroPage.getByRole('button', { name: 'ログアウト' }).click()
    await expect(taroPage).toHaveURL(/\/login$/)
    await submitLogin(taroPage, taro.username, PASSWORD)
    await expect(taroPage.getByRole('link', { name: '自分のプロフィール' })).toBeVisible()
    await taroPage.goto(`/books/${bookId}`)
    await expect(reviewCard(taroPage, taro).getByText(body)).toBeVisible()
  })
})
