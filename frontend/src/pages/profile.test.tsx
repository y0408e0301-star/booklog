import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Profile, ShelfItem } from '../api'
import { M } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { mockIntersectionObserver } from '../test/intersection'
import { renderComponent, review } from '../test/render'
import EditProfilePage from './EditProfilePage'
import ProfilePage from './ProfilePage'

// G-8 プロフィール画面（FN-11 PF-1〜PF-5、FN-08 ST-4〜ST-6）と G-9 プロフィールの編集画面（PF-6・PF-7）

function profile(overrides: Partial<Profile> = {}): Profile {
  return {
    username: 'hanako',
    displayName: 'はなこ',
    bio: '',
    reviewCount: 12,
    followingCount: 8,
    followerCount: 30,
    bookshelfCounts: { WANT: 5, READING: 2, READ: 14 },
    followedByMe: false,
    me: false,
    ...overrides,
  }
}

function shelfItem(id: number, title: string, rating: number | null = null): ShelfItem {
  return {
    book: { id, title, authors: ['夏目漱石'], thumbnailUrl: null },
    status: 'READ',
    rating,
    updatedAt: '2026-11-01T00:00:00Z',
  }
}

const page = (items: unknown[], nextCursor: string | null = null) => () => json(200, { items, nextCursor })
const noReviews = { 'GET /api/users/hanako/reviews': page([]) }
const notFound = () => json(404, { code: 'USER_NOT_FOUND', message: M[28], field: null })

function renderProfile(path = '/users/hanako') {
  return renderComponent(<ProfilePage />, { path, route: '/users/:username' })
}

