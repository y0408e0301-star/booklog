import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReviewPage, ReviewView } from '../api'
import App from '../App'
import { M } from '../messages'
import { json, mockFetch, type Handler } from '../test/fetchMock'
import { mockIntersectionObserver } from '../test/intersection'
import { me, review } from '../test/render'

// タイムライン（FN-09）の画面。10 章のテストの観点のうち、画面で確かめるもの

function item(id: number, overrides: Partial<ReviewView> = {}): ReviewView {
  return review({
    id, body: `感想 ${id}`, mine: false, author: { username: 'hanako', displayName: 'はなこ' },
    book: { id, title: `本 ${id}`, authors: [], thumbnailUrl: null }, ...overrides,
  })
}

// ID が from から to まで（新しい順）のレビュー
const items = (from: number, to: number) => Array.from({ length: from - to + 1 }, (_, i) => item(from - i))

const page = (list: ReviewView[], nextCursor: string | null = null): Handler =>
  () => json(200, { items: list, nextCursor } satisfies ReviewPage)

const failed: Handler = () => json(500, { code: 'INTERNAL_ERROR', message: M[30] })

// 今の URL を見られるように出しておく
function CurrentUrl() {
  const location = useLocation()
  return <output aria-label="URL">{location.pathname + location.search}</output>
}

function renderAt(path: string, routes: Record<string, Handler | Handler[]>) {
  const fetch = mockFetch({ 'GET /api/auth/me': () => json(200, me), ...routes })
  render(<MemoryRouter initialEntries={[path]}><App /><CurrentUrl /></MemoryRouter>)
  return fetch
}

const url = () => screen.getByRole('status', { name: 'URL' }).textContent
const tab = (name: string) => screen.getByRole('tab', { name })
// カードに出ている感想（感想のリンクの文字）
const bodies = () => screen.queryAllByRole('article')
  .map((a) => within(a).getByRole('link', { name: /^感想 \d+$|^自分の感想$/ }).textContent)

let io: ReturnType<typeof mockIntersectionObserver>

beforeEach(() => {
  localStorage.clear()
  io = mockIntersectionObserver()
})

describe('タブ（T-1・T-3・T-6）', () => {
  // TC-09-3
  it('初めて開いたときは「フォロー中」を出し、URL を ?tab=following にする', async () => {
    renderAt('/', { 'GET /api/timeline?tab=following': page([item(1)]) })
    expect(await screen.findByText('感想 1')).toBeInTheDocument()
    expect(tab('フォロー中')).toHaveAttribute('aria-selected', 'true')
    expect(tab('すべて')).toHaveAttribute('aria-selected', 'false')
    await waitFor(() => expect(url()).toBe('/?tab=following'))
  })

  // TC-09-3
  it('タブを押すとそのタブの最新 20 件を出し、選んだタブを記録して次に開いたときに出す', async () => {
    const fetch = renderAt('/', {
      'GET /api/timeline?tab=following': page([item(1)]),
      'GET /api/timeline?tab=all': page([item(3), item(2), item(1)]),
    })
    await screen.findByText('感想 1')
    await userEvent.click(tab('すべて'))
    await waitFor(() => expect(bodies()).toEqual(['感想 3', '感想 2', '感想 1']))
    expect(tab('すべて')).toHaveAttribute('aria-selected', 'true')
    expect(url()).toBe('/?tab=all')
    expect(localStorage.getItem('bl.tab')).toBe('all')
    expect(fetch.keys()).toContain('GET /api/timeline?tab=all')
  })

  // TC-09-3
  it('前回「すべて」を選んでいたら、?tab= のない URL で開いても「すべて」を出す', async () => {
    localStorage.setItem('bl.tab', 'all')
    renderAt('/', { 'GET /api/timeline?tab=all': page([item(1)]) })
    expect(await screen.findByText('感想 1')).toBeInTheDocument()
    expect(tab('すべて')).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(url()).toBe('/?tab=all'))
  })

  it('localStorage が使えないブラウザでも「フォロー中」で動く', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('使えない') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('使えない') })
    renderAt('/', {
      'GET /api/timeline?tab=following': page([item(1)]),
      'GET /api/timeline?tab=all': page([item(2)]),
    })
    expect(await screen.findByText('感想 1')).toBeInTheDocument()
    await userEvent.click(tab('すべて'))
    expect(await screen.findByText('感想 2')).toBeInTheDocument()
  })

  // T-6
  it('表示中のタブを押し直すと最新の 20 件から出し直す（ほかの人の新しいレビューが出る）', async () => {
    const fetch = renderAt('/', {
      'GET /api/timeline?tab=following': [page([item(1)]), page([item(2), item(1)])],
    })
    await screen.findByText('感想 1')
    await userEvent.click(tab('フォロー中'))
    await waitFor(() => expect(bodies()).toEqual(['感想 2', '感想 1']))
    expect(fetch.keys().filter((k) => k === 'GET /api/timeline?tab=following')).toHaveLength(2)
  })

  it('タブを素早く切り替えたら、前のタブの遅れた応答は使わない', async () => {
    let finish: ((res: Response) => void) | undefined
    renderAt('/', {
      'GET /api/timeline?tab=following': () => new Promise<Response>((resolve) => { finish = resolve }),
      'GET /api/timeline?tab=all': page([item(2)]),
    })
    await userEvent.click(await screen.findByRole('tab', { name: 'すべて' }))
    await screen.findByText('感想 2')
    await act(async () => finish?.(json(200, { items: [item(1)], nextCursor: null })))
    expect(bodies()).toEqual(['感想 2'])
  })
})

