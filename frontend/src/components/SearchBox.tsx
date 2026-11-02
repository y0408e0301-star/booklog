import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { bookSearchUrl } from '../api'
import { M } from '../messages'
import { limitChars, SEARCH_QUERY_MAX } from '../validation'
import styles from './SearchBox.module.css'
import { useToast } from './ToastContext'

type Props = {
  // G-4 では検索した文字を入れておく（B-10）
  initialQuery: string
  // 600px 未満で虫めがねのボタンを押したときに、入力欄にカーソルを移す
  autoFocus: boolean
  className: string
}

// ヘッダーの書籍の検索欄（FN-02 B-1、画面設計書 2.6）。
// Enter キーか虫めがねで G-4 を開く。トリムして 0 文字のときは M-12 を出し、検索しない
export default function SearchBox({ initialQuery, autoFocus, className }: Props) {
  const navigate = useNavigate()
  const toast = useToast()
  const [query, setQuery] = useState(initialQuery)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (autoFocus) inputRef.current?.focus()
  }, [autoFocus])

  function submit(e: FormEvent) {
    e.preventDefault()
    const q = query.trim()
    if (q === '') {
      toast(M[12])
      return
    }
    navigate(bookSearchUrl(q))
  }

  return (
    <search className={`${styles.wrap} ${className}`}>
      <form className={styles.box} onSubmit={submit}>
        <input ref={inputRef} type="search" className={styles.input} placeholder="ISBN・書名で本を探す"
          aria-label="本を探す文字" enterKeyHint="search"
          value={query} onChange={(e) => setQuery(limitChars(e.target.value, SEARCH_QUERY_MAX))} />
        <button type="submit" className={styles.button} aria-label="本を探す">🔍</button>
      </form>
    </search>
  )
}
