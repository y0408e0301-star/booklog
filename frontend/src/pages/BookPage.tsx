import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { ApiError, fetchBook, fetchBookReviews, reviewFormUrl, type BookDetail, type ReviewView } from '../api'
import BookCover from '../components/BookCover'
import ReviewCard from '../components/ReviewCard'
import Stars from '../components/Stars'
import ui from '../components/ui.module.css'
import { publisherAndDate } from '../format'
import { M } from '../messages'
import styles from './BookPage.module.css'

// 読み込んだ結果。どの本の何回目の読み込みかを持ち、今の本・今の回と違えば「読み込み中」とみなす
type Result = { id: number, attempt: number } & (
  | { status: 'loaded', book: BookDetail }
  | { status: 'notFound' }
  | { status: 'failed' }
)

// G-5 本のページ（画面設計書 7 章、FN-10）。本の情報はデータベースから読み、Google Books API は呼ばない（N-8）。
// 読書ステータスのボタン（1-7）は、その機能を作るときに足す
export default function BookPage() {
  const { id: rawId = '' } = useParams()
  // 数でない ID は、API を呼ばずに「見つからない」にする（BK-7）
  const id = /^[1-9][0-9]{0,15}$/.test(rawId) ? Number(rawId) : null
  // 「再読み込み」を押した回数
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<Result | null>(null)

  useEffect(() => {
    if (id === null) return
    // ほかの本に移った後に、前の本の応答が遅れて届いても使わない
    let active = true
    async function load(bookId: number) {
      try {
        const book = await fetchBook(bookId)
        if (active) setResult({ id: bookId, attempt, status: 'loaded', book })
      } catch (err) {
        const status = err instanceof ApiError && err.status === 404 ? 'notFound' : 'failed'
        if (active) setResult({ id: bookId, attempt, status })
      }
    }
    void load(id)
    return () => {
      active = false
    }
  }, [id, attempt])

  // レビューを削除したら、平均評価・件数・「レビューを書く」を読み直す（一覧はそのまま）
  const refreshBook = useCallback(async () => {
    if (id === null) return
    try {
      const book = await fetchBook(id)
      setResult((prev) => (prev?.id === id && prev.status === 'loaded' ? { ...prev, book } : prev))
    } catch {
      // 読み直せなくても、一覧から消したことには困らない
    }
  }, [id])

  if (id === null) return <p className={ui.empty}>{M[15]}</p>
  if (result === null || result.id !== id || result.attempt !== attempt) {
    return <p className={ui.empty}>読み込み中…</p>
  }
  if (result.status === 'notFound') return <p className={ui.empty}>{M[15]}</p>
  if (result.status === 'failed') {
    return (
      <div className={ui.empty} role="alert">
        <p>{M[29]}</p>
        <button type="button" className={ui.secondary} onClick={() => setAttempt((n) => n + 1)}>再読み込み</button>
      </div>
    )
  }
  return (
    // 本が替わったら、紹介文の開き具合と一覧を作り直す
    <div key={result.book.id}>
      <BookInfo book={result.book} />
      <BookReviews bookId={result.book.id} reviewCount={result.book.reviewCount} onDeleted={refreshBook} />
    </div>
  )
}

// 本の情報（BK-1・BK-2）、平均評価と件数（BK-3）、「レビューを書く」「レビューを編集」（BK-4、RV-1）
function BookInfo({ book }: { book: BookDetail }) {
  const sub = publisherAndDate(book.publisher, book.publishedDate)
  return (
    <section className={styles.book} aria-labelledby="book-title">
      <div className={styles.top}>
        <BookCover title={book.title} thumbnailUrl={book.thumbnailUrl} width={128} />
        <div className={styles.info}>
          <h1 id="book-title" className={styles.title}>{book.title}</h1>
          {book.authors.length > 0 && <p>{book.authors.join('、')}</p>}
          {sub && <p className={styles.sub}>{sub}</p>}
          {book.isbn13 && <p className={styles.sub}>ISBN {book.isbn13}</p>}
          <p className={styles.rating}>
            {book.averageRating === null
              ? <span aria-label="平均評価なし">—</span>
              : <Stars value={book.averageRating} size={24} average />}
            <span className={styles.sub}>レビュー {book.reviewCount} 件</span>
          </p>
        </div>
      </div>
      {book.description && <Description text={book.description} />}
      <div className={styles.actions}>
        {book.myReviewId === null
          ? <Link to={reviewFormUrl(book.id)} className={`${ui.primary} ${styles.write}`}>レビューを書く</Link>
          : <Link to={reviewFormUrl(book.id)} className={`${ui.secondary} ${styles.write}`}>レビューを編集</Link>}
      </div>
    </section>
  )
}

