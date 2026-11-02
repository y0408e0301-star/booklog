import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import styles from './ConfirmDialog.module.css'
import ui from './ui.module.css'

type Props = {
  title: string
  message: string
  // 「削除」を押したとき。終わったら（失敗しても）呼んだ側がダイアログを閉じる
  onConfirm: () => Promise<void>
  onCancel: () => void
}

// G-12 確認ダイアログ（画面設計書 2.5・14 章）。レビュー・コメントを削除する前に出す
export default function ConfirmDialog({ title, message, onConfirm, onCancel }: Props) {
  const id = useId()
  const cancelRef = useRef<HTMLButtonElement>(null)
  const confirmRef = useRef<HTMLButtonElement>(null)
  const [busy, setBusy] = useState(false)

  // 開いたら「キャンセル」にカーソルを置き（Enter キーで誤って削除しないため）、閉じたら元の場所に戻す。
  // 開いている間は後ろの画面をスクロールさせない
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

  async function confirm() {
    setBusy(true)
    await onConfirm()
  }

  // Esc キーで「キャンセル」。Tab キーのカーソルはダイアログの 2 つのボタンの間だけで動かす
  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      cancel()
    } else if (e.key === 'Tab') {
      e.preventDefault()
      const next = document.activeElement === cancelRef.current ? confirmRef.current : cancelRef.current
      next?.focus()
    }
  }

  return (
    // 覆いの部分のクリックは「キャンセル」と同じ（キーボードでは Esc キー）
    // oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && cancel()} onKeyDown={handleKeyDown}>
      <div className={styles.dialog} role="alertdialog" aria-modal="true" aria-labelledby={`${id}-title`}
        aria-describedby={`${id}-message`}>
        <h2 id={`${id}-title`}>{title}</h2>
        <p id={`${id}-message`}>{message}</p>
        <div className={styles.buttons}>
          <button type="button" ref={cancelRef} className={ui.secondary} onClick={cancel} disabled={busy}>
            キャンセル
          </button>
          <button type="button" ref={confirmRef} className={ui.danger} onClick={confirm} disabled={busy}>
            {busy ? '削除中…' : '削除'}
          </button>
        </div>
      </div>
    </div>
  )
}
