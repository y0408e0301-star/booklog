import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router'
import { ApiError, createComment, deleteComment, fetchComments, profileUrl, type CommentView } from '../api'
import { M } from '../messages'
import { fullTime, relativeTime } from '../time'
import { normalizeBody, remainingCommentChars } from '../validation'
import Avatar from './Avatar'
import styles from './Comments.module.css'
import ConfirmDialog from './ConfirmDialog'
import { isSubmitKey, remainingText, useAutoGrow } from './textarea'
import { useToast } from './ToastContext'
import ui from './ui.module.css'

type Props = {
  reviewId: number
  // 見出しの件数（カードのコメント数と同じ）
  count: number
  // 自分が付けた・消した分だけ、コメント数を増減する（TC-06-4）
  onCountChange: (delta: number) => void
  // レビューが削除されていた（M-20 を出して、画面を削除されたレビューの表示にする）
  onReviewDeleted: () => void
}

type List =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'loaded', items: CommentView[], nextCursor: string | null }

// ID の小さい順（古い順）にし、同じコメントは 1 つにする。
// まだ読んでいない続きがあるときに自分がコメントすると、続きを読んだときに同じコメントがまた返ってくるため
function merge(current: CommentView[], added: CommentView[]): CommentView[] {
  const byId = new Map([...current, ...added].map((c) => [c.id, c]))
  return [...byId.values()].toSorted((a, b) => a.id - b.id)
}

// G-7 のコメントの見出し・フォーム・一覧（画面設計書 9 章、FN-06 C-1〜C-5・C-7）
export default function Comments({ reviewId, count, onCountChange, onReviewDeleted }: Props) {
  const toast = useToast()
  const headingId = useId()
  const [list, setList] = useState<List>({ status: 'loading' })
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)
  const [deleting, setDeleting] = useState<CommentView | null>(null)

  useEffect(() => {
    let active = true
    fetchComments(reviewId).then(
      (page) => active && setList({ status: 'loaded', items: page.items, nextCursor: page.nextCursor }),
      () => active && setList({ status: 'failed' }))
    return () => {
      active = false
    }
  }, [reviewId])

  async function reload() {
    setList({ status: 'loading' })
    try {
      const page = await fetchComments(reviewId)
      setList({ status: 'loaded', items: page.items, nextCursor: page.nextCursor })
    } catch {
      setList({ status: 'failed' })
    }
  }

  // 「もっと見る」で次の 50 件（C-3）
  async function loadMore() {
    if (list.status !== 'loaded' || list.nextCursor === null || loadingMore) return
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const page = await fetchComments(reviewId, list.nextCursor)
      setList((prev) => prev.status === 'loaded'
        ? { status: 'loaded', items: merge(prev.items, page.items), nextCursor: page.nextCursor }
        : prev)
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }

  // 一覧の一番下に足す（C-1）
  function added(comment: CommentView) {
    setList((prev) => prev.status === 'loaded' ? { ...prev, items: merge(prev.items, [comment]) } : prev)
    onCountChange(1)
  }

  function removed(id: number) {
    setList((prev) => prev.status === 'loaded' ? { ...prev, items: prev.items.filter((c) => c.id !== id) } : prev)
    onCountChange(-1)
  }

  async function handleDelete() {
    if (!deleting) return
    try {
      await deleteComment(deleting.id)
      removed(deleting.id)
    } catch (e) {
      // すでに削除されていたときは、一覧から消すだけでメッセージは出さない（FN-06 9 章）
      if (e instanceof ApiError && e.code === 'COMMENT_NOT_FOUND') removed(deleting.id)
      else toast(M[30])
    }
    setDeleting(null)
  }

  return (
    <section aria-labelledby={headingId}>
      <h2 id={headingId} className={styles.heading}>コメント {count} 件</h2>
      <CommentForm reviewId={reviewId} onAdded={added} onReviewDeleted={onReviewDeleted} />
      {list.status === 'loading' && <p className={styles.state}>読み込み中…</p>}
      {list.status === 'failed' && (
        <div className={styles.state} role="alert">
          <p>{M[29]}</p>
          <button type="button" className={ui.secondary} onClick={reload}>再読み込み</button>
        </div>
      )}
      {list.status === 'loaded' && (
        <>
          <ul className={styles.list} aria-label="コメントの一覧">
            {list.items.map((comment) => (
              <CommentItem key={comment.id} comment={comment} onDelete={() => setDeleting(comment)} />
            ))}
          </ul>
          {list.nextCursor !== null && moreFailed && (
            <div className={styles.state} role="alert">
              <p>{M[29]}</p>
              <button type="button" className={ui.secondary} onClick={loadMore}>再読み込み</button>
            </div>
          )}
          {list.nextCursor !== null && !moreFailed && (
            <div className={styles.more}>
              <button type="button" className={ui.secondary} onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? '読み込み中…' : 'もっと見る'}
              </button>
            </div>
          )}
        </>
      )}
      {deleting && (
        <ConfirmDialog title="コメントを削除" message={M[24]} onConfirm={handleDelete} onCancel={() => setDeleting(null)} />
      )}
    </section>
  )
}

