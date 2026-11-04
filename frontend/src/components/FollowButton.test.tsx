import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { M } from '../messages'
import { json, mockFetch } from '../test/fetchMock'
import FollowButton from './FollowButton'
import { ToastProvider } from './ToastProvider'

// フォローボタン（FN-07 F-1・F-2、画面設計書 2.3）

function renderButton(following: boolean, onChange = vi.fn()) {
  render(<ToastProvider><FollowButton username="hanako" following={following} onChange={onChange} /></ToastProvider>)
  return onChange
}

// トーストの「×」と分けるため、名前で探す
const button = () => screen.getByRole('button', { name: /フォロー/ })

describe('FollowButton', () => {
  // F-1
  it('「フォローする」を押すとすぐに「フォロー中」にし、返ってきたフォロワー数を知らせる', async () => {
    mockFetch({ 'PUT /api/users/hanako/follow': () => json(200, { following: true, followerCount: 31 }) })
    const onChange = renderButton(false)
    expect(button()).toHaveTextContent('フォローする')
    await userEvent.click(button())
    expect(button()).toHaveTextContent(/^フォロー中/)
    expect(button()).toHaveAttribute('aria-pressed', 'true')
    await vi.waitFor(() => expect(onChange).toHaveBeenCalledWith({ following: true, followerCount: 31 }))
  })

  // F-2：確認は出さない
  it('「フォロー中」を押すと確認を出さずに解除する', async () => {
    const fetch = mockFetch({ 'DELETE /api/users/hanako/follow': () => json(200, { following: false, followerCount: 30 }) })
    renderButton(true)
    await userEvent.click(button())
    expect(button()).toHaveTextContent('フォローする')
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await vi.waitFor(() => expect(fetch.keys()).toEqual(['DELETE /api/users/hanako/follow']))
  })

  it('失敗したら元に戻して M-30、相手が退会していたら M-28', async () => {
    mockFetch({
      'PUT /api/users/hanako/follow': [
        () => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }),
        () => json(404, { code: 'USER_NOT_FOUND', message: M[28], field: null }),
      ],
    })
    renderButton(false)
    await userEvent.click(button())
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(button()).toHaveTextContent('フォローする')

    await userEvent.click(button())
    expect(await screen.findByText(M[28])).toBeInTheDocument()
    expect(button()).toHaveTextContent('フォローする')
  })

  // TC-07-2：送っている間に何回押されても、最後の状態だけを送り直す
  it('素早く何回押しても最後に押した状態に合う', async () => {
    const answers: (() => void)[] = []
    const pending = (following: boolean) => () =>
      new Promise<Response>((resolve) => answers.push(() => resolve(json(200, { following, followerCount: 0 }))))
    const fetch = mockFetch({
      'PUT /api/users/hanako/follow': pending(true),
      'DELETE /api/users/hanako/follow': pending(false),
    })
    renderButton(false)
    // フォロー → 解除 → フォロー（最後はフォロー中）
    for (let i = 0; i < 3; i++) await userEvent.click(button())
    expect(button()).toHaveTextContent(/^フォロー中/)
    expect(fetch.keys()).toEqual(['PUT /api/users/hanako/follow'])

    // 1 回目（フォロー）が返ったときの最後の状態もフォローなので、送り直さない
    await act(async () => answers.shift()?.())
    expect(fetch.keys()).toEqual(['PUT /api/users/hanako/follow'])
    expect(button()).toHaveTextContent(/^フォロー中/)
  })
})
