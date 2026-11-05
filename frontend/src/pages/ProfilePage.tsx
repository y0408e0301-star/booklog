import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import {
  ApiError, bookUrl, fetchBookshelf, fetchProfile, fetchUserReviews, followListUrl, PROFILE_EDIT_URL, type Profile,
  type ReadingStatus, type ShelfItem,
} from '../api'
import Avatar from '../components/Avatar'
import BookCover from '../components/BookCover'
import FollowButton from '../components/FollowButton'
import ReviewCard from '../components/ReviewCard'
import Stars from '../components/Stars'
import ui from '../components/ui.module.css'
import { M } from '../messages'
import tabStyles from './HomePage.module.css'
import styles from './ProfilePage.module.css'

// 「本棚」タブの小さなタブ。初めに開くのは「読書中」（ST-4）
const SHELVES: { param: string, status: ReadingStatus, label: string }[] = [
  { param: 'reading', status: 'READING', label: '読書中' },
  { param: 'read', status: 'READ', label: '読了' },
  { param: 'want', status: 'WANT', label: '読みたい' },
]

type State =
  | { status: 'loading' }
  | { status: 'loaded', profile: Profile }
  | { status: 'notFound' }
  | { status: 'failed' }

// G-8 プロフィール画面（画面設計書 10 章、FN-11 PF-1〜PF-5、FN-08 ST-4〜ST-6）
export default function ProfilePage() {
  const { username = '' } = useParams()
  // 「再読み込み」を押した回数。ユーザーか回数が替わったら画面を作り直す
  const [attempt, setAttempt] = useState(0)
  return <ProfileScreen key={`${username}:${attempt}`} username={username} onReload={() => setAttempt((n) => n + 1)} />
}

function ProfileScreen({ username, onReload }: { username: string, onReload: () => void }) {
  const [state, setState] = useState<State>({ status: 'loading' })
  const [searchParams] = useSearchParams()
  const tab = searchParams.get('tab') === 'bookshelf' ? 'bookshelf' : 'reviews'
  const shelf = SHELVES.find((s) => s.param === searchParams.get('shelf')) ?? SHELVES[0]

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const profile = await fetchProfile(username)
        if (active) setState({ status: 'loaded', profile })
      } catch (e) {
        const notFound = e instanceof ApiError && e.code === 'USER_NOT_FOUND'
        if (active) setState({ status: notFound ? 'notFound' : 'failed' })
      }
    }
    void load()
    return () => {
      active = false
    }
  }, [username])

  // 存在しないユーザーは、全体の代わりに M-28（PF-5）
  if (state.status === 'notFound') return <p className={ui.empty}>{M[28]}</p>
  if (state.status === 'failed') {
    return (
      <div className={ui.empty} role="alert">
        <p>{M[29]}</p>
        <button type="button" className={ui.secondary} onClick={onReload}>再読み込み</button>
      </div>
    )
  }

  const profile = state.status === 'loaded' ? state.profile : null
  // 件数を、削除やフォローの結果に合わせて変える
  const update = (change: (prev: Profile) => Profile) => setState((prev) => (
    prev.status === 'loaded' ? { ...prev, profile: change(prev.profile) } : prev
  ))

  return (
    <>
      {profile ? <ProfileHead profile={profile} onFollowerCount={(followerCount) => update((p) => ({ ...p, followerCount }))} />
        : <p className={ui.empty}>読み込み中…</p>}
      {/* プロフィールと一覧は同時に読み始める（FN-11 3 章）。一覧はプロフィールが届いてから見せる */}
      <div hidden={profile === null}>
        <nav className={tabStyles.tabs} aria-label="レビュー・本棚">
          <TabLink to="?tab=reviews" active={tab === 'reviews'}>レビュー</TabLink>
          <TabLink to={`?tab=bookshelf&shelf=${shelf.param}`} active={tab === 'bookshelf'}>本棚</TabLink>
        </nav>
        {tab === 'reviews'
          ? <ReviewList username={username} onDelete={() => update((p) => ({ ...p, reviewCount: p.reviewCount - 1 }))} />
          : (
            <>
              <nav className={styles.shelves} aria-label="本棚の分類">
                {SHELVES.map((s) => (
                  <Link key={s.param} to={`?tab=bookshelf&shelf=${s.param}`} replace
                    aria-current={s === shelf ? 'page' : undefined}
                    className={s === shelf ? `${styles.shelf} ${styles.shelfActive}` : styles.shelf}>
                    {s.label} {profile?.bookshelfCounts[s.status]}
                  </Link>
                ))}
              </nav>
              {/* 分類が替わったら一覧を作り直す */}
              <Bookshelf key={shelf.status} username={username} status={shelf.status} />
            </>
          )}
      </div>
    </>
  )
}

function TabLink({ to, active, children }: { to: string, active: boolean, children: string }) {
  return (
    <Link to={to} replace aria-current={active ? 'page' : undefined}
      className={active ? `${tabStyles.tab} ${tabStyles.active}` : tabStyles.tab}>
      {children}
    </Link>
  )
}

