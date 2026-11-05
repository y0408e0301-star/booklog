import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { ApiError, withdraw } from '../api'
import { useAuth } from '../auth/AuthContext'
import { M } from '../messages'
import { PASSWORD_MAX } from '../validation'
import styles from './ConfirmDialog.module.css'
import own from './WithdrawDialog.module.css'
import { useToast } from './ToastContext'
import ui from './ui.module.css'

// G-13 退会の確認ダイアログ（画面設計書 15 章、FN-01 A-10・A-11）。パスワードを入れ直して退会する
export default function WithdrawDialog({ onCancel }: { onCancel: () => void }) {
  const id = useId()
  const { signedOut } = useAuth()
  const toast = useToast()
  const dialogRef = useRef<HTMLFormElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // 開いたら「キャンセル」にカーソルを置き（Enter キーで誤って退会しないため）、閉じたら元の場所に戻す。
  // 開いている間は後ろの画面をスクロールさせない（画面設計書 2.5）
  useEffect(() => {
    const back = document.activeElement instanceof HTMLElement ? document.activeElement : null
    cancelRef.current?.focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = overflow
      if (back?.isConnected) back.focus()
    }
  }, [])

  function cancel() {
    if (!busy) onCancel()
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (password === '' || busy) return
    setBusy(true)
    try {
      await withdraw(password)
      toast(M[11])
      signedOut()
    } catch (err) {
      setBusy(false)
      if (err instanceof ApiError && err.field === 'password') {
        // M-10。退会しない
        setError(err.message)
        passwordRef.current?.focus()
      } else {
        toast(M[30])
      }
    }
  }

  // Esc キーで「キャンセル」。Tab キーのカーソルはダイアログの中だけで動かす
  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      cancel()
    } else if (e.key === 'Tab') {
      const items = [...(dialogRef.current?.querySelectorAll<HTMLElement>('input, button:not(:disabled)') ?? [])]
      const index = items.indexOf(document.activeElement as HTMLElement)
      const next = items[(index + (e.shiftKey ? -1 : 1) + items.length) % items.length]
      e.preventDefault()
      next?.focus()
    }
  }

  return (
    // 覆いの部分のクリックは「キャンセル」と同じ（キーボードでは Esc キー）
    // oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && cancel()} onKeyDown={handleKeyDown}>
      <form ref={dialogRef} className={styles.dialog} role="alertdialog" aria-modal="true" noValidate
        aria-labelledby={`${id}-title`} aria-describedby={`${id}-message`} onSubmit={(e) => void handleSubmit(e)}>
        <h2 id={`${id}-title`}>退会</h2>
        <p id={`${id}-message`}>{M[9]}</p>
        <div className={own.field}>
          <label htmlFor={`${id}-password`} className={own.label}>パスワード</label>
          <input id={`${id}-password`} ref={passwordRef} type="password" autoComplete="current-password"
            maxLength={PASSWORD_MAX} value={password} className={error ? `${own.input} ${own.invalid}` : own.input}
            aria-invalid={error ? true : undefined} aria-describedby={error ? `${id}-error` : undefined}
            onChange={(e) => {
              setPassword(e.target.value)
              setError(null)
            }} />
          {error && <p className={own.error} id={`${id}-error`}>{error}</p>}
        </div>
        <div className={styles.buttons}>
          <button type="button" ref={cancelRef} className={ui.secondary} onClick={cancel} disabled={busy}>
            キャンセル
          </button>
          {/* パスワードが空のときは押せない。送信中も押せない */}
          <button type="submit" className={ui.danger} disabled={password === '' || busy}>
            {busy ? '退会中…' : '退会する'}
          </button>
        </div>
      </form>
    </div>
  )
}
