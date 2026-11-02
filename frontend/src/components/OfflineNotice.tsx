import { useSyncExternalStore } from 'react'
import { M } from '../messages'
import styles from './OfflineNotice.module.css'

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange)
  window.addEventListener('offline', onChange)
  return () => {
    window.removeEventListener('online', onChange)
    window.removeEventListener('offline', onChange)
  }
}

const isOnline = () => navigator.onLine

// インターネットに接続できないときのお知らせ欄（機能定義書 2 章、画面設計書 2.4）。
// ブラウザがオフラインになったら M-31 を出し、接続が戻ったら消す
export default function OfflineNotice() {
  const online = useSyncExternalStore(subscribe, isOnline, () => true)
  if (online) return null
  return <div className={styles.notice} role="alert">{M[31]}</div>
}
