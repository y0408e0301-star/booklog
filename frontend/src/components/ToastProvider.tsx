import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ToastContext } from './ToastContext'
import styles from './Toast.module.css'

const SHOW_MS = 5000

// 画面の下中央に 1 つだけ出す。新しいものが出たら古いものは消し、「×」か 5 秒で消す（画面設計書 2.4）
export function ToastProvider({ children }: { children: ReactNode }) {
  // 同じメッセージを続けて出したときも 5 秒を数え直すよう、出すたびに番号を変える
  const [toast, setToast] = useState<{ id: number, message: string } | null>(null)

  const show = useCallback((message: string) => {
    setToast((prev) => ({ id: (prev?.id ?? 0) + 1, message }))
  }, [])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), SHOW_MS)
    return () => clearTimeout(timer)
  }, [toast])

  return (
    <ToastContext value={show}>
      {children}
      {toast && (
        <output className={styles.toast}>
          <span className={styles.text}>{toast.message}</span>
          <button type="button" className={styles.close} aria-label="閉じる" onClick={() => setToast(null)}>×</button>
        </output>
      )}
    </ToastContext>
  )
}
