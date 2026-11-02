import { useState } from 'react'
import { Link, Outlet } from 'react-router'
import { profileUrl } from '../api'
import { useAuth } from '../auth/AuthContext'
import Avatar from './Avatar'
import styles from './Layout.module.css'
import OfflineNotice from './OfflineNotice'
import ui from './ui.module.css'

// G-3〜G-10 で共通のヘッダー（画面設計書 2.6）と、横幅 600px の 1 列（2.14）。
// 書籍の検索欄は、書籍検索（開発計画書 1-3）で足す
export default function Layout() {
  const { me, signOut } = useAuth()
  const [sending, setSending] = useState(false)

  // 確認なしでログアウトし G-1 に移す（A-9）
  async function handleLogout() {
    setSending(true)
    await signOut()
  }

  return (
    <>
      <header className={styles.header}>
        <div className={styles.inner}>
          {/* 狭い画面で「BL」に縮めても、読み上げは「BookLog」にする */}
          <Link to="/" className={styles.appName} aria-label="BookLog">
            <span className={styles.full}>BookLog</span>
            <span className={styles.short}>BL</span>
          </Link>
          <div className={styles.right}>
            {me && (
              // 自分の頭文字の丸を押すと自分の G-8
              <Link to={profileUrl(me.username)} title={`${me.displayName}（@${me.username}）`}
                aria-label="自分のプロフィール" className={styles.me}>
                <Avatar displayName={me.displayName} size={32} />
              </Link>
            )}
            <button type="button" className={ui.secondary} onClick={handleLogout} disabled={sending}>
              ログアウト
            </button>
          </div>
        </div>
        {/* お知らせ欄（M-31）。ヘッダーと一緒に画面の上に留める */}
        <OfflineNotice />
      </header>
      <main className={styles.container}>
        <Outlet />
      </main>
    </>
  )
}
