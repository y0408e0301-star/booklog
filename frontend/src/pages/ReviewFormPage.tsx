import { useEffect, useId, useMemo, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  ApiError, bookUrl, createReview, editReview, fetchBook, fetchReview, type BookDetail, type ReviewView,
} from '../api'
import BookCover from '../components/BookCover'
import StarInput from '../components/StarInput'
import { isSubmitKey, remainingText, useAutoGrow } from '../components/textarea'
import { useToast } from '../components/ToastContext'
import ui from '../components/ui.module.css'
import { M } from '../messages'
import { addPhotos, normalizeBody, PHOTO_TYPES, PHOTOS_MAX, remainingReviewChars } from '../validation'
import styles from './ReviewFormPage.module.css'

type Loaded = { book: BookDetail, review: ReviewView | null }

type State =
  | { status: 'loading' }
  | { status: 'loaded', data: Loaded }
  | { status: 'notFound' }
  | { status: 'failed' }

// 本と、もう書いていればそのレビューを読む（RV-1。FN-03 6 章）
async function loadForm(bookId: number): Promise<Loaded> {
  const book = await fetchBook(bookId)
  const review = book.myReviewId === null ? null : await fetchReview(book.myReviewId)
  return { book, review }
}

// G-6 レビューの投稿・編集画面（画面設計書 8 章、FN-03 RV-1〜RV-4・RV-10、FN-04 PH-1〜PH-5）
export default function ReviewFormPage() {
  const { id: rawId = '' } = useParams()
  const bookId = /^[1-9][0-9]{0,15}$/.test(rawId) ? Number(rawId) : null
  // 読み込み直した回数（「再読み込み」と、M-18 で編集の状態で開き直すとき）。変わったら読み込む部品ごと作り直す
  const [attempt, setAttempt] = useState(0)
  if (bookId === null) return <p className={ui.empty}>{M[15]}</p>
  return <ReviewFormLoader key={`${bookId}:${attempt}`} bookId={bookId} onReload={() => setAttempt((n) => n + 1)} />
}

function ReviewFormLoader({ bookId, onReload }: { bookId: number, onReload: () => void }) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const data = await loadForm(bookId)
        if (active) setState({ status: 'loaded', data })
      } catch (err) {
        if (active) setState({ status: err instanceof ApiError && err.status === 404 ? 'notFound' : 'failed' })
      }
    }
    void load()
    return () => {
      active = false
    }
  }, [bookId])

  if (state.status === 'notFound') return <p className={ui.empty}>{M[15]}</p>
  if (state.status === 'loading') return <p className={ui.empty}>読み込み中…</p>
  if (state.status === 'failed') {
    return (
      <div className={ui.empty} role="alert">
        <p>{M[29]}</p>
        <button type="button" className={ui.secondary} onClick={onReload}>再読み込み</button>
      </div>
    )
  }
  const { book, review } = state.data
  return <ReviewForm book={book} review={review} onExists={onReload} />
}

type Errors = { rating?: string, body?: string }

// 写真が添えられなかったときの API のエラー（FN-04 9 章）。message は M-22・M-23
const PHOTO_ERRORS = ['TOO_MANY_PHOTOS', 'PHOTO_TOO_LARGE', 'PHOTO_TYPE_INVALID']

