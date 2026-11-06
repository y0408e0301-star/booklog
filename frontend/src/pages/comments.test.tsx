import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { CommentView } from '../api'
import { M } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { renderComponent, review } from '../test/render'
import ReviewPage from './ReviewPage'

// G-7 のコメント（FN-06）。10 章のテストの観点のうち、画面で確かめるもの

function comment(id: number, overrides: Partial<CommentView> = {}): CommentView {
  return {
    id, author: { username: 'hanako', displayName: 'はなこ' }, body: `コメント ${id}`, mine: false,
    createdAt: new Date().toISOString(), ...overrides,
  }
}

const page = (items: CommentView[], nextCursor: string | null = null) => () => json(200, { items, nextCursor })

function renderDetail() {
  return renderComponent(<ReviewPage />, { path: '/reviews/1234', route: '/reviews/:id' })
}

const form = () => screen.getByRole('textbox', { name: 'コメントの本文' })
const submit = () => screen.getByRole('button', { name: 'コメントする' })
const shown = () => within(screen.getByRole('list', { name: 'コメントの一覧' })).queryAllByRole('listitem')
  .map((li) => li.querySelector('div > div:last-child')?.textContent)
const cardCount = () => within(screen.getByRole('article')).getByLabelText(/^コメント \d+ 件$/)

