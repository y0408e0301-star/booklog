import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { Me } from '../api'
import { AuthContext } from '../auth/AuthContext'
import { M } from '../messages'
import Layout from './Layout'

// G-3〜G-10 で共通のヘッダー（画面設計書 2.6）

const me: Me = { username: 'taro_y', displayName: 'たろう' }

function renderLayout(path: string, signOut = vi.fn(async () => {})) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext value={{ me, signedIn: vi.fn(), signOut }}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="*" element={<p>中身</p>} />
          </Route>
        </Routes>
      </AuthContext>
    </MemoryRouter>,
  )
  return { signOut }
}

describe('Layout', () => {
  it('アプリ名は G-3、自分の頭文字の丸は自分の G-8 へのリンク', () => {
    renderLayout('/books/1')
    expect(screen.getByRole('link', { name: 'BookLog' })).toHaveAttribute('href', '/')
    const profile = screen.getByRole('link', { name: '自分のプロフィール' })
    expect(profile).toHaveAttribute('href', '/users/taro_y')
    expect(profile).toHaveTextContent('た')
    expect(screen.getByText('中身')).toBeInTheDocument()
  })

  it('通信が切れたら、ヘッダーのすぐ下に M-31 を出す', () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    renderLayout('/')
    expect(within(screen.getByRole('banner')).getByRole('alert')).toHaveTextContent(M[31])
  })

  it('ログアウトは確認なしで送り、送っている間は押せない', async () => {
    const { signOut } = renderLayout('/', vi.fn(() => new Promise<void>(() => {})))
    await userEvent.click(screen.getByRole('button', { name: 'ログアウト' }))
    expect(signOut).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: 'ログアウト' })).toBeDisabled()
  })
})