describe('一覧（T-2・T-4・T-8）', () => {
  // TC-09-2
  it('一番下までスクロールすると次の 20 件を足し、最後まで読んだら「これ以上のレビューはありません」', async () => {
    let finish: ((res: Response) => void) | undefined
    renderAt('/?tab=all', {
      'GET /api/timeline?tab=all': page(items(41, 22), '22'),
      'GET /api/timeline?tab=all&cursor=22': () => new Promise<Response>((resolve) => { finish = resolve }),
      'GET /api/timeline?tab=all&cursor=2': page([item(1)]),
    })
    await waitFor(() => expect(bodies()).toHaveLength(20))
    expect(screen.queryByText('これ以上のレビューはありません')).not.toBeInTheDocument()

    await waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    expect(await screen.findByText('読み込み中…')).toBeInTheDocument()
    await act(async () => finish?.(json(200, { items: items(21, 2), nextCursor: '2' })))
    await waitFor(() => expect(bodies()).toHaveLength(40))
    expect(screen.queryByText('読み込み中…')).not.toBeInTheDocument()

    await waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    await waitFor(() => expect(bodies()).toHaveLength(41))
    expect(bodies()[40]).toBe('感想 1')
    expect(screen.getByText('これ以上のレビューはありません')).toBeInTheDocument()
  })

  it('一覧のカードには本を出す', async () => {
    renderAt('/?tab=all', { 'GET /api/timeline?tab=all': page([item(1)]) })
    const card = await screen.findByRole('article')
    expect(within(card).getByRole('link', { name: /本 1/ })).toHaveAttribute('href', '/books/1')
  })

  // TC-09-4（T-4）
  it('「フォロー中」で出すレビューがないときは M-25 と「すべて」タブを開くボタンを出す', async () => {
    renderAt('/', {
      'GET /api/timeline?tab=following': page([]),
      'GET /api/timeline?tab=all': page([item(1)]),
    })
    expect(await screen.findByText(M[25])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '「すべて」タブを開く' }))
    expect(await screen.findByText('感想 1')).toBeInTheDocument()
    expect(tab('すべて')).toHaveAttribute('aria-selected', 'true')
  })

  it('「すべて」が 0 件のときは、本を探してレビューを書くよう促す', async () => {
    renderAt('/?tab=all', { 'GET /api/timeline?tab=all': page([]) })
    expect(await screen.findByText('まだレビューがありません。本を探して、最初のレビューを書いてみましょう。'))
      .toBeInTheDocument()
  })

  // TC-09-7（T-8）
  it('最初の読み込みに失敗したら M-29 と「再読み込み」を出し、「再読み込み」でやり直す', async () => {
    renderAt('/?tab=all', { 'GET /api/timeline?tab=all': [failed, page([item(1)])] })
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByText('感想 1')).toBeInTheDocument()
    expect(screen.queryByText(M[29])).not.toBeInTheDocument()
  })

  // TC-09-7
  it('続きの読み込みに失敗したら、出しているレビューは残して続きだけをやり直す', async () => {
    const fetch = renderAt('/?tab=all', {
      'GET /api/timeline?tab=all': page(items(21, 2), '2'),
      'GET /api/timeline?tab=all&cursor=2': [failed, page([item(1)])],
    })
    await waitFor(() => expect(bodies()).toHaveLength(20))
    await waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    expect(bodies()).toHaveLength(20)
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    await waitFor(() => expect(bodies()).toHaveLength(21))
    expect(fetch.keys().filter((k) => k === 'GET /api/timeline?tab=all')).toHaveLength(1)
  })
})

describe('削除の反映', () => {
  it('一覧の自分のレビューを削除すると一覧から消す', async () => {
    renderAt('/?tab=all', {
      'GET /api/timeline?tab=all': page([item(2, { mine: true, author: me, body: '自分の感想' }), item(1)]),
      'DELETE /api/reviews/2': () => json(204),
    })
    await screen.findByText('自分の感想')
    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '削除' }))
    await waitFor(() => expect(bodies()).toEqual(['感想 1']))
  })
})
