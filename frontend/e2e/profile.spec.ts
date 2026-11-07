import { expect, test } from '@playwright/test'
import {
  api, closeContexts, createReviewByApi, importBook, newContext, PASSWORD, photoFile, reviewCard, send, signup, submitLogin, user,
} from './helpers.ts'
import type { Profile, ReviewView } from './helpers.ts'

test.afterEach(closeContexts)

// プロフィール（FN-11）・本棚（FN-08 ST-4〜ST-6）・退会（FN-01 A-10〜A-13）

// TC-11-1・ST-4〜ST-6
test('G-8 は件数とレビュー・本棚のタブを出し、本棚は分類ごとに読める', async ({ browser }) => {
  const taro = user('pa', 'たろう')
  const page = await signup(browser, taro)
  const read = await importBook(page, '読了の本')
  const reading = await importBook(page, '読書中の本')
  await createReviewByApi(page, read, 'たろうの感想', { rating: 5 })
  await send(page, 'PUT', `/api/books/${reading}/status`, { data: { status: 'READING' } })

  await page.goto(`/users/${taro.username}`)
  await expect(page.getByRole('region', { name: 'プロフィール' })).toContainText('1 レビュー')
  await expect(reviewCard(page, taro)).toContainText('たろうの感想')

  await page.getByRole('link', { name: '本棚' }).click()
  await expect(page).toHaveURL(/tab=bookshelf&shelf=reading/)
  await expect(page.getByRole('link', { name: '読書中 1' })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('link', { name: /読書中の本 1巻/ })).toHaveAttribute('href', `/books/${reading}`)

  await page.getByRole('link', { name: '読了 1' }).click()
  const book = page.getByRole('link', { name: /読了の本 1巻/ })
  await expect(book).toContainText('E2E 著者')
  await expect(book).toContainText('星 5（5 段階中）')

  await page.getByRole('link', { name: '読みたい 0' }).click()
  await expect(page.getByText('この本棚にはまだ本がありません。')).toBeVisible()
  // 読み直しても同じタブ
  await page.reload()
  await expect(page.getByRole('link', { name: '読みたい 0' })).toHaveAttribute('aria-current', 'page')
})

// TC-11-2・TC-11-3・TC-11-4・TC-11-6
test('G-9 で表示名と自己紹介を変えると、ヘッダー・カードにも反映される', async ({ browser }) => {
  const taro = user('pb', 'たろう')
  const hana = user('pc', 'はなこ')
  const page = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  await createReviewByApi(page, await importBook(page, '編集の本'), '感想')

  // ほかの人にはフォローボタン、自分には「プロフィールを編集」
  await hanaPage.goto(`/users/${taro.username}`)
  await expect(hanaPage.getByRole('button', { name: 'フォローする' })).toBeVisible()
  await expect(hanaPage.getByRole('link', { name: 'プロフィールを編集' })).toHaveCount(0)

  await page.goto(`/users/${taro.username}`)
  await page.getByRole('link', { name: 'プロフィールを編集' }).click()
  await expect(page).toHaveURL(/\/settings\/profile$/)
  const save = page.getByRole('button', { name: '保存する' })
  await expect(save).toBeDisabled()

  await page.getByLabel('表示名').fill('   ')
  await save.click()
  await expect(page.getByText('表示名を入力してください。')).toBeVisible()

  const name = `新しい名前 ${taro.username}`
  const bio = '<script>alert(1)</script>\n二行目です'
  await page.getByLabel('表示名').fill(name)
  await page.getByLabel('自己紹介').fill(bio)
  await save.click()

  await expect(page).toHaveURL(new RegExp(`/users/${taro.username}$`))
  await expect(page.getByRole('heading', { name })).toBeVisible()
  await expect(page.getByRole('link', { name: '自分のプロフィール' })).toHaveAttribute('title', new RegExp(name))
  await expect(page.getByRole('region', { name: 'プロフィール' })).toContainText('<script>alert(1)</script>')
  expect(await api<Profile>(page, `/api/users/${taro.username}`)).toMatchObject({ displayName: name, bio })

  // ほかの人が見るカードの名前も変わる
  await hanaPage.reload()
  await expect(hanaPage.getByRole('article', { name: `${name}のレビュー` })).toBeVisible()
})

