import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { M } from '../messages'
import { json, mockFetch } from '../test/fetchMock'
import { renderComponent, review } from '../test/render'
import ReviewCard from './ReviewCard'

// レビューのカード（画面設計書 2.7、FN-03 RV-6〜RV-12）

const location = () => screen.getByRole('status', { name: '今の URL' })

describe('ReviewCard', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('書いた人・日時・本・星・感想・件数を出す（RV-6）', () => {
    renderComponent(<ReviewCard review={review({ commentCount: 3, helpfulCount: 12 })} showBook onDelete={vi.fn()} />)
    const card = screen.getByRole('article', { name: 'たろうのレビュー' })

    expect(within(card).getByRole('link', { name: /^たろう\s*@taro_y$/ })).toHaveAttribute('href', '/users/taro_y')
    expect(within(card).getByText('· 1 分前')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: /こころ/ })).toHaveAttribute('href', '/books/56')
    expect(card).toHaveTextContent('星 4（5 段階中）')
    expect(within(card).getByRole('link', { name: '最後まで一気に読んだ。' })).toHaveAttribute('href', '/reviews/1234')
    expect(within(card).getByLabelText('参考になった 12 件')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'コメント 3 件' })).toHaveAttribute('href', '/reviews/1234')
  })

  it('G-5 では本を出さない（BK-5）', () => {
    renderComponent(<ReviewCard review={review()} showBook={false} onDelete={vi.fn()} />)
    expect(screen.queryByRole('link', { name: /こころ/ })).not.toBeInTheDocument()
  })

  // TC-03-5
  it('編集したレビューは「（編集済み）」を出し、最後に編集した日時を添える', () => {
    renderComponent(<ReviewCard review={review({ editedAt: '2026-11-01T06:30:00Z' })} showBook onDelete={vi.fn()} />)
    expect(screen.getByText('（編集済み）')).toHaveAttribute('title', '2026/11/01 15:30 に編集')
  })

  // TC-03-10
  it('感想の HTML の記号と改行はそのまま出す（RV-7）', () => {
    renderComponent(<ReviewCard review={review({ body: '<script>alert(1)</script>\n二行目' })} showBook detail
      onDelete={vi.fn()} />)
    expect(screen.getByText(/<script>alert\(1\)<\/script>/).textContent).toBe('<script>alert(1)</script>\n二行目')
  })

  // TC-03-11
  it('一覧で 5 行に収まらない感想は「続きを読む」で G-7 を開く', () => {
    // jsdom は大きさを測らないので、5 行で切った感想が収まっていないことにする
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(200)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100)
    renderComponent(<ReviewCard review={review()} showBook onDelete={vi.fn()} />)
    expect(screen.getByRole('link', { name: '続きを読む' })).toHaveAttribute('href', '/reviews/1234')
  })

  it('G-7 では感想を全部出し、「続きを読む」もリンクも出さない', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(200)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100)
    renderComponent(<ReviewCard review={review()} showBook detail onDelete={vi.fn()} />)
    expect(screen.queryByRole('link', { name: '続きを読む' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '最後まで一気に読んだ。' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('コメント 0 件')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'コメント 0 件' })).not.toBeInTheDocument()
  })

  // TC-03-8（RV-11・RV-12）
  it('ネタバレありは感想を伏せ、星・書名・書いた人は出す。「ネタバレを表示」で開いて感想にカーソルを移す', async () => {
    renderComponent(<ReviewCard review={review({ spoiler: true, body: '犯人は執事' })} showBook onDelete={vi.fn()} />)

    expect(screen.queryByText('犯人は執事')).not.toBeInTheDocument()
    expect(screen.getByText('⚠ ネタバレあり')).toBeInTheDocument()
    expect(screen.getByRole('article')).toHaveTextContent('星 4（5 段階中）')
    expect(screen.getByRole('link', { name: /こころ/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'ネタバレを表示' }))
    expect(screen.getByRole('link', { name: '犯人は執事' })).toHaveFocus()
    expect(screen.queryByText('⚠ ネタバレあり')).not.toBeInTheDocument()
  })

  it('自分のレビューでも伏せる', () => {
    renderComponent(<ReviewCard review={review({ spoiler: true, mine: true })} showBook onDelete={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'ネタバレを表示' })).toBeInTheDocument()
  })

  // RV-9
  it('「…」メニューは自分のレビューにだけ出し、「編集」で G-6 を開く', async () => {
    const { unmount } = renderComponent(<ReviewCard review={review({ mine: false })} showBook onDelete={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'メニュー' })).not.toBeInTheDocument()
    unmount()

    renderComponent(<ReviewCard review={review()} showBook onDelete={vi.fn()} />)
    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '編集' }))
    expect(location()).toHaveTextContent('/books/56/review')
  })

  it('「削除」で G-12 に M-19 を出し、「削除」で消す。「キャンセル」では何もしない', async () => {
    const onDelete = vi.fn()
    const fetch = mockFetch({ 'DELETE /api/reviews/1234': () => json(204) })
    renderComponent(<ReviewCard review={review()} showBook onDelete={onDelete} />)

    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    const dialog = screen.getByRole('alertdialog', { name: 'レビューを削除' })
    expect(dialog).toHaveAccessibleDescription(M[19])
    await userEvent.click(within(dialog).getByRole('button', { name: 'キャンセル' }))
    expect(fetch.calls).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除' }))
    expect(onDelete).toHaveBeenCalledWith(1234)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
  })

  it('削除に失敗したら M-30 を出して残す。先に消されていたら M-20 を出して消す', async () => {
    const onDelete = vi.fn()
    mockFetch({
      'DELETE /api/reviews/1234': [
        () => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }),
        () => json(404, { code: 'REVIEW_NOT_FOUND', message: M[20], field: null }),
      ],
    })
    renderComponent(<ReviewCard review={review()} showBook onDelete={onDelete} />)

    for (const message of [M[30], M[20]]) {
      await userEvent.click(screen.getByRole('button', { name: 'メニュー' }))
      await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
      await userEvent.click(screen.getByRole('button', { name: '削除' }))
      expect(await screen.findByText(message)).toBeInTheDocument()
    }
    expect(onDelete).toHaveBeenCalledOnce()
  })
})
