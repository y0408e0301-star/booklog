import { render } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, type InitialEntry } from 'react-router'
import { vi } from 'vitest'
import type { Me, ReviewView } from '../api'
import { AuthContext, type Auth } from '../auth/AuthContext'
import { ToastProvider } from '../components/ToastProvider'
import CurrentLocation from './CurrentLocation'

// 部品のテストで使う準備。部品を 1 つだけ描画し、ルーター・ログインしている自分・トーストを用意する。
// App 全体（AuthProvider）は使わないので、GET /api/auth/me は呼ばない

export const me: Me = { username: 'taro_y', displayName: 'たろう' }

// レビュー（機能定義書 3.5 ReviewView）。指定しなければ自分が 1 分前に書いた「こころ」のレビュー
export function review(overrides: Partial<ReviewView> = {}): ReviewView {
  return {
    id: 1234,
    author: { username: 'taro_y', displayName: 'たろう' },
    book: { id: 56, title: 'こころ', authors: ['夏目漱石'], thumbnailUrl: null },
    rating: 4,
    body: '最後まで一気に読んだ。',
    spoiler: false,
    photos: [],
    helpfulCount: 0,
    commentCount: 0,
    helpfulByMe: false,
    mine: true,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    editedAt: null,
    ...overrides,
  }
}

type Options = {
  // URL か、state（G-1 に渡すメッセージなど）つきの場所
  path?: InitialEntry
  // 部品が useParams で読む URL の形（例：/books/:id）
  route?: string
  auth?: Partial<Auth>
}

export function renderComponent(ui: ReactNode, { path = '/', route = '*', auth = {} }: Options = {}) {
  const value: Auth = { me, signedIn: vi.fn(), signOut: vi.fn(async () => {}), signedOut: vi.fn(), ...auth }
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext value={value}>
        <ToastProvider>
          <Routes>
            <Route path={route} element={<>{ui}<CurrentLocation /></>} />
            {route !== '*' && <Route path="*" element={<CurrentLocation />} />}
          </Routes>
        </ToastProvider>
      </AuthContext>
    </MemoryRouter>,
  )
}
