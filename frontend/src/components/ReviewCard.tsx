import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, bookUrl, deleteReview, profileUrl, reviewFormUrl, reviewUrl, type ReviewView } from '../api'
import { M } from '../messages'
import { fullTime, relativeTime } from '../time'
import Avatar from './Avatar'
import BookCover from './BookCover'
import ConfirmDialog from './ConfirmDialog'
import HelpfulButton from './HelpfulButton'
import styles from './ReviewCard.module.css'
import ReviewPhotos from './ReviewPhotos'
import Stars from './Stars'
import { useToast } from './ToastContext'
import ui from './ui.module.css'

type Props = {
  review: ReviewView
  // 本を出すか。G-5 では同じ本なので出さない（BK-5）
  showBook: boolean
  // G-7 のカード。感想を全部出し、感想・コメント数を押しても何もしない（RV-6）
  detail?: boolean
  // 削除した（削除されていた）レビューを、並べている画面から消す
  onDelete: (id: number) => void
  // G-7 では、ネタバレを開いたかを画面で持つ（コメントも一緒に伏せるため。C-6）。省いたらカードの中で持つ
  revealed?: boolean
  onReveal?: () => void
  // G-7 では、コメントを付けた・消した分を足したコメント数を出す（TC-06-4）。省いたら review の値
  commentCount?: number
}

// レビューのカード（画面設計書 2.7。G-3・G-5・G-7・G-8 で共通）
export default function ReviewCard({ review, showBook, detail = false, onDelete, ...props }: Props) {
  const navigate = useNavigate()
  const toast = useToast()
  const [confirming, setConfirming] = useState(false)
  // ネタバレありのレビューを開いたか。その画面を開いている間だけ覚える（RV-12）
  const [ownRevealed, setOwnRevealed] = useState(false)
  const revealed = props.revealed ?? ownRevealed
  const reveal = props.onReveal ?? (() => setOwnRevealed(true))
  const hidden = review.spoiler && !revealed
  const commentCount = props.commentCount ?? review.commentCount

  async function handleDelete() {
    try {
      await deleteReview(review.id)
      setConfirming(false)
      onDelete(review.id)
    } catch (e) {
      setConfirming(false)
      // 先に削除されていた（ほかの画面で消した）ときは M-20 を出して、画面からも消す（FN-03 9 章）
      if (e instanceof ApiError && e.code === 'REVIEW_NOT_FOUND') {
        toast(M[20])
        onDelete(review.id)
      } else {
        toast(M[30])
      }
    }
  }

  const { author, book } = review

  return (
    <article className={styles.card} aria-label={`${author.displayName}のレビュー`}>
      <Link to={profileUrl(author.username)} className={styles.avatar} tabIndex={-1} aria-hidden="true">
        <Avatar displayName={author.displayName} size={40} />
      </Link>
      <div className={styles.main}>
        <div className={styles.head}>
          {/* 表示名・@ユーザー ID を押すと書いた人の G-8（RV-8） */}
          <Link to={profileUrl(author.username)} className={styles.names}>
            <span className={styles.name}>{author.displayName}</span>
            <span className={styles.username}>@{author.username}</span>
          </Link>
          <time className={styles.time} dateTime={review.createdAt} title={fullTime(review.createdAt)}>
            · {relativeTime(review.createdAt)}
          </time>
          {review.editedAt && (
            <span className={styles.edited} title={`${fullTime(review.editedAt)} に編集`}>（編集済み）</span>
          )}
          {review.mine && (
            <ReviewMenu onEdit={() => navigate(reviewFormUrl(book.id))} onDelete={() => setConfirming(true)} />
          )}
        </div>
        {showBook && (
          // 表紙・書名を押すと G-5（RV-8）
          <Link to={bookUrl(book.id)} className={styles.book}>
            <BookCover title={book.title} thumbnailUrl={book.thumbnailUrl} width={48} />
            <span className={styles.bookInfo}>
              <span className={styles.bookTitle}>{book.title}</span>
              {book.authors.length > 0 && <span className={styles.bookAuthors}>{book.authors.join('、')}</span>}
            </span>
          </Link>
        )}
        <div className={styles.rating}><Stars value={review.rating} size={16} /></div>
        {hidden
          ? <SpoilerCover onReveal={reveal} />
          : (
            <>
              <ReviewBody review={review} detail={detail} focusOnMount={revealed} />
              <ReviewPhotos photos={review.photos} />
            </>
          )}
        <div className={styles.actions}>
          <HelpfulButton review={review} onDelete={onDelete} />
          {detail
            ? <span className={styles.action} aria-label={`コメント ${commentCount} 件`}>💬 {commentCount}</span>
            : (
              <Link to={reviewUrl(review.id)} className={`${styles.action} ${styles.countLink}`}
                aria-label={`コメント ${commentCount} 件`}>💬 {commentCount}</Link>
            )}
        </div>
      </div>
      {confirming && (
        <ConfirmDialog title="レビューを削除" message={M[19]} onConfirm={handleDelete}
          onCancel={() => setConfirming(false)} />
      )}
    </article>
  )
}

