import { Navigate, Outlet, useLocation } from 'react-router'
import OfflineNotice from '../components/OfflineNotice'
import { useAuth } from './AuthContext'

// ログインしていないと開けない画面（G-3〜G-10）。ログインしていなければ G-1 を出し、
// ログインした後に開こうとした画面へ移す（A-9）
export function RequireAuth() {
  const { me } = useAuth()
  const location = useLocation()
  if (me === undefined) return null
  if (me === null) return <Navigate to="/login" replace state={{ from: location }} />
  return <Outlet />
}

// ログインしていないときだけの画面（G-1・G-2）。ログインしていれば G-3 に移す（FN-01 8 章）。
// ヘッダーがないので、通信切れのお知らせ欄（M-31）は画面の一番上に出す
export function GuestOnly() {
  const { me } = useAuth()
  if (me === undefined) return null
  if (me) return <Navigate to="/" replace />
  return (
    <>
      <OfflineNotice />
      <Outlet />
    </>
  )
}