describe('プロフィール画面（G-8）', () => {
  // PF-2・TC-11-6
  it('表示名・@ユーザー ID・自己紹介・件数を出す。自己紹介はタグも文字のまま改行もそのまま', async () => {
    const bio = '<script>alert(1)</script>\n二行目'
    mockFetch({ 'GET /api/users/hanako': () => json(200, profile({ bio })), ...noReviews })
    renderProfile()
    expect(await screen.findByRole('heading', { name: 'はなこ' })).toBeInTheDocument()
    const head = screen.getByRole('region', { name: 'プロフィール' })
    expect(head).toHaveTextContent('@hanako')
    expect(within(head).getByText(/<script>/).textContent).toBe(bio)
    expect(head).toHaveTextContent('12 レビュー')
    expect(within(head).getByRole('link', { name: '8 フォロー' })).toHaveAttribute('href', '/users/hanako/following')
    expect(within(head).getByRole('link', { name: '30 フォロワー' })).toHaveAttribute('href', '/users/hanako/followers')
  })

  // TC-11-2
  it('ほかの人にはフォローボタン、自分には「プロフィールを編集」', async () => {
    mockFetch({ 'GET /api/users/hanako': () => json(200, profile({ followedByMe: true })), ...noReviews })
    const { unmount } = renderProfile()
    expect(await screen.findByRole('button', { name: /^フォロー中/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'プロフィールを編集' })).not.toBeInTheDocument()
    unmount()

    mockFetch({ 'GET /api/users/hanako': () => json(200, profile({ me: true })), ...noReviews })
    renderProfile()
    expect(await screen.findByRole('link', { name: 'プロフィールを編集' })).toHaveAttribute('href', '/settings/profile')
    expect(screen.queryByRole('button', { name: 'フォローする' })).not.toBeInTheDocument()
  })

  it('フォローしたらフォロワー数を応答の最新の数にする', async () => {
    mockFetch({
      'GET /api/users/hanako': () => json(200, profile()),
      'PUT /api/users/hanako/follow': () => json(200, { following: true, followerCount: 35 }),
      ...noReviews,
    })
    renderProfile()
    await userEvent.click(await screen.findByRole('button', { name: 'フォローする' }))
    expect(await screen.findByRole('link', { name: '35 フォロワー' })).toBeInTheDocument()
  })

  // PF-4
  it('初めは「レビュー」タブで、レビューを出す。0 件なら M-26', async () => {
    mockFetch({
      'GET /api/users/hanako': () => json(200, profile()),
      'GET /api/users/hanako/reviews': page([review({ body: 'はなこの感想' })]),
    })
    const { unmount } = renderProfile()
    expect(await screen.findByText('はなこの感想')).toBeInTheDocument()
    const tabs = screen.getByRole('navigation', { name: 'レビュー・本棚' })
    expect(within(tabs).getByRole('link', { name: 'レビュー' })).toHaveAttribute('aria-current', 'page')
    unmount()

    mockFetch({ 'GET /api/users/hanako': () => json(200, profile()), ...noReviews })
    renderProfile()
    expect(await screen.findByText(M[26])).toBeInTheDocument()
  })

  it('一番下まで見たら次の 20 件を足す', async () => {
    const io = mockIntersectionObserver()
    mockFetch({
      'GET /api/users/hanako': () => json(200, profile()),
      'GET /api/users/hanako/reviews': page([review({ id: 2, body: '一件目' })], '2'),
      'GET /api/users/hanako/reviews?cursor=2': page([review({ id: 1, body: '二件目' })]),
    })
    renderProfile()
    await screen.findByText('一件目')
    await vi.waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    expect(await screen.findByText('二件目')).toBeInTheDocument()
  })

  it('レビューを削除したら一覧から消し、件数を減らす', async () => {
    mockFetch({
      'GET /api/users/hanako': () => json(200, profile({ me: true })),
      'GET /api/users/hanako/reviews': page([review({ body: '消す感想' })]),
      'DELETE /api/reviews/1234': () => new Response(null, { status: 204 }),
    })
    renderProfile()
    await screen.findByText('消す感想')
    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除' }))
    await waitFor(() => expect(screen.queryByText('消す感想')).not.toBeInTheDocument())
    expect(screen.getByRole('region', { name: 'プロフィール' })).toHaveTextContent('11 レビュー')
  })

  // ST-4・ST-5
  it('「本棚」タブは初めに「読書中」を開き、冊数を出す。本を押すと G-5', async () => {
    const fetch = mockFetch({
      'GET /api/users/hanako': () => json(200, profile()),
      ...noReviews,
      'GET /api/users/hanako/bookshelf?status=READING': page([shelfItem(56, 'こころ', 4), shelfItem(57, '門')]),
    })
    renderProfile()
    await userEvent.click(await screen.findByRole('link', { name: '本棚' }))
    const shelves = await screen.findByRole('navigation', { name: '本棚の分類' })
    expect(within(shelves).getByRole('link', { name: '読書中 2' })).toHaveAttribute('aria-current', 'page')
    expect(within(shelves).getByRole('link', { name: '読了 14' })).toBeInTheDocument()
    expect(within(shelves).getByRole('link', { name: '読みたい 5' })).toBeInTheDocument()

    const kokoro = await screen.findByRole('link', { name: /こころ/ })
    expect(kokoro).toHaveAttribute('href', '/books/56')
    expect(kokoro).toHaveTextContent('夏目漱石')
    expect(kokoro).toHaveTextContent('星 4（5 段階中）')
    expect(screen.getByRole('link', { name: /門/ })).not.toHaveTextContent('星')
    expect(fetch.keys()).toContain('GET /api/users/hanako/bookshelf?status=READING')
  })

  // ST-4・ST-6
  it('小さなタブを押すとその分類を読む。0 冊なら M-27。URL を開いても同じ分類', async () => {
    mockFetch({
      'GET /api/users/hanako': () => json(200, profile()),
      'GET /api/users/hanako/bookshelf?status=READ': page([shelfItem(56, 'こころ')]),
      'GET /api/users/hanako/bookshelf?status=WANT': page([]),
    })
    renderProfile('/users/hanako?tab=bookshelf&shelf=read')
    expect(await screen.findByRole('link', { name: /こころ/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: '読みたい 5' }))
    expect(await screen.findByText(M[27])).toBeInTheDocument()
    expect(screen.getByRole('status', { name: '今の URL' })).toHaveTextContent('shelf=want')
  })

  // TC-11-5・PF-5
  it('存在しないユーザーは M-28 だけ', async () => {
    mockFetch({ 'GET /api/users/nobody': notFound, 'GET /api/users/nobody/reviews': notFound })
    renderProfile('/users/nobody')
    expect(await screen.findByText(M[28])).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  })

  it('読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({ 'GET /api/users/hanako': [() => json(500, {}), () => json(200, profile())], ...noReviews })
    renderProfile()
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('heading', { name: 'はなこ' })).toBeInTheDocument()
  })
})

const myProfile = profile({ username: 'taro_y', displayName: 'たろう', bio: 'ミステリー', me: true })
const myRoute = { 'GET /api/users/taro_y': () => json(200, myProfile) }

