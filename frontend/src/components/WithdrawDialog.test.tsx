import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { M } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { renderComponent } from '../test/render'
import WithdrawDialog from './WithdrawDialog'

// G-13 退会の確認ダイアログ（FN-01 A-10・A-11、TC-01-13）

function renderDialog(routes: Parameters<typeof mockFetch>[0] = {}) {
  const fetch = mockFetch(routes)
  const onCancel = vi.fn()
  const signedOut = vi.fn()
  renderComponent(<WithdrawDialog onCancel={onCancel} />, { auth: { signedOut } })
  const dialog = screen.getByRole('alertdialog', { name: '退会' })
  return {
    fetch,
    onCancel,
    signedOut,
    dialog,
    password: within(dialog).getByLabelText('パスワード'),
    cancel: within(dialog).getByRole('button', { name: 'キャンセル' }),
    submit: within(dialog).getByRole('button', { name: '退会する' }),
  }
}

describe('退会の確認ダイアログ（G-13）', () => {
  it('M-9 を出し、「キャンセル」にカーソルを置く。パスワードが空なら「退会する」を押せない', async () => {
    const { dialog, password, cancel, submit } = renderDialog()
    expect(dialog).toHaveAccessibleDescription(M[9])
    expect(cancel).toHaveFocus()
    expect(submit).toBeDisabled()
    await userEvent.type(password, 'password1')
    expect(submit).toBeEnabled()
    expect(password).toHaveAttribute('type', 'password')
  })

  it('パスワードを入れて「退会する」を押すと API に送り、M-11 を出して G-1 に移す', async () => {
    const { fetch, signedOut, password, submit } = renderDialog({
      'DELETE /api/me': () => new Response(null, { status: 204 }),
    })
    await userEvent.type(password, 'password1')
    await userEvent.click(submit)
    await waitFor(() => expect(signedOut).toHaveBeenCalledOnce())
    expect(sentJson(fetch)).toEqual({ password: 'password1' })
    expect(screen.getByText(M[11])).toBeInTheDocument()
  })

  it('パスワードの欄の Enter キーでも送る', async () => {
    const { fetch, password } = renderDialog({ 'DELETE /api/me': () => new Response(null, { status: 204 }) })
    await userEvent.type(password, 'password1{Enter}')
    await waitFor(() => expect(fetch.keys()).toContain('DELETE /api/me'))
  })

  it('パスワードが違えば欄の下に M-10 を出し、退会しない。入力し直すと消える', async () => {
    const { signedOut, password, submit } = renderDialog({
      'DELETE /api/me': () => json(400, { code: 'PASSWORD_WRONG', message: 'パスワードが違います。', field: 'password' }),
    })
    await userEvent.type(password, 'wrong')
    await userEvent.click(submit)
    expect(await screen.findByText('パスワードが違います。')).toBeInTheDocument()
    expect(signedOut).not.toHaveBeenCalled()
    expect(password).toHaveFocus()
    expect(password).toHaveAttribute('aria-invalid', 'true')
    await userEvent.type(password, 'x')
    expect(screen.queryByText('パスワードが違います。')).not.toBeInTheDocument()
  })

  it('失敗したら M-30 を出し、ダイアログは開いたまま', async () => {
    const { signedOut, password, submit } = renderDialog({ 'DELETE /api/me': () => json(500, {}) })
    await userEvent.type(password, 'password1')
    await userEvent.click(submit)
    expect(await screen.findByText(M[30])).toBeInTheDocument()
    expect(signedOut).not.toHaveBeenCalled()
    expect(submit).toBeEnabled()
  })

  it('送信中は「退会中…」にして押せなくする', async () => {
    const { password, submit, cancel } = renderDialog({ 'DELETE /api/me': () => new Promise<Response>(() => {}) })
    await userEvent.type(password, 'password1')
    await userEvent.click(submit)
    expect(await screen.findByRole('button', { name: '退会中…' })).toBeDisabled()
    expect(cancel).toBeDisabled()
  })

  it('「キャンセル」と Esc キーでは何もせずに閉じる', async () => {
    const { fetch, onCancel, cancel } = renderDialog()
    await userEvent.click(cancel)
    expect(onCancel).toHaveBeenCalledOnce()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalledTimes(2)
    expect(fetch.calls).toHaveLength(0)
  })

  it('Tab キーのカーソルはダイアログの中だけで動く', async () => {
    const { password, cancel } = renderDialog()
    expect(cancel).toHaveFocus()
    // 「退会する」は押せないので飛ばす
    await userEvent.tab()
    expect(password).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(cancel).toHaveFocus()
  })
})
