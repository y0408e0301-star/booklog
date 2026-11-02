import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { isUsernameAvailable, setSessionExpiredHandler } from '../api'
import { json, mockFetch, type Handler } from '../test/fetchMock'
import { useAuth } from './AuthContext'
import { AuthProvider } from './AuthProvider'

// 誰がログインしているかを持つ（FN-01 6 章）。画面を開いたときに GET /api/auth/me で確かめる

const me = { username: 'taro_y', displayName: 'たろう' }

// 今の自分と、G-1 に移ったときのメッセージを出す
function Probe() {
  const { me: current, signedIn, signOut } = useAuth()
  const location = useLocation()
  const message = (location.state as { message?: string } | null)?.message
  return (
    <>
      <p>{current === undefined ? '確かめ中' : current === null ? 'ログインしていない' : `ログイン中：${current.displayName}`}</p>
      <p>URL：{location.pathname}</p>
      {message && <p>{message}</p>}
      <button type="button" onClick={() => signedIn({ ...me, displayName: 'はなこ' })}>名前を変える</button>
      <button type="button" onClick={() => void signOut()}>ログアウト</button>
    </>
  )
}

function renderProvider(routes: Record<string, Handler | Handler[]>) {
  const fetch = mockFetch(routes)
  render(
    <MemoryRouter initialEntries={['/']}>
      <AuthProvider>
        <Routes>
          <Route path="*" element={<Probe />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
  return fetch
}

afterEach(() => {
  setSessionExpiredHandler(() => {})
})

const failed = () => json(401, { code: 'REFRESH_FAILED', message: '…' })

describe('AuthProvider', () => {
  it('開いたときに確かめ、ログインしていれば自分を持つ', async () => {
    renderProvider({ 'GET /api/auth/me': () => json(200, me) })
    expect(screen.getByText('確かめ中')).toBeInTheDocument()
    expect(await screen.findByText('ログイン中：たろう')).toBeInTheDocument()
  })

  it('ログインしていなければ null にする', async () => {
    renderProvider({ 'GET /api/auth/me': () => json(401, { code: 'UNAUTHENTICATED', message: '…' }), 'POST /api/auth/refresh': failed })
    expect(await screen.findByText('ログインしていない')).toBeInTheDocument()
  })

  it('API サーバーが止まっていて確かめられなくても、ログインしていないことにする', async () => {
    renderProvider({ 'GET /api/auth/me': () => new Response('Bad Gateway', { status: 502 }) })
    expect(await screen.findByText('ログインしていない')).toBeInTheDocument()
  })

  it('自分の表示名を変えたら、新しい自分を持つ', async () => {
    renderProvider({ 'GET /api/auth/me': () => json(200, me) })
    await screen.findByText('ログイン中：たろう')
    await userEvent.click(screen.getByRole('button', { name: '名前を変える' }))
    expect(screen.getByText('ログイン中：はなこ')).toBeInTheDocument()
  })

  it('ログアウトすると G-1 に移す。ログアウトの API が失敗しても移す', async () => {
    const fetch = renderProvider({
      'GET /api/auth/me': () => json(200, me),
      'POST /api/auth/logout': () => json(500, { code: 'INTERNAL_ERROR', message: '…' }),
    })
    await screen.findByText('ログイン中：たろう')
    await userEvent.click(screen.getByRole('button', { name: 'ログアウト' }))
    expect(await screen.findByText('URL：/login')).toBeInTheDocument()
    expect(screen.getByText('ログインしていない')).toBeInTheDocument()
    expect(fetch.keys()).toContain('POST /api/auth/logout')
  })

  // TC-01-12
  it('操作の途中で取り直しに失敗したら、M-8 を出して G-1 に移す', async () => {
    renderProvider({
      'GET /api/auth/me': () => json(200, me),
      'GET /api/auth/username-availability?username=taro_y': () => json(401, { code: 'TOKEN_EXPIRED', message: '…' }),
      'POST /api/auth/refresh': failed,
    })
    await screen.findByText('ログイン中：たろう')
    await act(async () => {
      await isUsernameAvailable('taro_y').catch(() => {})
    })
    await waitFor(() => expect(screen.getByText('URL：/login')).toBeInTheDocument())
    expect(screen.getByText('ログインの有効期限が切れました。もう一度ログインしてください。')).toBeInTheDocument()
    expect(screen.getByText('ログインしていない')).toBeInTheDocument()
  })
})