function ReviewForm({ book, review, onExists }: {
  book: BookDetail
  // 編集のときは今のレビュー。新しく書くときは null
  review: ReviewView | null
  // 同じ本のレビューがすでにあった（M-18）ので、編集の状態で開き直す
  onExists: () => void
}) {
  const navigate = useNavigate()
  const toast = useToast()
  const id = useId()
  const [rating, setRating] = useState<number | null>(review?.rating ?? null)
  const [body, setBody] = useState(review?.body ?? '')
  const [spoiler, setSpoiler] = useState(review?.spoiler ?? false)
  // 編集で残す今の写真と、足す写真（選んだ順）。保存すると、残す写真の後ろに足す写真が並ぶ（FN-04 5 章の 5）
  const [kept, setKept] = useState(review?.photos ?? [])
  const [added, setAdded] = useState<File[]>([])
  const [errors, setErrors] = useState<Errors>({})
  const [sending, setSending] = useState(false)
  // 素早く 2 回押しても 1 回だけ送る（state は次の描画まで変わらないため、ref でも確かめる）
  const sendingRef = useRef(false)
  const firstStarRef = useRef<HTMLInputElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLTextAreaElement>(null)
  useAutoGrow(bodyRef, 20)

  // 足す写真のプレビューに使う URL。写真を外したら・画面を離れたら解放する
  const previews = useMemo(() => added.map((file) => URL.createObjectURL(file)), [added])
  useEffect(() => () => previews.forEach((url) => URL.revokeObjectURL(url)), [previews])

  // 開いたときは、星のボタンの 1 つ目にカーソルを置く（画面設計書 8 章）
  useEffect(() => {
    firstStarRef.current?.focus()
  }, [])

  const editing = review !== null
  const remaining = remainingReviewChars(body)
  const changed = !editing
    || rating !== review.rating || normalizeBody(body) !== review.body || spoiler !== review.spoiler
    || kept.length !== review.photos.length || added.length > 0
  // 感想が 2,000 文字を超えたとき、編集で何も変えていないとき、送信中は押せない（画面設計書 8 章）
  const canSubmit = remaining >= 0 && changed && !sending

  // 「←」・「キャンセル」：入力した内容は捨てて G-5 に戻る
  function back() {
    navigate(bookUrl(book.id))
  }

  async function submit() {
    if (!canSubmit || sendingRef.current) return
    const found: Errors = {}
    if (rating === null) found.rating = M[16]
    if (normalizeBody(body) === '') found.body = M[17]
    setErrors(found)
    if (rating === null || found.body) {
      if (found.rating) firstStarRef.current?.focus()
      else bodyRef.current?.focus()
      return
    }
    sendingRef.current = true
    setSending(true)
    try {
      const input = { rating, body: normalizeBody(body), spoiler, photos: added }
      if (editing) await editReview(review.id, input, kept.map((photo) => photo.id))
      else await createReview(book.id, input)
      // 投稿・保存したら G-5 に戻る。G-6 には戻らないよう、履歴を置き換える（RV-2・RV-10）
      navigate(bookUrl(book.id), { replace: true })
    } catch (err) {
      sendingRef.current = false
      setSending(false)
      if (err instanceof ApiError && err.code === 'REVIEW_EXISTS') {
        // 別のブラウザで先に書いていた：M-18 を出し、編集の状態で開き直す（FN-03 9 章）
        toast(M[18])
        onExists()
      } else if (err instanceof ApiError && (err.field === 'rating' || err.field === 'body')) {
        setErrors({ [err.field]: err.message })
      } else if (err instanceof ApiError && PHOTO_ERRORS.includes(err.code)) {
        // 画面の確認をすり抜けた写真（中身が画像でないなど）。API の M-22・M-23 を出し、入力した内容は残す
        toast(err.message)
      } else {
        // 入力した内容と写真は残す（PH-5）
        toast(M[21])
      }
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    void submit()
  }

  // 選んだ写真を後ろに足す（PH-1〜PH-3）。同じファイルをもう一度選べるよう、選んだ後は入力欄を空にする
  function pick(e: ChangeEvent<HTMLInputElement>) {
    const picked = [...(e.target.files ?? [])]
    e.target.value = ''
    if (picked.length === 0) return
    const result = addPhotos(added, picked, kept.length)
    setAdded(result.photos)
    if (result.error === 'M22') toast(M[22])
    if (result.error === 'M23') toast(M[23])
  }

  // 感想の入力欄では Enter キーで改行し、Ctrl＋Enter で送る（画面設計書 2.12）
  function handleKeyDown(e: KeyboardEvent) {
    if (isSubmitKey(e)) {
      e.preventDefault()
      void submit()
    }
  }

  const title = editing ? 'レビューを編集' : 'レビューを書く'
  return (
    <>
      <div className={styles.head}>
        <button type="button" className={styles.back} aria-label="本のページに戻る" onClick={back}>←</button>
        <h1>{title}</h1>
      </div>
      <form className={styles.form} onSubmit={handleSubmit} noValidate>
        <div className={styles.book}>
          <BookCover title={book.title} thumbnailUrl={book.thumbnailUrl} width={48} />
          <div className={styles.bookInfo}>
            <span className={styles.bookTitle}>{book.title}</span>
            {book.authors.length > 0 && <span className={styles.sub}>{book.authors.join('、')}</span>}
          </div>
        </div>

        <div className={styles.field}>
          <StarInput value={rating} firstRef={firstStarRef} error={errors.rating}
            onChange={(value) => {
              setRating(value)
              setErrors((e) => ({ ...e, rating: undefined }))
            }} />
        </div>

        <div className={styles.field}>
          <label htmlFor={`${id}-body`} className={styles.label}>感想（必須）</label>
          {/* 2,000 文字を超えても入力はできる（あとで削れるように）。超えている間は送れない（画面設計書 2.12） */}
          <textarea id={`${id}-body`} ref={bodyRef} className={errors.body ? `${styles.body} ${styles.invalid}` : styles.body}
            value={body} onKeyDown={handleKeyDown} aria-invalid={errors.body ? true : undefined}
            aria-describedby={`${id}-count${errors.body ? ` ${id}-body-error` : ''}`}
            onChange={(e) => {
              setBody(e.target.value)
              setErrors((prev) => ({ ...prev, body: undefined }))
            }} />
          <div className={styles.bodyFoot}>
            {errors.body && <p className={styles.error} id={`${id}-body-error`}>{errors.body}</p>}
            <span id={`${id}-count`} className={remaining < 0 ? `${styles.count} ${styles.over}` : styles.count}>
              {remainingText(remaining)}
            </span>
          </div>
        </div>

        <div className={styles.field}>
          <label className={styles.check}>
            <input type="checkbox" checked={spoiler} onChange={(e) => setSpoiler(e.target.checked)}
              aria-describedby={`${id}-spoiler-hint`} />
            ネタバレあり
          </label>
          <p className={styles.hint} id={`${id}-spoiler-hint`}>結末や大事な展開に触れているときに付けます</p>
        </div>

        <div className={styles.field}>
          {kept.length + added.length > 0 && (
            <ul className={styles.previews} aria-label="添える写真">
              {kept.map((photo, index) => (
                <PhotoPreview key={`kept-${photo.id}`} url={photo.url} index={index} disabled={sending}
                  onRemove={() => setKept((prev) => prev.filter((p) => p.id !== photo.id))} />
              ))}
              {previews.map((url, index) => (
                <PhotoPreview key={url} url={url} index={kept.length + index} disabled={sending}
                  onRemove={() => setAdded((prev) => prev.filter((_, i) => i !== index))} />
              ))}
            </ul>
          )}
          <input ref={fileRef} type="file" accept={PHOTO_TYPES.join(',')} multiple hidden
            aria-label="添える写真のファイル" onChange={pick} />
          <button type="button" className={ui.secondary} onClick={() => fileRef.current?.click()}
            disabled={sending || kept.length + added.length >= PHOTOS_MAX} aria-describedby={`${id}-photo-hint`}>
            写真を選ぶ
          </button>
          <p className={styles.photoHint} id={`${id}-photo-hint`}>JPEG・PNG・WebP、4 枚まで、1 枚 5MB まで</p>
        </div>

        <div className={styles.buttons}>
          <button type="button" className={ui.secondary} onClick={back} disabled={sending}>キャンセル</button>
          <button type="submit" className={ui.primary} disabled={!canSubmit}>
            {sending ? (editing ? '保存中…' : '投稿中…') : (editing ? '保存する' : '投稿する')}
          </button>
        </div>
      </form>
    </>
  )
}

// 添える写真のプレビュー（80px 四方）。右上の「×」で外す（PH-4）
function PhotoPreview({ url, index, disabled, onRemove }: {
  url: string, index: number, disabled: boolean, onRemove: () => void,
}) {
  return (
    <li className={styles.preview}>
      <img src={url} alt={`添える写真 ${index + 1}`} />
      <button type="button" className={styles.remove} aria-label={`写真 ${index + 1} を外す`} disabled={disabled}
        onClick={onRemove}>×</button>
    </li>
  )
}