// 頭文字の丸・名前・自己紹介・件数（PF-2）と、フォローボタンか「プロフィールを編集」（PF-3）
function ProfileHead({ profile, onFollowerCount }: { profile: Profile, onFollowerCount: (count: number) => void }) {
  const { username } = profile
  return (
    <section className={styles.head} aria-label="プロフィール">
      <div className={styles.top}>
        <Avatar displayName={profile.displayName} size={80} />
        <div className={styles.action}>
          {profile.me
            ? <Link to={PROFILE_EDIT_URL} className={`${ui.secondary} ${styles.edit}`}>プロフィールを編集</Link>
            : <FollowButton username={username} following={profile.followedByMe}
                onChange={(result) => onFollowerCount(result.followerCount)} />}
        </div>
      </div>
      <h1 className={styles.name}>{profile.displayName}</h1>
      <p className={styles.username}>@{username}</p>
      {/* 改行はそのまま出す。タグも文字のまま出る（TC-11-6） */}
      {profile.bio && <p className={styles.bio}>{profile.bio}</p>}
      <p className={styles.counts}>
        <span><b>{profile.reviewCount}</b> レビュー</span>
        <Link to={followListUrl(username, 'following')}><b>{profile.followingCount}</b> フォロー</Link>
        <Link to={followListUrl(username, 'followers')}><b>{profile.followerCount}</b> フォロワー</Link>
      </p>
    </section>
  )
}

type Page<T> = { items: T[], nextCursor: string | null }

type List<T> = Page<T> & { status: 'loading' | 'loaded' | 'failed' }

// 20 件ずつの一覧。開いたら最初の 20 件を読み、一番下が見えたら続きを読む（PF-4、ST-5）。fetchPage は useCallback で渡す
function usePagedList<T>(fetchPage: (cursor: string | null) => Promise<Page<T>>) {
  const [list, setList] = useState<List<T>>({ items: [], nextCursor: null, status: 'loading' })
  const requestRef = useRef(0)
  const sentinelRef = useRef<HTMLDivElement>(null)

  const load = useCallback(async (cursor: string | null) => {
    const request = ++requestRef.current
    setList((prev) => ({ ...prev, status: 'loading' }))
    try {
      const page = await fetchPage(cursor)
      if (request !== requestRef.current) return
      setList((prev) => ({
        items: cursor === null ? page.items : [...prev.items, ...page.items],
        nextCursor: page.nextCursor,
        status: 'loaded',
      }))
    } catch {
      if (request === requestRef.current) setList((prev) => ({ ...prev, status: 'failed' }))
    }
  }, [fetchPage])

  useEffect(() => {
    async function start() {
      await load(null)
    }
    void start()
  }, [load])

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

  const retry = () => void load(list.items.length === 0 ? null : list.nextCursor)
  const remove = (match: (item: T) => boolean) => setList((prev) => ({
    ...prev, items: prev.items.filter((item) => !match(item)),
  }))
  return { list, sentinelRef, retry, remove }
}

// 一覧の下の「読み込み中…」、失敗したときの M-29 と「再読み込み」、続きを読む目印
function ListFoot({ status, retry, sentinelRef }: {
  status: List<unknown>['status']
  retry: () => void
  sentinelRef: React.RefObject<HTMLDivElement | null>
}) {
  return (
    <>
      {status === 'loading' && <output className={tabStyles.status}>読み込み中…</output>}
      {status === 'failed' && (
        <div className={tabStyles.status} role="alert">
          <p>{M[29]}</p>
          <button type="button" className={ui.secondary} onClick={retry}>再読み込み</button>
        </div>
      )}
      <div ref={sentinelRef} />
    </>
  )
}

// 「レビュー」タブ：その人のレビューを新しい順に 20 件ずつ（PF-4）
function ReviewList({ username, onDelete }: { username: string, onDelete: () => void }) {
  const fetchPage = useCallback((cursor: string | null) => fetchUserReviews(username, cursor), [username])
  const { list, sentinelRef, retry, remove } = usePagedList(fetchPage)
  return (
    <>
      {list.items.map((review) => (
        <ReviewCard key={review.id} review={review} showBook onDelete={(id) => {
          remove((r) => r.id === id)
          onDelete()
        }} />
      ))}
      {list.status === 'loaded' && list.items.length === 0 && <p className={ui.empty}>{M[26]}</p>}
      <ListFoot status={list.status} retry={retry} sentinelRef={sentinelRef} />
    </>
  )
}

// 「本棚」タブの一覧：ステータスを付けた（変えた）日時の新しい順に 20 冊ずつ（ST-5）。0 冊なら M-27（ST-6）
function Bookshelf({ username, status }: { username: string, status: ReadingStatus }) {
  const fetchPage = useCallback((cursor: string | null) => fetchBookshelf(username, status, cursor), [username, status])
  const { list, sentinelRef, retry } = usePagedList(fetchPage)
  return (
    <>
      {list.items.length > 0 && (
        <ul className={styles.books}>
          {list.items.map((item) => <ShelfBook key={item.book.id} item={item} />)}
        </ul>
      )}
      {list.status === 'loaded' && list.items.length === 0 && <p className={ui.empty}>{M[27]}</p>}
      <ListFoot status={list.status} retry={retry} sentinelRef={sentinelRef} />
    </>
  )
}

// 本棚の 1 冊。表紙・書名・著者と、その人の星（レビューがあれば）。押すと G-5（ST-5）
function ShelfBook({ item: { book, rating } }: { item: ShelfItem }) {
  return (
    <li>
      <Link to={bookUrl(book.id)} className={styles.book}>
        <BookCover title={book.title} thumbnailUrl={book.thumbnailUrl} width="full" />
        <span className={styles.bookTitle}>{book.title}</span>
        {book.authors.length > 0 && <span className={styles.bookAuthors}>{book.authors.join('、')}</span>}
        {rating !== null && <Stars value={rating} size={16} />}
      </Link>
    </li>
  )
}
