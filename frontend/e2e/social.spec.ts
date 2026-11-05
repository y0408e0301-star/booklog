import { expect, test } from '@playwright/test'
import { api, closeContexts, createReviewByApi, importBook, reviewCard, send, signup, user, xsrfHeader } from './helpers.ts'
import type { Profile, ReviewView } from './helpers.ts'

test.afterEach(closeContexts)

// 参考になった（FN-05）・コメント（FN-06）・フォロー（FN-07）・タイムライン（FN-09）

test('参考になったは付け外しができ、自分のレビューには押せない', async ({ browser }) => {
  const taro = user('nh', 'たろう')
  const taroPage = await signup(browser, taro)
  const hanaPage = await signup(browser, user('ni', 'はなこ'))
  const id = await importBook(taroPage, '参考')
  const review = await createReviewByApi(taroPage, id, '感想')

  await hanaPage.goto(`/reviews/${review.id}`)
  const button = reviewCard(hanaPage, taro).getByRole('button', { name: /参考になった/ })
  const count = async () => (await api<ReviewView>(hanaPage, `/api/reviews/${review.id}`)).helpfulCount
  await button.click()
  await expect(button).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(count).toBe(1)
  await button.click()
  await expect(button).toHaveAttribute('aria-pressed', 'false')
  await expect.poll(count).toBe(0)

  await taroPage.goto(`/reviews/${review.id}`)
  await expect(reviewCard(taroPage, taro).getByLabel('参考になった 0 件')).toBeVisible()
  await expect(reviewCard(taroPage, taro).getByRole('button', { name: /参考になった/ })).toHaveCount(0)
})

test('コメントは自分のものだけ消せ、コメント数も合う', async ({ browser }) => {
  const taro = user('nc', 'たろう')
  const hana = user('nd', 'はなこ')
  const taroPage = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  const id = await importBook(taroPage, 'コメント')
  const review = await createReviewByApi(taroPage, id, '感想')
  await send(taroPage, 'POST', `/api/reviews/${review.id}/comments`, { data: { body: 'たろうのコメント' } })

  await hanaPage.goto(`/reviews/${review.id}`)
  const write = hanaPage.getByRole('button', { name: 'コメントする' })
  await expect(write).toBeDisabled()
  await hanaPage.getByRole('textbox', { name: 'コメントの本文' }).fill('はなこのコメント')
  await write.click()
  const mine = hanaPage.getByRole('listitem', { name: `${hana.displayName}のコメント` })
  await expect(mine).toContainText('はなこのコメント')
  await expect(reviewCard(hanaPage, taro).getByLabel('コメント 2 件')).toBeVisible()
  await expect(hanaPage.getByRole('listitem', { name: `${taro.displayName}のコメント` }).getByRole('button', { name: '削除' })).toHaveCount(0)

  await mine.getByRole('button', { name: '削除' }).click()
  await hanaPage.getByRole('alertdialog', { name: 'コメントを削除' }).getByRole('button', { name: '削除' }).click()
  await expect(mine).toHaveCount(0)
  await expect(reviewCard(hanaPage, taro).getByLabel('コメント 1 件')).toBeVisible()
})

test('フォロー・解除と、フォロー中・フォロワーの一覧（G-10）', async ({ browser }) => {
  const taro = user('nf', 'たろう')
  const hana = user('ng', 'はなこ')
  const taroPage = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)

  await hanaPage.goto(`/users/${taro.username}`)
  await hanaPage.getByRole('button', { name: 'フォローする' }).click()
  await hanaPage.getByRole('link', { name: '1 フォロワー' }).click()
  await expect(hanaPage).toHaveURL(new RegExp(`/users/${taro.username}/followers$`))
  // 見出しは表示名。自分の行にはフォローボタンがない
  await expect(hanaPage.getByRole('heading', { name: taro.displayName })).toBeVisible()
  const row = hanaPage.getByRole('listitem', { name: hana.displayName })
  await expect(row).toBeVisible()
  await expect(row.getByRole('button')).toHaveCount(0)

  // たろうからはフォローを返せる
  await taroPage.goto(`/users/${taro.username}/followers`)
  await taroPage.getByRole('listitem', { name: hana.displayName }).getByRole('button', { name: 'フォローする' }).click()
  await taroPage.getByRole('link', { name: 'フォロー中' }).click()
  await expect(taroPage.getByRole('listitem', { name: hana.displayName })).toBeVisible()

  // 解除すると数が戻る
  await hanaPage.goto(`/users/${taro.username}`)
  await hanaPage.getByRole('button', { name: /^フォロー中/ }).click()
  await expect(hanaPage.getByRole('link', { name: '0 フォロワー' })).toBeVisible()
  await expect.poll(() => api<Profile>(hanaPage, `/api/users/${taro.username}`)).toMatchObject({ followedByMe: false, followerCount: 0 })

  // 自分はフォローできない（TC-07-3）
  const res = await taroPage.request.put(`/api/users/${taro.username}/follow`, { headers: await xsrfHeader(taroPage) })
  expect(res.status()).toBe(400)
})

// T-1〜T-3
test('タイムラインの「フォロー中」は自分とフォローしている人だけで、タブは覚えている。続きも読める', async ({ browser }) => {
  const taro = user('nt', 'たろう')
  const hana = user('nu', 'はなこ')
  const jiro = user('nv', 'じろう')
  const taroPage = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  const jiroPage = await signup(browser, jiro)
  await createReviewByApi(hanaPage, await importBook(hanaPage, 'はなこの本'), 'はなこの感想')
  await createReviewByApi(jiroPage, await importBook(jiroPage, 'じろうの本'), 'じろうの感想')
  await createReviewByApi(taroPage, await importBook(taroPage, 'たろうの本'), 'たろうの感想')
  await send(taroPage, 'PUT', `/api/users/${hana.username}/follow`)

  await taroPage.goto('/?tab=following')
  await expect(reviewCard(taroPage, hana)).toContainText('はなこの感想')
  await expect(reviewCard(taroPage, taro)).toContainText('たろうの感想')
  await expect(reviewCard(taroPage, jiro)).toHaveCount(0)

  await taroPage.getByRole('tab', { name: 'すべて' }).click()
  await expect(reviewCard(taroPage, jiro)).toContainText('じろうの感想')
  await taroPage.goto('/')
  await expect(taroPage.getByRole('tab', { name: 'すべて' })).toHaveAttribute('aria-selected', 'true')

  // 21 件あると、一番下まで見たときに続きを読む（T-2）
  for (let i = 0; i < 20; i++) {
    await createReviewByApi(jiroPage, await importBook(jiroPage, `続き${i}`), `続きの感想 ${i}`)
  }
  await taroPage.reload()
  const cards = reviewCard(taroPage, jiro)
  await expect(cards).toHaveCount(20)
  await cards.last().scrollIntoViewIfNeeded()
  await taroPage.mouse.wheel(0, 100000)
  await expect(cards).toHaveCount(21)
})