async function renderEdit(routes: Parameters<typeof mockFetch>[0] = {}) {
  const fetch = mockFetch({ ...myRoute, ...routes })
  const signedIn = vi.fn()
  renderComponent(<EditProfilePage />, { path: '/settings/profile', auth: { signedIn } })
  const name = await screen.findByLabelText('表示名')
  return { fetch, signedIn, name, bio: screen.getByLabelText('自己紹介'), save: screen.getByRole('button', { name: '保存する' }) }
}

describe('プロフィールの編集画面（G-9）', () => {
  // PF-6
  it('今の値を入れておき、何も変えていないと「保存する」を押せない。残りの文字数を出す', async () => {
    const { name, bio, save } = await renderEdit()
    expect(name).toHaveValue('たろう')
    expect(bio).toHaveValue('ミステリー')
    expect(save).toBeDisabled()
    expect(screen.getByText('残り 155')).toBeInTheDocument()

    // 前後の空白だけなら変えていない
    await userEvent.type(name, ' ')
    expect(save).toBeDisabled()
    await userEvent.type(name, 'X')
    expect(save).toBeEnabled()
  })

  it('保存すると API に送り、ヘッダーの自分を変えて G-8 に戻る', async () => {
    const { fetch, signedIn, name, bio, save } = await renderEdit({
      'PATCH /api/me/profile': () => json(200, { ...myProfile, displayName: '新しい名前', bio: '一行目\n二行目' }),
    })
    await userEvent.clear(name)
    await userEvent.type(name, ' 新しい名前 ')
    await userEvent.clear(bio)
    await userEvent.type(bio, '一行目{Enter}二行目')
    await userEvent.click(save)
    await waitFor(() => expect(signedIn).toHaveBeenCalledWith({ username: 'taro_y', displayName: '新しい名前' }))
    expect(sentJson(fetch, 1)).toEqual({ displayName: '新しい名前', bio: '一行目\n二行目' })
    expect(screen.getByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/users\/taro_y$/)
  })

  it('自己紹介の欄の Ctrl＋Enter で保存する', async () => {
    const { fetch, bio } = await renderEdit({ 'PATCH /api/me/profile': () => json(200, myProfile) })
    await userEvent.type(bio, 'を読みます{Control>}{Enter}{/Control}')
    await waitFor(() => expect(fetch.keys()).toContain('PATCH /api/me/profile'))
  })

  // TC-11-3・PF-7
  it('表示名が空なら送らずに M-5', async () => {
    const { fetch, name, save } = await renderEdit()
    await userEvent.clear(name)
    await userEvent.type(name, '   ')
    await userEvent.click(save)
    expect(screen.getByText(M[5])).toBeInTheDocument()
    expect(name).toHaveFocus()
    expect(fetch.keys()).not.toContain('PATCH /api/me/profile')
  })

  it('上限を超えて入力はできない', async () => {
    const { name, bio } = await renderEdit()
    await userEvent.clear(name)
    await userEvent.click(name)
    await userEvent.paste('あ'.repeat(31))
    expect(name).toHaveValue('あ'.repeat(30))
    await userEvent.clear(bio)
    await userEvent.click(bio)
    await userEvent.paste('😀'.repeat(161))
    expect(bio).toHaveValue('😀'.repeat(160))
    expect(screen.getByText('残り 0')).toBeInTheDocument()
  })

  it('保存に失敗したら M-30 を出し、入力した内容を残す', async () => {
    const { bio, save } = await renderEdit({ 'PATCH /api/me/profile': () => json(500, {}) })
    await userEvent.type(bio, 'を読みます')
    await userEvent.click(save)
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(bio).toHaveValue('ミステリーを読みます')
    expect(save).toBeEnabled()
    expect(screen.getByRole('status', { name: '今の URL' })).toHaveTextContent('/settings/profile')
  })

  it('「キャンセル」と「←」は変えずに G-8', async () => {
    const { fetch, name } = await renderEdit()
    await userEvent.type(name, 'X')
    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(screen.getByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/users\/taro_y$/)
    expect(fetch.keys()).not.toContain('PATCH /api/me/profile')
  })

  // PF-8・A-10
  it('いちばん下の「退会する」で G-13 を開き、「キャンセル」で閉じる', async () => {
    await renderEdit()
    expect(screen.getByRole('region', { name: '退会' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '退会する' }))
    const dialog = screen.getByRole('alertdialog', { name: '退会' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'キャンセル' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '退会する' })).toHaveFocus()
  })

  it('読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({ 'GET /api/users/taro_y': [() => json(500, {}), () => json(200, myProfile)] })
    renderComponent(<EditProfilePage />)
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByLabelText('表示名')).toHaveValue('たろう')
  })
})
