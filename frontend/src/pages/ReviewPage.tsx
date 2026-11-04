import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { ApiError, bookUrl, fetchReview, type ReviewView } from '../api'
import Comments from '../components/Comments'
import ReviewCard from '../components/ReviewCard'
import cardStyles from '../components/ReviewCard.module.css'
import ui from '../components/ui.module.css'
import { M } from '../messages'
import styles from './ReviewFormPage.module.css'

type Result = { id: number, attempt: number } & (
  | { status: 'loaded', review: ReviewView }
  | { status: 'deleted' }
  | { status: 'failed' }
)

// G-7 レビューの詳細画面（画面設計書 9 章、FN-03 RV-6・RV-13、FN-06）。感想は全部出し、下にコメントを出す
export default function ReviewPage() {
  const { id: rawId = '' } = useParams()
  const id = /^[1-9][0-9]{0,15}$/.test(rawId) ? Number(rawId) : null
  const navigate = useNavigate()
  const location = useLocation()
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<Result | null>(null)

  useEffect(() => {
    if (id === null) return
    let active = true
    async function load(reviewId: number) {
      try {
        const review = await fetchReview(reviewId)
        if (active) setResult({ id: reviewId, attempt, status: 'loaded', review })
      } catch (err) {
        const status = err instanceof ApiError && err.status === 404 ? 'deleted' : 'failed'
        if (active) setResult({ id: reviewId, attempt, status })
      }
    }
    void load(id)
    return () => {
      active = false
    }
  }, [id, attempt])

  const review = result?.status === 'loaded' && result.id === id ? result.review : null

  // 「←」：前の画面に戻る。この画面を直接開いたときは、その本の G-5（画面設計書 9 章）
  function back() {
    if (location.key !== 'default') navigate(-1)
    else if (review) navigate(bookUrl(review.book.id))
    else navigate('/')
  }

  function content() {
    // 削除されたレビュー（RV-13）。ID の形が違うときも同じ
    if (id === null) return <p className={ui.empty}>{M[20]}</p>
    if (result === null || result.id !== id || result.attempt !== attempt) {
      return <p className={ui.empty}>読み込み中…</p>
    }
    if (result.status === 'deleted') return <p className={ui.empty}>{M[20]}</p>
    if (result.status === 'failed') {
      return (
        <div className={ui.empty} role="alert">
          <p>{M[29]}</p>
          <button type="button" className={ui.secondary} onClick={() => setAttempt((n) => n + 1)}>再読み込み</button>
        </div>
      )
    }
    // 削除したらその本の G-5 に移る（FN-03 3.3）。コメントを付けようとしたら削除されていたときは M-20 を出す
    return (
      <ReviewDetail key={result.review.id} review={result.review}
        onDelete={() => navigate(bookUrl(result.review.book.id), { replace: true })}
        onDeleted={() => setResult({ id: result.id, attempt, status: 'deleted' })} />
    )
  }

  return (
    <>
      <div className={styles.head}>
        <button type="button" className={styles.back} aria-label="戻る" onClick={back}>←</button>
        <h1>レビュー</h1>
      </div>
      {content()}
    </>
  )
}

// カードとコメント。ネタバレありのレビューでは、「ネタバレを表示」を押すまでコメントの見出し・フォーム・一覧もまとめて伏せる（C-6）
function ReviewDetail({ review, onDelete, onDeleted }: {
  review: ReviewView
  onDelete: () => void
  onDeleted: () => void
}) {
  // ネタバレを開いたか。この画面を開いている間だけ覚える（RV-12）
  const [revealed, setRevealed] = useState(false)
  // 自分が付けた・消した分（カードと見出しのコメント数に足す）
  const [delta, setDelta] = useState(0)
  const commentCount = review.commentCount + delta
  const hidden = review.spoiler && !revealed

  return (
    <>
      <ReviewCard review={review} showBook detail onDelete={onDelete} revealed={revealed}
        onReveal={() => setRevealed(true)} commentCount={commentCount} />
      {hidden
        ? (
          <div className={styles.commentsCover}>
            <div className={cardStyles.spoiler}><span>⚠ ネタバレあり</span></div>
          </div>
        )
        : (
          <Comments reviewId={review.id} count={commentCount} onCountChange={(n) => setDelta((d) => d + n)}
            onReviewDeleted={onDeleted} />
        )}
    </>
  )
}
