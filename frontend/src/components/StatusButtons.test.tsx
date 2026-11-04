import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ReadingStatus } from '../api'
import { M } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import StatusButtons from './StatusButtons'
import { ToastProvider } from './ToastProvider'

// G-5 の読書ステータスのボタン（FN-08 ST-1〜ST-3・ST-8）

function renderButtons(initial: ReadingStatus | null = null) {
  return render(<ToastProvider><StatusButtons bookId={56} initial={initial} /></ToastProvider>)
}

const button = (name: string) => screen.getByRole('button', { name })
const pressed = () => screen.getAllByRole('button', { pressed: true }).map((b) => b.textContent)

const ok = (status: ReadingStatus | null) => () => json(200, { status })

describe('StatusButtons', () => {
  // ST-1
  it('3 つのボタンを並べ、今のステータスだけを押した状態で出す。本棚になければどれも押していない', () => {
    const { unmount } = renderButtons('READING')
    expect(screen.getByRole('group', { name: '読書ステータス' })).toBeInTheDocument()
    expect(pressed()).toEqual(['読書中'])
    expect(button('読みたい')).toHaveAttribute('aria-pressed', 'false')
    unmount()

    renderButtons(null)
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0)
  })

  // ST-2・ST-8（TC-08-1）
  it('押すとすぐに画面を変え、PUT で送る。別のボタンを押すとステータスを変える', async () => {
    const fetch = mockFetch({ 'PUT /api/books/56/status': [ok('WANT'), ok('READ')] })
    renderButtons()
    await userEvent.click(button('読みたい'))
    expect(pressed()).toEqual(['読みたい'])
    expect(sentJson(fetch)).toEqual({ status: 'WANT' })

    await userEvent.click(button('読了'))
    expect(pressed()).toEqual(['読了'])
    expect(sentJson(fetch, 1)).toEqual({ status: 'READ' })
  })

  // ST-3（TC-08-2）
  it('押しているボタンをもう一度押すと、確認を出さずに外す', async () => {
    const fetch = mockFetch({ 'DELETE /api/books/56/status': ok(null) })
    renderButtons('READ')
    await userEvent.click(button('読了'))
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(fetch.keys()).toEqual(['DELETE /api/books/56/status'])
  })

  // ST-8
  it('失敗したら押す前の状態に戻して M-30', async () => {
    mockFetch({ 'PUT /api/books/56/status': () => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }) })
    renderButtons('READING')
    await userEvent.click(button('読了'))
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(pressed()).toEqual(['読書中'])
  })

  // TC-08-4：送っている間に何回押されても、最後の状態だけを送り直す
  it('素早く何回押しても、最後に押した状態に合う', async () => {
    const answers: (() => void)[] = []
    const fetch = mockFetch({
      'PUT /api/books/56/status': () => new Promise<Response>((resolve) => answers.push(() => resolve(json(200, {})))),
      'DELETE /api/books/56/status': () => new Promise<Response>((resolve) => answers.push(() => resolve(json(200, {})))),
    })
    renderButtons()
    // 1 回目の PUT（読みたい）が返る前に、読書中 → 読了 → 読了（外す）→ 読みたい と押す
    await userEvent.click(button('読みたい'))
    await userEvent.click(button('読書中'))
    await userEvent.click(button('読了'))
    await userEvent.click(button('読了'))
    await userEvent.click(button('読みたい'))
    expect(pressed()).toEqual(['読みたい'])
    expect(fetch.keys()).toEqual(['PUT /api/books/56/status'])

    // 1 回目が返ったときの最後の状態は「読みたい」で、送った状態と同じなので送り直さない
    await act(async () => answers.shift()?.())
    expect(fetch.keys()).toEqual(['PUT /api/books/56/status'])
    expect(pressed()).toEqual(['読みたい'])
  })

  it('送っている間に違う状態にしたら、返った後に最後の状態を送り直す', async () => {
    const answers: (() => void)[] = []
    const fetch = mockFetch({
      'PUT /api/books/56/status': () => new Promise<Response>((resolve) => answers.push(() => resolve(json(200, {})))),
      'DELETE /api/books/56/status': () => new Promise<Response>((resolve) => answers.push(() => resolve(json(200, {})))),
    })
    renderButtons()
    await userEvent.click(button('読みたい'))
    await userEvent.click(button('読書中'))
    await userEvent.click(button('読書中'))
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0)

    await act(async () => answers.shift()?.())
    expect(fetch.keys()).toEqual(['PUT /api/books/56/status', 'DELETE /api/books/56/status'])
    await act(async () => answers.shift()?.())
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0)
  })
})