describe('コメント（G-7）', () => {
  // C-3・C-4・C-7
  it('古い順に、表示名・@ユーザー ID・本文を出す。名前を押すと G-8。HTML の記号と改行はそのまま（TC-06-6）', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 2 })),
      'GET /api/reviews/1234/comments': page([comment(1, { body: '<b>太字</b>\n二行目' }), comment(2)]),
    })
    renderDetail()
    expect(await screen.findByRole('heading', { name: 'コメント 2 件' })).toBeInTheDocument()
    await screen.findByText('コメント 2')
    expect(shown()).toEqual(['<b>太字</b>\n二行目', 'コメント 2'])
    const first = screen.getAllByRole('listitem', { name: 'はなこのコメント' })[0]
    expect(within(first).getByRole('link', { name: /^はなこ\s*@hanako$/ })).toHaveAttribute('href', '/users/hanako')
  })

  // TC-06-1（C-2）
  it('0 文字（空白・改行だけ）と 500 文字を超えたときは押せない。残りの文字数を出す', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(200, review()), 'GET /api/reviews/1234/comments': page([]) })
    renderDetail()
    await screen.findByText('残り 500')
    expect(submit()).toBeDisabled()
    await userEvent.type(form(), ' {Enter} ')
    expect(submit()).toBeDisabled()

    await userEvent.clear(form())
    await userEvent.click(form())
    await userEvent.paste('あ'.repeat(500))
    expect(screen.getByText('残り 0')).toBeInTheDocument()
    expect(submit()).toBeEnabled()
    await userEvent.paste('あ')
    expect(screen.getByText('-1')).toBeInTheDocument()
    expect(submit()).toBeDisabled()
  })

  // C-1・TC-06-4
  it('付けるとトリムして送り、フォームを空にして一覧の下に足し、見出しとカードのコメント数を 1 増やす', async () => {
    const fetch = mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 1 })),
      'GET /api/reviews/1234/comments': page([comment(1)]),
      'POST /api/reviews/1234/comments': () => json(201, comment(2, { body: '私も', mine: true })),
    })
    renderDetail()
    await screen.findByText('コメント 1')
    await userEvent.type(form(), '  私も  ')
    await userEvent.click(submit())

    expect(await screen.findByText('私も')).toBeInTheDocument()
    expect(sentJson(fetch, 2)).toEqual({ body: '私も' })
    expect(form()).toHaveValue('')
    expect(shown()).toEqual(['コメント 1', '私も'])
    expect(screen.getByRole('heading', { name: 'コメント 2 件' })).toBeInTheDocument()
    expect(cardCount()).toHaveTextContent('2')
  })

  it('Ctrl＋Enter で送る', async () => {
    const fetch = mockFetch({
      'GET /api/reviews/1234': () => json(200, review()),
      'GET /api/reviews/1234/comments': page([]),
      'POST /api/reviews/1234/comments': () => json(201, comment(2, { mine: true })),
    })
    renderDetail()
    await screen.findByText('残り 500')
    await userEvent.type(form(), 'すごい{Control>}{Enter}{/Control}')
    await screen.findByText('コメント 2')
    expect(fetch.keys()).toContain('POST /api/reviews/1234/comments')
  })

  it('送信中は押せず、失敗したら M-30 を出して本文を残す', async () => {
    let fail: (() => void) | undefined
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review()),
      'GET /api/reviews/1234/comments': page([]),
      'POST /api/reviews/1234/comments': () => new Promise<Response>((resolve) => {
        fail = () => resolve(json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }))
      }),
    })
    renderDetail()
    await screen.findByText('残り 500')
    await userEvent.type(form(), '残しておく')
    await userEvent.click(submit())
    expect(screen.getByRole('button', { name: '送信中…' })).toBeDisabled()

    act(() => fail?.())
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(form()).toHaveValue('残しておく')
  })

  it('レビューが削除されていたら M-20 を出し、削除されたレビューの表示にする', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review()),
      'GET /api/reviews/1234/comments': page([]),
      'POST /api/reviews/1234/comments': () => json(404, { code: 'REVIEW_NOT_FOUND', message: M[20], field: null }),
    })
    renderDetail()
    await screen.findByText('残り 500')
    await userEvent.type(form(), 'a')
    await userEvent.click(submit())
    expect(await screen.findAllByText(M[20])).not.toHaveLength(0)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  // TC-06-2
  it('「もっと見る」で次の 50 件を足し、続きがなければ出さない', async () => {
    const first = Array.from({ length: 50 }, (_, i) => comment(i + 1))
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 51 })),
      'GET /api/reviews/1234/comments': page(first, '50'),
      'GET /api/reviews/1234/comments?cursor=50': page([comment(51)]),
    })
    renderDetail()
    await screen.findByText('コメント 50')
    expect(shown()).toHaveLength(50)
    await userEvent.click(screen.getByRole('button', { name: 'もっと見る' }))
    expect(await screen.findByText('コメント 51')).toBeInTheDocument()
    expect(shown()).toHaveLength(51)
    expect(screen.queryByRole('button', { name: 'もっと見る' })).not.toBeInTheDocument()
  })

  it('一覧の読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review()),
      'GET /api/reviews/1234/comments': [() => json(500, {}), page([comment(1)])],
    })
    renderDetail()
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByText('コメント 1')).toBeInTheDocument()
  })

  it('「もっと見る」に失敗したら、出したコメントは残して M-29 と「再読み込み」を出す', async () => {
    const first = Array.from({ length: 50 }, (_, i) => comment(i + 1))
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 51 })),
      'GET /api/reviews/1234/comments': page(first, '50'),
      'GET /api/reviews/1234/comments?cursor=50': [() => json(500, {}), page([comment(51)])],
    })
    renderDetail()
    await screen.findByText('コメント 50')
    await userEvent.click(screen.getByRole('button', { name: 'もっと見る' }))
    expect(await screen.findByText(M[29])).toBeInTheDocument()
    expect(shown()).toHaveLength(50)
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByText('コメント 51')).toBeInTheDocument()
    expect(screen.queryByText(M[29])).not.toBeInTheDocument()
  })

  // C-5・TC-06-4
  it('自分のコメントにだけ「削除」を出し、M-24 で確かめてから消してコメント数を 1 減らす', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 2 })),
      'GET /api/reviews/1234/comments': page([comment(1), comment(2, { mine: true, body: '自分の' })]),
      'DELETE /api/comments/2': () => json(204),
    })
    renderDetail()
    await screen.findByText('自分の')
    const items = screen.getAllByRole('listitem')
    expect(within(items[0]).queryByRole('button', { name: '削除' })).not.toBeInTheDocument()
    await userEvent.click(within(items[1]).getByRole('button', { name: '削除' }))
    const dialog = screen.getByRole('alertdialog', { name: 'コメントを削除' })
    expect(dialog).toHaveTextContent(M[24])
    await userEvent.click(within(dialog).getByRole('button', { name: '削除' }))

    expect(screen.queryByText('自分の')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'コメント 1 件' })).toBeInTheDocument()
    expect(cardCount()).toHaveTextContent('1')
  })

  it('消そうとしたコメントがすでに削除されていたら、メッセージを出さずに一覧から消す', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ commentCount: 1 })),
      'GET /api/reviews/1234/comments': page([comment(2, { mine: true, body: '自分の' })]),
      'DELETE /api/comments/2': () => json(404, { code: 'COMMENT_NOT_FOUND', message: '…', field: null }),
    })
    renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: '削除' }))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '削除' }))
    expect(screen.queryByText('自分の')).not.toBeInTheDocument()
    expect(screen.queryByText(M[30])).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'コメント 0 件' })).toBeInTheDocument()
  })

  // TC-06-5（C-6）
  it('ネタバレありでは「ネタバレを表示」を押すまで、コメントの見出し・フォーム・一覧を伏せる', async () => {
    mockFetch({
      'GET /api/reviews/1234': () => json(200, review({ spoiler: true, commentCount: 1 })),
      'GET /api/reviews/1234/comments': page([comment(1, { body: '犯人の話' })]),
    })
    renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: 'ネタバレを表示' }))
    expect(await screen.findByText('犯人の話')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'コメント 1 件' })).toBeInTheDocument()
  })

  it('ネタバレありで伏せている間は、コメントを読み込まない', async () => {
    const fetch = mockFetch({ 'GET /api/reviews/1234': () => json(200, review({ spoiler: true })) })
    renderDetail()
    await screen.findByRole('button', { name: 'ネタバレを表示' })
    expect(screen.getAllByText('⚠ ネタバレあり')).toHaveLength(2)
    expect(screen.queryByRole('heading', { name: /コメント/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'コメントの本文' })).not.toBeInTheDocument()
    expect(fetch.keys()).toEqual(['GET /api/reviews/1234'])
  })
})
