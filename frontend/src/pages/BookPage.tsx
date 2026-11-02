import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useParams } from 'react-router'
import { ApiError, fetchBook, type BookDetail } from '../api'
import BookCover from '../components/BookCover'
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
// 平均評価・レビューの一覧（1-4）と読書ステータス（1-7）は、その機能を作るときに足す
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
  return <BookInfo key={result.book.id} book={result.book} />
}

// 本の情報（BK-1・BK-2）。ない項目は行ごと出さない
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
        </div>
      </div>
      {book.description && <Description text={book.description} />}
    </section>
  )
}

// 紹介文は 5 行まで出し、6 行目以降があるときは「続きを読む」で全部出す（BK-2）。
// 本が替わると BookInfo ごと作り直すので、紹介文が替わったときも測り直す
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
