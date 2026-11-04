import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError, fetchFollowList, followListUrl, profileUrl, type FollowListKind, type UserSummary } from '../api'
import ui from '../components/ui.module.css'
import UserRow from '../components/UserRow'
import { M } from '../messages'
import tabStyles from './HomePage.module.css'
import styles from './ReviewFormPage.module.css'

const TABS: { kind: FollowListKind, label: string, empty: string }[] = [
  { kind: 'following', label: 'フォロー中', empty: 'まだ誰もフォローしていません。' },
  { kind: 'followers', label: 'フォロワー', empty: 'まだフォロワーはいません。' },
]

type List = {
  items: UserSummary[]
  // 続きを読むときに送る。null なら最後まで読んだ
  nextCursor: string | null
  status: 'loading' | 'loaded' | 'failed' | 'notFound'
}

const initialList: List = { items: [], nextCursor: null, status: 'loading' }

// G-10 フォロー中・フォロワーの一覧画面（画面設計書 12 章、FN-07 F-5〜F-7）。
// 見出しの表示名は、ユーザーの情報の API（FN-11）ができる開発計画書 1-11 で出す。それまでは @ユーザー ID を出す
export default function FollowListPage({ kind }: { kind: FollowListKind }) {
  const { username = '' } = useParams()
  // ユーザーかタブが替わったら一覧を作り直す
  return <FollowList key={`${username}:${kind}`} username={username} kind={kind} />
}

function FollowList({ username, kind }: { username: string, kind: FollowListKind }) {
  const navigate = useNavigate()
  const [list, setList] = useState<List>(initialList)
  const sentinelRef = useRef<HTMLDivElement>(null)

  // cursor が null なら最新の 20 件から、そうでなければ続きを後ろに足す（F-5）
  const load = useCallback(async (cursor: string | null) => {
    setList((prev) => (cursor === null ? initialList : { ...prev, status: 'loading' }))
    try {
      const page = await fetchFollowList(username, kind, cursor)
      setList((prev) => ({
        items: cursor === null ? page.items : [...prev.items, ...page.items],
        nextCursor: page.nextCursor,
        status: 'loaded',
      }))
    } catch (e) {
      const notFound = e instanceof ApiError && e.code === 'USER_NOT_FOUND'
      setList((prev) => ({ ...prev, status: notFound ? 'notFound' : 'failed' }))
    }
  }, [username, kind])

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

  // 存在しないユーザー（F-7）
  if (list.status === 'notFound') return <p className={ui.empty}>{M[28]}</p>

  const { items, status } = list
  const current = TABS.find((t) => t.kind === kind) ?? TABS[0]
  return (
    <>
      <div className={styles.head}>
        {/* 「←」でその人の G-8 */}
        <button type="button" className={styles.back} aria-label="プロフィールに戻る"
          onClick={() => navigate(profileUrl(username))}>←</button>
        <h1>@{username}</h1>
      </div>
      {/* タブは URL の /following・/followers と合わせる */}
      <nav className={tabStyles.tabs} aria-label="フォロー中・フォロワー">
        {TABS.map((t) => (
          <Link key={t.kind} to={followListUrl(username, t.kind)} replace aria-current={t.kind === kind ? 'page' : undefined}
            className={t.kind === kind ? `${tabStyles.tab} ${tabStyles.active}` : tabStyles.tab}>
            {t.label}
          </Link>
        ))}
      </nav>
      <ul className={styles.userList} aria-label={current.label}>
        {items.map((user) => <UserRow key={user.username} user={user} />)}
      </ul>
      {status === 'loaded' && items.length === 0 && <p className={ui.empty}>{current.empty}</p>}
      {status === 'loading' && <output className={tabStyles.status}>読み込み中…</output>}
      {status === 'failed' && (
        <div className={tabStyles.status} role="alert">
          <p>{M[29]}</p>
          <button type="button" className={ui.secondary}
            onClick={() => void load(items.length === 0 ? null : list.nextCursor)}>再読み込み</button>
        </div>
      )}
      <div ref={sentinelRef} />
    </>
  )
}
