import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, type InitialEntry } from 'react-router'
import { vi } from 'vitest'
import type { Me } from '../api'
import { AuthContext, type Auth } from '../auth/AuthContext'
import { ToastProvider } from '../components/ToastProvider'
import CurrentLocation from './CurrentLocation'

// 部品のテストで使う準備。部品を 1 つだけ描画し、ルーター・ログインしている自分・トーストを用意する。
// App 全体（AuthProvider）は使わないので、GET /api/auth/me は呼ばない

export const me: Me = { username: 'taro_y', displayName: 'たろう' }

// path は URL か、state（G-1 に渡すメッセージなど）つきの場所
export function renderComponent(ui: ReactNode, { path = '/', auth = {} }: { path?: InitialEntry, auth?: Partial<Auth> } = {}) {
  const value: Auth = { me, signedIn: vi.fn(), signOut: vi.fn(async () => {}), ...auth }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext value={value}>
        <ToastProvider>
          <Routes>
            <Route path="*" element={<>{ui}<CurrentLocation /></>} />
          </Routes>
        </ToastProvider>
      </AuthContext>
    </MemoryRouter>,
  )
}
