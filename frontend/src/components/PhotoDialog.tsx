import { useEffect, useRef, type KeyboardEvent } from 'react'
import type { ReviewPhoto } from '../api'
import styles from './PhotoDialog.module.css'

type Props = {
  photos: ReviewPhoto[]
  index: number
  onIndexChange: (index: number) => void
  onClose: () => void
  // 写真を読み込めなかった（URL の期限切れなど）
  onError: () => void
}

// G-11 写真の拡大ダイアログ（画面設計書 13 章、FN-04 PH-7）。
// Esc キー・「×」・写真の外側のクリックで閉じる。2 枚以上なら「‹」「›」と左右の矢印キーで切り替える
export default function PhotoDialog({ photos, index, onIndexChange, onClose, onError }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const many = photos.length > 1
  const photo = photos[index]

  // 開いたら「×」にカーソルを置き、閉じたら元の場所に戻す。開いている間は後ろの画面をスクロールさせない
  useEffect(() => {
    const back = document.activeElement instanceof HTMLElement ? document.activeElement : null
    closeRef.current?.focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = overflow
      if (back?.isConnected) back.focus()
    }
  }, [])

  // 端まで行ったら反対の端に戻る
  function move(step: number) {
    onIndexChange((index + step + photos.length) % photos.length)
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    } else if (many && e.key === 'ArrowLeft') {
      e.preventDefault()
      move(-1)
    } else if (many && e.key === 'ArrowRight') {
      e.preventDefault()
      move(1)
    } else if (e.key === 'Tab') {
      // カーソルはダイアログのボタンの間だけで動かす
      const buttons = [...e.currentTarget.querySelectorAll('button')]
      const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
      e.preventDefault()
      buttons[(at + (e.shiftKey ? -1 : 1) + buttons.length) % buttons.length]?.focus()
    }
  }

  if (!photo) return null

  return (
    // 写真の外側のクリックで閉じる（キーボードでは Esc キー）
    // oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()} onKeyDown={handleKeyDown}>
      {/* 写真とボタン以外の場所はクリックが後ろの覆いに届く（CSS の pointer-events）ので、写真の外側を押すと閉じる */}
      <dialog open aria-modal="true" aria-label="写真の拡大" className={styles.dialog}>
        <img src={photo.url} alt={`写真 ${index + 1} / ${photos.length}`} className={styles.photo} onError={onError} />
        <button type="button" ref={closeRef} className={styles.close} onClick={onClose} aria-label="閉じる">×</button>
        {many && (
          <>
            <button type="button" className={`${styles.nav} ${styles.prev}`} onClick={() => move(-1)} aria-label="前の写真">‹</button>
            <button type="button" className={`${styles.nav} ${styles.next}`} onClick={() => move(1)} aria-label="次の写真">›</button>
            <p className={styles.counter}>{index + 1} / {photos.length}</p>
          </>
        )}
      </dialog>
    </div>
  )
}
