import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { FollowListKind, UserSummary } from '../api'
import { M } from '../messages'
import { json, mockFetch } from '../test/fetchMock'
import { mockIntersectionObserver } from '../test/intersection'
import { renderComponent } from '../test/render'
import FollowListPage from './FollowListPage'

// G-10 フォロー中・フォロワーの一覧画面（FN-07 F-5〜F-7）

function user(name: string, overrides: Partial<UserSummary> = {}): UserSummary {
  return { username: name, displayName: `${name}の表示名`, bioFirstLine: '', followedByMe: false, me: false, ...overrides }
}

const page = (items: UserSummary[], nextCursor: string | null = null) => () => json(200, { items, nextCursor })

function renderList(kind: FollowListKind = 'following', username = 'taro_y') {
  return renderComponent(<FollowListPage kind={kind} />,
    { path: `/users/${username}/${kind}`, route: `/users/:username/${kind}` })
}

describe('フォロー中・フォロワーの一覧（G-10）', () => {
  // F-5・F-6
  it('見出しとタブを出し、ユーザーの行に表示名・@ユーザー ID・自己紹介の 1 行目・フォローボタンを出す', async () => {
    mockFetch({
      'GET /api/users/taro_y/following': page([
        user('hanako', { bioFirstLine: 'ミステリーをよく読みます', followedByMe: true }),
        user('jiro'),
      ]),
    })
    renderList()
    expect(screen.getByRole('heading', { name: '@taro_y' })).toBeInTheDocument()
    const tabs = screen.getByRole('navigation', { name: 'フォロー中・フォロワー' })
    expect(within(tabs).getByRole('link', { name: 'フォロー中' })).toHaveAttribute('aria-current', 'page')
    expect(within(tabs).getByRole('link', { name: 'フォロワー' })).toHaveAttribute('href', '/users/taro_y/followers')

    const hanako = await screen.findByRole('listitem', { name: 'hanakoの表示名' })
    expect(within(hanako).getByRole('link', { name: /^hanakoの表示名\s*@hanako$/ })).toHaveAttribute('href', '/users/hanako')
    expect(hanako).toHaveTextContent('ミステリーをよく読みます')
    expect(within(hanako).getByRole('button')).toHaveTextContent(/^フォロー中/)
    const jiro = screen.getByRole('listitem', { name: 'jiroの表示名' })
    expect(within(jiro).getByRole('button')).toHaveTextContent('フォローする')
  })

  it('自分の行にはフォローボタンを出さない。自己紹介の 1 行目は 60 文字を超えたら「…」で省く', async () => {
    mockFetch({
      'GET /api/users/taro_y/followers': page([user('taro_y', { me: true, bioFirstLine: 'あ'.repeat(61) })]),
    })
    renderList('followers')
    const me = await screen.findByRole('listitem', { name: 'taro_yの表示名' })
    expect(within(me).queryByRole('button')).not.toBeInTheDocument()
    expect(me).toHaveTextContent(`${'あ'.repeat(60)}…`)
  })

  it('0 件のときはタブごとのメッセージ', async () => {
    mockFetch({ 'GET /api/users/taro_y/following': page([]) })
    const { unmount } = renderList()
    expect(await screen.findByText('まだ誰もフォローしていません。')).toBeInTheDocument()
    unmount()

    mockFetch({ 'GET /api/users/taro_y/followers': page([]) })
    renderList('followers')
    expect(await screen.findByText('まだフォロワーはいません。')).toBeInTheDocument()
  })

  // TC-07-4
  it('一番下まで見たら次の 20 件を足す', async () => {
    const io = mockIntersectionObserver()
    const fetch = mockFetch({
      'GET /api/users/taro_y/following': page([user('a')], '1_2'),
      'GET /api/users/taro_y/following?cursor=1_2': page([user('b')]),
    })
    renderList()
    await screen.findByRole('listitem', { name: 'aの表示名' })
    await vi.waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    expect(await screen.findByRole('listitem', { name: 'bの表示名' })).toBeInTheDocument()
    expect(fetch.keys()).toContain('GET /api/users/taro_y/following?cursor=1_2')
  })

  it('見出しは表示名。読めなければ @ユーザー ID のまま', async () => {
    mockFetch({
      'GET /api/users/taro_y': () => json(200, { displayName: 'たろう' }),
      'GET /api/users/taro_y/following': page([]),
    })
    renderList()
    expect(await screen.findByRole('heading', { name: 'たろう' })).toBeInTheDocument()
  })

  // TC-07-5・F-7
  it('存在しないユーザーは M-28', async () => {
    mockFetch({
      'GET /api/users/nobody/following': () => json(404, { code: 'USER_NOT_FOUND', message: M[28], field: null }),
    })
    renderList('following', 'nobody')
    expect(await screen.findByText(M[28])).toBeInTheDocument()
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })

  it('読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({ 'GET /api/users/taro_y/following': [() => json(500, {}), page([user('hanako')])] })
    renderList()
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('listitem', { name: 'hanakoの表示名' })).toBeInTheDocument()
  })

  it('「←」でその人の G-8', async () => {
    mockFetch({ 'GET /api/users/taro_y/following': page([]) })
    renderList()
    await userEvent.click(screen.getByRole('button', { name: 'プロフィールに戻る' }))
    expect(screen.getByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/users\/taro_y$/)
  })
})