// 紹介文は 5 行まで出し、6 行目以降があるときは「続きを読む」で全部出す（BK-2）。
// 本が替わると BookPage が作り直すので、紹介文が替わったときも測り直す
function Description({ text }: { text: string }) {
  const ref = useRef<HTMLParagraphElement>(null)
  const [expanded, setExpanded] = useState(false)
  const [overflowing, setOverflowing] = useState(false)

  // 5 行で切ったときに、隠れた部分があるかを測る
  useLayoutEffect(() => {
    const el = ref.current
    if (el && !expanded) setOverflowing(el.scrollHeight > el.clientHeight + 1)
  }, [expanded])

  return (
    <div className={styles.description}>
      <p ref={ref} id="book-description" className={expanded ? undefined : styles.clamped}>{text}</p>
      {(overflowing || expanded) && (
        <button type="button" className={styles.more} aria-expanded={expanded} aria-controls="book-description"
          onClick={() => setExpanded((v) => !v)}>
          {expanded ? '閉じる' : '続きを読む'}
        </button>
      )}
    </div>
  )
}

type List = {
  items: ReviewView[]
  // 続きを読むときに送る。null なら最後まで読んだ
  nextCursor: string | null
  status: 'loading' | 'loaded' | 'failed'
}

const initialList: List = { items: [], nextCursor: null, status: 'loading' }

// その本のレビューの一覧（BK-5・BK-6）。新しい順に 20 件ずつ、一番下まで見たら次の 20 件を足す。本は出さない
function BookReviews({ bookId, reviewCount, onDeleted }: {
  bookId: number
  reviewCount: number
  onDeleted: () => Promise<void>
}) {
  const [list, setList] = useState<List>(initialList)
  const sentinelRef = useRef<HTMLDivElement>(null)

  // cursor が null なら最新の 20 件から、そうでなければ続きを後ろに足す
  const load = useCallback(async (cursor: string | null) => {
    setList((prev) => (cursor === null ? initialList : { ...prev, status: 'loading' }))
    try {
      const page = await fetchBookReviews(bookId, cursor)
      setList((prev) => ({
        items: cursor === null ? page.items : [...prev.items, ...page.items],
        nextCursor: page.nextCursor,
        status: 'loaded',
      }))
    } catch {
      setList((prev) => ({ ...prev, status: 'failed' }))
    }
  }, [bookId])

  useEffect(() => {
    async function start() {
      await load(null)
    }
    void start()
  }, [load])

  // 一覧の一番下が見えたら続きを読む
  const canLoadMore = list.status === 'loaded' && list.nextCursor !== null
  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!canLoadMore || !sentinel) return
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void load(list.nextCursor)
    })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [canLoadMore, list.nextCursor, load])

  function removeReview(id: number) {
    setList((prev) => ({ ...prev, items: prev.items.filter((r) => r.id !== id) }))
    void onDeleted()
  }

  const { items, status } = list
  return (
    <section aria-labelledby="book-reviews">
      <h2 id="book-reviews" className={styles.reviewsHead}>レビュー（{reviewCount} 件）</h2>
      {items.map((review) => (
        <ReviewCard key={review.id} review={review} showBook={false} onDelete={removeReview} />
      ))}
      {status === 'loaded' && items.length === 0 && <p className={ui.empty}>{M[26]}</p>}
      {status === 'loading' && <output className={styles.status}>読み込み中…</output>}
      {status === 'failed' && (
        <div className={styles.status} role="alert">
          <p>{M[29]}</p>
          <button type="button" className={ui.secondary}
            onClick={() => void load(items.length === 0 ? null : list.nextCursor)}>
            再読み込み
          </button>
        </div>
      )}
      <div ref={sentinelRef} />
    </section>
  )
}