// TC-11-5
test('存在しないユーザーの G-8 は M-28', async ({ browser }) => {
  const page = await signup(browser, user('pd', 'たろう'))
  await page.goto('/users/nobody_e2e')
  await expect(page.getByText('このユーザーは見つかりません。')).toBeVisible()
  await expect(page.getByRole('navigation', { name: 'レビュー・本棚' })).toHaveCount(0)
})

// TC-01-13〜TC-01-16
// A-11〜A-13・N-22
test('退会：パスワードを確かめて全部消し、ほかのブラウザのログインも切れ、同じ ID で登録できる', async ({ browser }) => {
  const taro = user('pe', 'たろう')
  const hana = user('pf', 'はなこ')
  const page = await signup(browser, taro)
  const hanaPage = await signup(browser, hana)
  const id = await importBook(page, '退会の本')
  const mine = await createReviewByApi(page, id, 'たろうの感想', { photo: photoFile('a.png') })
  await send(hanaPage, 'POST', `/api/reviews/${mine.id}/comments`, { data: { body: 'はなこのコメント' } })
  await send(hanaPage, 'PUT', `/api/users/${taro.username}/follow`)
  await send(page, 'PUT', `/api/users/${hana.username}/follow`)
  const photoUrl = mine.photos[0].url

  // 別のブラウザでもログインしておく
  const other = await (await newContext(browser)).newPage()
  await other.goto('/login')
  await submitLogin(other, taro.username, PASSWORD)
  await expect(other.getByRole('link', { name: '自分のプロフィール' })).toBeVisible()

  await page.goto('/settings/profile')
  await page.getByRole('button', { name: '退会する' }).click()
  const dialog = page.getByRole('alertdialog', { name: '退会' })
  await expect(dialog.getByRole('button', { name: 'キャンセル' })).toBeFocused()
  const submit = dialog.getByRole('button', { name: '退会する' })
  await expect(submit).toBeDisabled()

  await test.step('パスワードが違うと M-10 で何も消えない', async () => {
    await dialog.getByLabel('パスワード').fill('wrong-pass1')
    await submit.click()
    await expect(dialog.getByText('パスワードが違います。')).toBeVisible()
    expect((await api<ReviewView>(hanaPage, `/api/reviews/${mine.id}`)).body).toBe('たろうの感想')
  })

  await test.step('正しいパスワードで退会すると M-11 で G-1 に移る', async () => {
    await dialog.getByLabel('パスワード').fill(PASSWORD)
    await submit.click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.getByText('退会しました。ご利用ありがとうございました。')).toBeVisible()
  })

  await test.step('その人のものはすべて消え、ほかの人の数も減り、本は残る', async () => {
    expect((await hanaPage.request.get(`/api/reviews/${mine.id}`)).status()).toBe(404)
    expect((await hanaPage.request.get(`/api/users/${taro.username}`)).status()).toBe(404)
    expect(await api<Profile>(hanaPage, `/api/users/${hana.username}`)).toMatchObject({ followingCount: 0, followerCount: 0 })
    expect((await hanaPage.request.get(`/api/books/${id}`)).status()).toBe(200)
    await expect.poll(async () => (await hanaPage.request.get(photoUrl)).status()).toBe(404)
  })

  await test.step('別のブラウザに残った JWT では使えず、G-1 に移る', async () => {
    expect((await other.request.get('/api/timeline?tab=all')).status()).toBe(401)
    await other.goto('/')
    await expect(other).toHaveURL(/\/login$/)
  })

  await test.step('同じユーザー ID でまた登録できる', async () => {
    const again = await signup(browser, taro)
    expect(await api<Profile>(again, `/api/users/${taro.username}`)).toMatchObject({ reviewCount: 0 })
  })
})
