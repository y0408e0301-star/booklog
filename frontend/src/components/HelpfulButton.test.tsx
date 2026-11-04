import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ReviewView } from '../api'
import { M } from '../messages'
import { json, mockFetch } from '../test/fetchMock'
import { renderComponent, review } from '../test/render'
import HelpfulButton from './HelpfulButton'

// レビューのカードの「参考になった」（FN-05 H-1〜H-5、画面設計書 2.7）

// ほかの人のレビュー
const others = (overrides: Partial<ReviewView> = {}) => review({ mine: false, helpfulCount: 12, ...overrides })

const button = () => screen.getByRole('button', { name: /参考になった/ })

describe('HelpfulButton', () => {
  // H-1（H-5：0 件のときも「0」）
  it('押すとすぐに押した状態にして数を 1 増やし、返ってきた数に合わせる', async () => {
    mockFetch({ 'PUT /api/reviews/1234/helpful': () => json(200, { helpful: true, helpfulCount: 20 }) })
    renderComponent(<HelpfulButton review={others({ helpfulCount: 0 })} onDelete={vi.fn()} />)
    expect(button()).toHaveTextContent('👍 参考になった 0')
    expect(button()).toHaveAttribute('aria-pressed', 'false')

    await userEvent.click(button())
    expect(button()).toHaveAttribute('aria-pressed', 'true')
    // ほかの人の操作も含めた最新の数
    expect(await screen.findByText('👍 参考になった 20')).toBeInTheDocument()
  })

  // H-2
  it('押した状態でもう一度押すと取り消して数を 1 減らす', async () => {
    let answer: (() => void) | undefined
    mockFetch({
      'DELETE /api/reviews/1234/helpful': () => new Promise<Response>((resolve) => {
        answer = () => resolve(json(200, { helpful: false, helpfulCount: 11 }))
      }),
    })
    renderComponent(<HelpfulButton review={others({ helpfulByMe: true })} onDelete={vi.fn()} />)
    await userEvent.click(button())
    expect(button()).toHaveTextContent('👍 参考になった 11')
    expect(button()).toHaveAttribute('aria-pressed', 'false')
    await act(async () => answer?.())
    expect(button()).toHaveTextContent('👍 参考になった 11')
  })

  // H-4・TC-05-3
  it('自分のレビューではボタンを出さず件数だけ', () => {
    renderComponent(<HelpfulButton review={review({ mine: true, helpfulCount: 3 })} onDelete={vi.fn()} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByLabelText('参考になった 3 件')).toHaveTextContent('👍 3')
  })

  // TC-05-4
  it('失敗したら元の状態と数に戻して M-30', async () => {
    mockFetch({ 'PUT /api/reviews/1234/helpful': () => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }) })
    renderComponent(<HelpfulButton review={others()} onDelete={vi.fn()} />)
    await userEvent.click(button())
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(button()).toHaveTextContent('👍 参考になった 12')
    expect(button()).toHaveAttribute('aria-pressed', 'false')
  })

  // FN-05 9 章
  it('レビューが削除されていたら M-20 を出してカードを消す', async () => {
    mockFetch({
      'PUT /api/reviews/1234/helpful': () => json(404, { code: 'REVIEW_NOT_FOUND', message: M[20], field: null }),
    })
    const onDelete = vi.fn()
    renderComponent(<HelpfulButton review={others()} onDelete={onDelete} />)
    await userEvent.click(button())
    expect(await screen.findByText(M[20])).toBeInTheDocument()
    expect(onDelete).toHaveBeenCalledWith(1234)
  })

  // TC-05-1：送っている間に何回押されても、最後の状態だけを送り直す
  it('素早く何回押しても最後に押した状態に合う', async () => {
    const answers: (() => void)[] = []
    const pending = (result: { helpful: boolean, helpfulCount: number }) => () =>
      new Promise<Response>((resolve) => answers.push(() => resolve(json(200, result))))
    const fetch = mockFetch({
      'PUT /api/reviews/1234/helpful': pending({ helpful: true, helpfulCount: 13 }),
      'DELETE /api/reviews/1234/helpful': pending({ helpful: false, helpfulCount: 12 }),
    })
    renderComponent(<HelpfulButton review={others()} onDelete={vi.fn()} />)
    // 付ける → 取り消す → 付ける → 取り消す（最後は取り消した状態）
    for (let i = 0; i < 4; i++) await userEvent.click(button())
    expect(button()).toHaveTextContent('👍 参考になった 12')
    expect(fetch.keys()).toEqual(['PUT /api/reviews/1234/helpful'])

    // 1 回目（付ける）が返ったら、最後の状態（取り消す）を送り直す
    await act(async () => answers.shift()?.())
    expect(fetch.keys()).toEqual(['PUT /api/reviews/1234/helpful', 'DELETE /api/reviews/1234/helpful'])
    await act(async () => answers.shift()?.())
    expect(button()).toHaveTextContent('👍 参考になった 12')
    expect(button()).toHaveAttribute('aria-pressed', 'false')
  })
})