// 「ネタバレあり」の枠（画面設計書 2.7、RV-11）。感想と写真の代わりに出す
function SpoilerCover({ onReveal }: { onReveal: () => void }) {
  return (
    <div className={styles.spoiler}>
      <span>⚠ ネタバレあり</span>
      <button type="button" className={ui.secondary} onClick={onReveal}>ネタバレを表示</button>
    </div>
  )
}

// 感想。改行をそのまま出し、URL はリンクにしない（RV-7）。一覧では 5 行まで出し、6 行目以降があれば「続きを読む」（RV-6）
function ReviewBody({ review, detail, focusOnMount }: { review: ReviewView, detail: boolean, focusOnMount: boolean }) {
  const ref = useRef<HTMLDivElement & HTMLAnchorElement>(null)
  const [overflowing, setOverflowing] = useState(false)

  // 5 行で切ったときに、隠れた部分があるかを測る
  useLayoutEffect(() => {
    const el = ref.current
    if (el && !detail) setOverflowing(el.scrollHeight > el.clientHeight + 1)
  }, [detail])

  // 「ネタバレを表示」で開いたときは、カーソルを感想の先頭に移す（画面設計書 2.7）
  useEffect(() => {
    if (focusOnMount) ref.current?.focus()
  }, [focusOnMount])

  if (detail) {
    return <div ref={ref} className={styles.body} tabIndex={-1}>{review.body}</div>
  }
  return (
    <>
      {/* 感想を押すと G-7（RV-8） */}
      <Link ref={ref} to={reviewUrl(review.id)} className={`${styles.body} ${styles.clamped}`}>{review.body}</Link>
      {overflowing && <Link to={reviewUrl(review.id)} className={styles.more}>続きを読む</Link>}
    </>
  )
}

// 自分のレビューにだけ出す「…」メニュー（RV-9）
function ReviewMenu({ onEdit, onDelete }: { onEdit: () => void, onDelete: () => void }) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  // メニューの外を押したとき・Esc キーで閉じる
  useEffect(() => {
    if (!open) return
    function handlePointerDown(e: PointerEvent) {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false)
    }
    function handleKeyDown(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', handlePointerDown)
    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown)
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [open])

  function choose(action: () => void) {
    setOpen(false)
    action()
  }

  return (
    <div className={styles.menuWrap} ref={wrapRef}>
      <button type="button" className={styles.menuButton} aria-label="メニュー" aria-haspopup="menu" aria-expanded={open}
        onClick={() => setOpen(!open)}>…</button>
      {open && (
        <div className={styles.menu} role="menu">
          <button type="button" role="menuitem" onClick={() => choose(onEdit)}>編集</button>
          <button type="button" role="menuitem" className={styles.danger} onClick={() => choose(onDelete)}>削除</button>
        </div>
      )}
    </div>
  )
}
