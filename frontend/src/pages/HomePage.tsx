import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { fetchTimeline, type ReviewView, type Tab } from '../api'
import ReviewCard from '../components/ReviewCard'
import ui from '../components/ui.module.css'
import { M } from '../messages'
import styles from './HomePage.module.css'

const TABS: { tab: Tab, label: string }[] = [
  { tab: 'following', label: 'フォロー中' },
  { tab: 'all', label: 'すべて' },
]

// 最後に選んだタブを記録する場所（T-3）
const TAB_KEY = 'bl.tab'

const isTab = (value: string | null): value is Tab => value === 'following' || value === 'all'

// localStorage が使えないブラウザ（プライベートブラウズなど）では、記録せずに「フォロー中」で動く
function storedTab(): Tab {
  try {
    const tab = localStorage.getItem(TAB_KEY)
    return isTab(tab) ? tab : 'following'
  } catch {
    return 'following'
  }
}

function storeTab(tab: Tab) {
  try {
    localStorage.setItem(TAB_KEY, tab)
  } catch {
    // 記録できなくても表示には困らない
  }
}

type List = {
  items: ReviewView[]
  // 続きを読むときに送る。null なら最後まで読んだ
  nextCursor: string | null
  status: 'loading' | 'loaded' | 'failed'
}

const initialList: List = { items: [], nextCursor: null, status: 'loading' }

// G-3 タイムライン画面（画面設計書 5 章、FN-09 T-1〜T-8）。「フォロー中」「すべて」のタブとレビューの一覧
export default function HomePage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const urlTab = searchParams.get('tab')
  const tab: Tab = isTab(urlTab) ? urlTab : storedTab()
  const [list, setList] = useState<List>(initialList)
  // タブを切り替えた後に、前のタブの応答が遅れて届いても使わない
  const requestRef = useRef(0)
  const sentinelRef = useRef<HTMLDivElement>(null)

  // URL の ?tab= を、表示しているタブに合わせる。表示したタブはブラウザに記録する（T-3）
  useEffect(() => {
    if (urlTab !== tab) setSearchParams({ tab }, { replace: true })
    storeTab(tab)
  }, [tab, urlTab, setSearchParams])

  // cursor が null なら最新の 20 件から出し直し、そうでなければ続きを後ろに足す（T-2）
  const load = useCallback(async (cursor: string | null) => {
    const request = ++requestRef.current
    setList((prev) => (cursor === null ? initialList : { ...prev, status: 'loading' }))
    try {
      const page = await fetchTimeline(tab, cursor)
      if (request !== requestRef.current) return
      setList((prev) => ({
        items: cursor === null ? page.items : [...prev.items, ...page.items],
        nextCursor: page.nextCursor,
        status: 'loaded',
      }))
    } catch {
      if (request !== requestRef.current) return
      setList((prev) => ({ ...prev, status: 'failed' }))
    }
  }, [tab])

  // 開いたとき・タブが変わったときは最新の 20 件から出す
  useEffect(() => {
    async function start() {
      await load(null)
    }
    void start()
  }, [load])

  // 一覧の一番下が見えたら続きを読む（T-2）
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

  // 違うタブなら URL が変わって読み込み直す。表示中のタブを押し直したときも、最新の 20 件から出し直す（T-6）
  function selectTab(next: Tab) {
    if (next === tab) void load(null)
    else setSearchParams({ tab: next })
  }

  // 「再読み込み」：失敗したのと同じ読み込みをやり直す（T-8）
  function retry() {
    void load(list.items.length === 0 ? null : list.nextCursor)
  }

  const { items, status } = list
  const finished = status === 'loaded' && list.nextCursor === null

  return (
    <>
      <div className={styles.tabs} role="tablist">
        {TABS.map(({ tab: t, label }) => (
          <button key={t} type="button" role="tab" aria-selected={t === tab}
            className={t === tab ? `${styles.tab} ${styles.active}` : styles.tab} onClick={() => selectTab(t)}>
            {label}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={TABS.find((t) => t.tab === tab)?.label}>
        {items.map((review) => (
          <ReviewCard key={review.id} review={review} showBook
            onDelete={(id) => setList((prev) => ({ ...prev, items: prev.items.filter((r) => r.id !== id) }))} />
        ))}
        {finished && items.length === 0 && (tab === 'following'
          ? (
            // 自分のレビューもなく、誰もフォローしていない（T-4）
            <div className={`${ui.empty} ${styles.empty}`}>
              <p>{M[25]}</p>
              <button type="button" className={ui.primary} onClick={() => selectTab('all')}>「すべて」タブを開く</button>
            </div>
          )
          : <p className={ui.empty}>まだレビューがありません。本を探して、最初のレビューを書いてみましょう。</p>)}
        {finished && items.length > 0 && <p className={styles.status}>これ以上のレビューはありません</p>}
        {status === 'loading' && <output className={styles.status}>読み込み中…</output>}
        {status === 'failed' && (
          <div className={styles.status} role="alert">
            <p>{M[29]}</p>
            <button type="button" className={ui.secondary} onClick={retry}>再読み込み</button>
          </div>
        )}
        <div ref={sentinelRef} />
      </div>
    </>
  )
}
