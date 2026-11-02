import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import App from './App'
import { M } from './messages'
import { json, mockFetch } from './test/fetchMock'

const me = { username: 'taro_y', displayName: 'たろう' }

// G-3（フォロー中のタブが空）に出す文
const G3 = M[25]

// G-3 を開くと「フォロー中」のタブを読む
const timeline = { 'GET /api/timeline?tab=following': () => json(200, { items: [], nextCursor: null }) }

const notLoggedIn = {
  'GET /api/auth/me': () => json(401, { code: 'UNAUTHENTICATED', message: '…' }),
  'POST /api/auth/refresh': () => json(401, { code: 'REFRESH_FAILED', message: '…' }),
}

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}

describe('ログイン（G-1）', () => {
  it('ログインしていなければ G-1 を出し、ログインすると G-3 に移る', async () => {
    mockFetch({ ...notLoggedIn, 'POST /api/auth/login': () => json(200, me), ...timeline })
    renderAt('/')
    const button = await screen.findByRole('button', { name: 'ログイン' })
    // どちらかが空のときは押せない
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('ユーザー ID'), 'taro_y')
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('パスワード'), 'password1')
    await userEvent.click(button)
    expect(await screen.findByText(G3)).toBeInTheDocument()
    expect(screen.getByTitle('たろう（@taro_y）')).toBeInTheDocument()
  })

  it('失敗したら API のメッセージをフォームの上に出す', async () => {
    mockFetch({
      ...notLoggedIn,
      'POST /api/auth/login': () => json(401, { code: 'LOGIN_FAILED', message: 'ユーザー ID かパスワードが違います。' }),
    })
    renderAt('/login')
    await userEvent.type(await screen.findByLabelText('ユーザー ID'), 'taro_y')
    await userEvent.type(screen.getByLabelText('パスワード'), 'wrong1234')
    await userEvent.click(screen.getByRole('button', { name: 'ログイン' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('ユーザー ID かパスワードが違います。')
  })

  it('ログインしていれば G-1 を開いても G-3 に移る', async () => {
    mockFetch({ 'GET /api/auth/me': () => json(200, me), ...timeline })
    renderAt('/login')
    expect(await screen.findByText(G3)).toBeInTheDocument()
  })

  it('ログアウトすると G-1 に戻る', async () => {
    const fetch = mockFetch({ 'GET /api/auth/me': () => json(200, me), 'POST /api/auth/logout': () => json(204), ...timeline })
    renderAt('/')
    await userEvent.click(await screen.findByRole('button', { name: 'ログアウト' }))
    expect(await screen.findByRole('button', { name: 'ログイン' })).toBeInTheDocument()
    expect(fetch.keys()).toContain('POST /api/auth/logout')
  })
})

describe('ユーザー登録（G-2）', () => {
  it('条件に合わない項目の下にメッセージを出し、送らない', async () => {
    const fetch = mockFetch(notLoggedIn)
    renderAt('/signup')
    await userEvent.type(await screen.findByLabelText('ユーザー ID'), 'ab')
    await userEvent.type(screen.getByLabelText('パスワード'), 'password')
    await userEvent.type(screen.getByLabelText('パスワード（確認）'), 'passw0rd')
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))
    expect(screen.getByText('ユーザー ID は 3〜15 文字の半角英数字と「_」で入力してください。')).toBeInTheDocument()
    expect(screen.getByText('表示名を入力してください。')).toBeInTheDocument()
    expect(screen.getByText('パスワードは英字と数字を含む 8〜64 文字の半角文字で入力してください。')).toBeInTheDocument()
    expect(screen.getByText('パスワード（確認）が一致しません。')).toBeInTheDocument()
    expect(screen.getByLabelText('ユーザー ID')).toHaveFocus()
    expect(fetch.keys()).not.toContain('POST /api/auth/signup')
  })

  it('ユーザー ID の入力欄から離れたとき、使われていれば M-1 を出す', async () => {
    mockFetch({
      ...notLoggedIn,
      'GET /api/auth/username-availability?username=taro_y': () => json(200, { available: false }),
    })
    renderAt('/signup')
    await userEvent.type(await screen.findByLabelText('ユーザー ID'), 'taro_y')
    await userEvent.tab()
    expect(await screen.findByText('このユーザー ID は使われています。')).toBeInTheDocument()
  })

  it('表示名は 30 文字（コードポイント）まで。絵文字も 1 文字と数え、31 文字目は入力できない', async () => {
    mockFetch(notLoggedIn)
    renderAt('/signup')
    await userEvent.click(await screen.findByLabelText('表示名'))
    await userEvent.paste('😀'.repeat(31))
    expect(Array.from((screen.getByLabelText('表示名') as HTMLInputElement).value)).toHaveLength(30)
  })

  it('登録すると G-3 に移る', async () => {
    const fetch = mockFetch({
      ...notLoggedIn,
      'GET /api/auth/username-availability?username=hanako': () => json(200, { available: true }),
      'POST /api/auth/signup': () => json(201, { username: 'hanako', displayName: 'はなこ' }),
      ...timeline,
    })
    renderAt('/signup')
    await userEvent.type(await screen.findByLabelText('ユーザー ID'), ' hanako ')
    await userEvent.type(screen.getByLabelText('表示名'), 'はなこ')
    await userEvent.type(screen.getByLabelText('パスワード'), 'password1')
    await userEvent.type(screen.getByLabelText('パスワード（確認）'), 'password1')
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))
    expect(await screen.findByText(G3)).toBeInTheDocument()
    const sent = fetch.calls.find((c) => c.key === 'POST /api/auth/signup')
    expect(JSON.parse(sent?.init?.body as string)).toEqual({ username: 'hanako', displayName: 'はなこ', password: 'password1' })
  })
})
