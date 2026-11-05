import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { fetchMe, logout, setSessionExpiredHandler, type Me } from '../api'
import { M } from '../messages'
import { AuthContext, type Auth } from './AuthContext'

// 誰がログインしているかを持つ。JWT は JavaScript から読めないので、画面を開いたときに
// GET /api/auth/me で確かめる（機能定義書 FN-01 6 章）
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const navigate = useNavigate()

  useEffect(() => {
    let active = true
    async function load() {
      // 確かめられなかったとき（API サーバーが止まっているなど）は、ログインしていないことにする
      const result = await fetchMe().catch(() => null)
      if (active) setMe(result)
    }
    void load()
    return () => {
      active = false
    }
  }, [])

  // 操作の途中で取り直しに失敗したら、M-8 を出して G-1 に移す（A-8）
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setMe(null)
      navigate('/login', { replace: true, state: { message: M[8] } })
    })
    return () => setSessionExpiredHandler(() => {})
  }, [navigate])

  const signedIn = useCallback((next: Me) => setMe(next), [])

  const signedOut = useCallback(() => {
    setMe(null)
    navigate('/login', { replace: true })
  }, [navigate])

  // 失敗しても G-1 に戻す（Cookie が残っていても、次に開いたときに確かめ直す）
  const signOut = useCallback(async () => {
    try {
      await logout()
    } catch {
      // 何もしない
    }
    signedOut()
  }, [signedOut])

  const auth = useMemo<Auth>(() => ({ me, signedIn, signOut, signedOut }), [me, signedIn, signOut, signedOut])
  return <AuthContext value={auth}>{children}</AuthContext>
}