// 1 つのコメント（C-3〜C-5・C-7）。本文の改行はそのまま出し、URL はリンクにしない
function CommentItem({ comment, onDelete }: { comment: CommentView, onDelete: () => void }) {
  return (
    <li className={styles.comment} aria-label={`${comment.author.displayName}のコメント`}>
      <Link to={profileUrl(comment.author.username)} className={styles.avatar} tabIndex={-1} aria-hidden="true">
        <Avatar displayName={comment.author.displayName} size={32} />
      </Link>
      <div className={styles.main}>
        <div className={styles.head}>
          {/* 名前を押すと G-8（C-7） */}
          <Link to={profileUrl(comment.author.username)} className={styles.names}>
            <span className={styles.name}>{comment.author.displayName}</span>
            <span className={styles.username}>@{comment.author.username}</span>
          </Link>
          <time className={styles.time} dateTime={comment.createdAt} title={fullTime(comment.createdAt)}>
            · {relativeTime(comment.createdAt)}
          </time>
          {comment.mine && (
            <button type="button" className={styles.delete} onClick={onDelete}>削除</button>
          )}
        </div>
        <div className={styles.body}>{comment.body}</div>
      </div>
    </li>
  )
}

// コメントのフォーム（C-1・C-2）。0 文字・500 文字を超えたときは押せない
function CommentForm({ reviewId, onAdded, onReviewDeleted }: {
  reviewId: number
  onAdded: (comment: CommentView) => void
  onReviewDeleted: () => void
}) {
  const toast = useToast()
  const countId = useId()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  // 素早く 2 回押しても 1 回だけ送る（state は次の描画まで変わらないため、ref でも確かめる）
  const sendingRef = useRef(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  useAutoGrow(textareaRef, 10)

  const remaining = remainingCommentChars(body)
  const canSubmit = !sending && remaining >= 0 && normalizeBody(body) !== ''

  async function submit() {
    if (!canSubmit || sendingRef.current) return
    sendingRef.current = true
    setSending(true)
    try {
      onAdded(await createComment(reviewId, normalizeBody(body)))
      setBody('')
    } catch (e) {
      if (e instanceof ApiError && e.code === 'REVIEW_NOT_FOUND') {
        toast(M[20])
        onReviewDeleted()
      } else {
        // 入力した本文は残す（FN-06 9 章）
        toast(M[30])
      }
    } finally {
      sendingRef.current = false
      setSending(false)
    }
  }

  // Enter キーで改行し、Ctrl＋Enter で送る（画面設計書 2.12）
  function handleKeyDown(e: KeyboardEvent) {
    if (isSubmitKey(e)) {
      e.preventDefault()
      void submit()
    }
  }

  return (
    <div className={styles.form}>
      <textarea ref={textareaRef} rows={2} placeholder="コメントを書く" aria-label="コメントの本文"
        aria-describedby={countId} value={body} onChange={(e) => setBody(e.target.value)} onKeyDown={handleKeyDown} />
      <div className={styles.actions}>
        <span id={countId} className={remaining < 0 ? `${styles.count} ${styles.over}` : styles.count}>
          {remainingText(remaining)}
        </span>
        <button type="button" className={ui.primary} onClick={() => void submit()} disabled={!canSubmit}>
          {sending ? '送信中…' : 'コメントする'}
        </button>
      </div>
    </div>
  )
}
