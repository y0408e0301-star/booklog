import { createContext, useContext } from 'react'
import type { Me } from '../api'

export type Auth = {
  // undefined：確かめている途中、null：ログインしていない
  me: Me | null | undefined
  // ログイン・登録に成功したとき、自分の表示名を変えたとき（G-9）に呼ぶ
  signedIn: (me: Me) => void
  // ログアウトして G-1 に移す
  signOut: () => Promise<void>
}

export const AuthContext = createContext<Auth | null>(null)

export function useAuth(): Auth {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider の中で使ってください')
  return auth
}
