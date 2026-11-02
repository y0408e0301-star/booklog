import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { ApiError, bookUrl, importBook, searchBooks, type BookSearchItem } from '../api'
import BookCover from '../components/BookCover'
import { useToast } from '../components/ToastContext'
import ui from '../components/ui.module.css'
import { publisherAndDate } from '../format'
import { M, m13 } from '../messages'
import styles from './BookSearchPage.module.css'

type List = {
  items: BookSearchItem[]
  // 読み込んだ最後のページ（1〜5）
  page: number
  hasNext: boolean
  status: 'loading' | 'loaded' | 'failed'
  // 失敗したときに出すメッセージ（M-14 か M-29）
  error: string | null
}

const initialList: List = { items: [], page: 0, hasNext: false, status: 'loading', error: null }

// Google Books API が使えないとき（503）は M-14、それ以外の失敗は M-29
function loadError(err: unknown): string {
  return err instanceof ApiError && err.code === 'BOOK_SEARCH_UNAVAILABLE' ? M[14] : M[29]
}

// G-4 書籍検索の結果画面（画面設計書 6 章、FN-02 B-1〜B-10）。検索した文字は URL の ?q= に入れるので、
// 開き直したり、G-5 から戻ってきたりしても同じ結果を出す。20 件ずつ、「もっと見る」で次の 20 件を足す
export default function BookSearchPage() {
  const [params] = useSearchParams()
  const q = (params.get('q') ?? '').trim()
  const navigate = useNavigate()
  const toast = useToast()
  const [list, setList] = useState<List>(initialList)
  // 取り込んでいる本の Google Books の ID。その間はほかの行も押せなくする
  const [opening, setOpening] = useState<string | null>(null)
  // 検索し直した後に、前の検索の応答が遅れて届いても使わない
  const requestRef = useRef(0)

  // page が 1 なら最初の 20 件、そうでなければ続きを後ろに足す
  const load = useCallback(async (page: number) => {
    const request = ++requestRef.current
    setList((prev) => (page === 1 ? initialList : { ...prev, status: 'loading', error: null }))
    try {
      const result = await searchBooks(q, page)
      if (request !== requestRef.current) return
      setList((prev) => ({
        items: page === 1 ? result.items : [...prev.items, ...result.items],
        page,
        hasNext: result.hasNext,
        status: 'loaded',
        error: null,
      }))
    } catch (err) {
      if (request === requestRef.current) setList((prev) => ({ ...prev, status: 'failed', error: loadError(err) }))
    }
  }, [q])

  // 開いたとき・検索し直したときは最初の 20 件から出す。検索する文字がなければ探さない（B-1）
  useEffect(() => {
    async function start() {
      if (q !== '') await load(1)
    }
    void start()
  }, [q, load])

  // 本を取り込んで G-5 を開く（B-5・B-6）。見つからない本は M-15 を出し、一覧はそのまま
  async function open(item: BookSearchItem) {
    setOpening(item.googleBooksId)
    try {
      const id = await importBook(item.googleBooksId)
      navigate(bookUrl(id))
    } catch (err) {
      toast(err instanceof ApiError ? err.message : M[30])
      setOpening(null)
    }
  }

  if (q === '') return null

  const firstLoad = list.page === 0

  return (
    <>
      <div className={styles.head}>
        <h1>「{q}」の検索結果</h1>
        {firstLoad && list.status === 'loading' && <p className={styles.sub}>検索中…</p>}
      </div>
      <ul className={styles.list} aria-label="検索結果">
        {list.items.map((item, i) => {
          const sub = publisherAndDate(item.publisher, item.publishedDate)
          return (
            // 同じ本が別のページにも出ることがあるので、順番も key に入れる
            <li key={`${i}:${item.googleBooksId}`}>
              <button type="button" className={styles.row} disabled={opening !== null}
                aria-busy={opening === item.googleBooksId || undefined} onClick={() => void open(item)}>
                <BookCover title={item.title} thumbnailUrl={item.thumbnailUrl} width={64} />
                <span className={styles.info}>
                  <span className={styles.title}>{item.title}</span>
                  {item.authors.length > 0 && <span className={styles.authors}>{item.authors.join('、')}</span>}
                  {sub && <span className={styles.sub}>{sub}</span>}
                  {opening === item.googleBooksId && <span className={styles.opening}>開いています…</span>}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
      {list.status === 'loaded' && list.items.length === 0 && <p className={ui.empty}>{m13(q)}</p>}
      {list.status === 'failed' && (
        // 「もっと見る」で失敗したときは、出した結果は残し、ボタンの場所に出す
        <div className={styles.state} role="alert">
          <p>{list.error}</p>
          <button type="button" className={ui.secondary} onClick={() => void load(firstLoad ? 1 : list.page + 1)}>
            再読み込み
          </button>
        </div>
      )}
      {!firstLoad && list.hasNext && list.status !== 'failed' && (
        <div className={styles.state}>
          <button type="button" className={ui.secondary} disabled={list.status === 'loading' || opening !== null}
            onClick={() => void load(list.page + 1)}>
            {list.status === 'loading' ? '読み込み中…' : 'もっと見る'}
          </button>
        </div>
      )}
    </>
  )
}
