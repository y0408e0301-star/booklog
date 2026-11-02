import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { Me } from '../api'
import { AuthContext, useAuth } from './AuthContext'
import { GuestOnly, RequireAuth } from './guards'

// ログインしていないと開けない画面・ログインしていないときだけの画面（A-9、FN-01 8 章）

const me: Me = { username: 'taro_y', displayName: 'たろう' }

// G-1 に移ったとき、ログインした後に開く画面（state.from）を出す
function LoginPage() {
  const location = useLocation()
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
  return <p>G-1（ログインした後は {from ?? 'なし'}）</p>
}

function renderAt(path: string, current: Me | null | undefined) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext value={{ me: current, signedIn: vi.fn(), signOut: vi.fn(async () => {}) }}>
        <Routes>
          <Route element={<GuestOnly />}>
            <Route path="/login" element={<LoginPage />} />
          </Route>
          <Route element={<RequireAuth />}>
            <Route path="/" element={<p>G-3</p>} />
            <Route path="/users/:username" element={<p>G-8</p>} />
          </Route>
        </Routes>
      </AuthContext>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  it('ログインしていれば開く', () => {
    renderAt('/users/hanako', me)
    expect(screen.getByText('G-8')).toBeInTheDocument()
  })

  // TC-01-6
  it('ログインしていなければ G-1 に移し、ログインした後に開こうとした画面を渡す', () => {
    renderAt('/users/hanako', null)
    expect(screen.getByText('G-1（ログインした後は /users/hanako）')).toBeInTheDocument()
  })

  it('確かめている途中は何も出さない', () => {
    const { container } = renderAt('/users/hanako', undefined)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('GuestOnly', () => {
  it('ログインしていなければ開く', () => {
    renderAt('/login', null)
    expect(screen.getByText('G-1（ログインした後は なし）')).toBeInTheDocument()
  })

  it('ログインしていれば G-3 に移す', () => {
    renderAt('/login', me)
    expect(screen.getByText('G-3')).toBeInTheDocument()
  })

  it('確かめている途中は何も出さない', () => {
    const { container } = renderAt('/login', undefined)
    expect(container).toBeEmptyDOMElement()
  })
})

function UsesAuth() {
  useAuth()
  return null
}

describe('useAuth', () => {
  it('AuthProvider の外では使えない', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<UsesAuth />)).toThrow('AuthProvider の中で使ってください')
  })
})
