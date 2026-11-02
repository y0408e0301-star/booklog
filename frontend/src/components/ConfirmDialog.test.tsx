import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import ConfirmDialog from './ConfirmDialog'

// G-12 確認ダイアログ（画面設計書 2.5・14 章）

function Opener({ onConfirm, onCancel }: { onConfirm: () => Promise<void>, onCancel?: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>削除する</button>
      {open && (
        <ConfirmDialog title="レビューを削除" message="このレビューを削除しますか？" onConfirm={onConfirm}
          onCancel={() => {
            onCancel?.()
            setOpen(false)
          }} />
      )}
    </>
  )
}

async function openDialog(onConfirm = vi.fn(async () => {}), onCancel = vi.fn()) {
  render(<Opener onConfirm={onConfirm} onCancel={onCancel} />)
  await userEvent.click(screen.getByRole('button', { name: '削除する' }))
  return { dialog: screen.getByRole('alertdialog', { name: 'レビューを削除' }), onConfirm, onCancel }
}

describe('ConfirmDialog', () => {
  it('見出しとメッセージを出し、誤って削除しないよう「キャンセル」にカーソルを置く', async () => {
    const { dialog } = await openDialog()
    expect(dialog).toHaveAccessibleDescription('このレビューを削除しますか？')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByRole('button', { name: 'キャンセル' })).toHaveFocus()
  })

  it('開いている間は後ろの画面をスクロールさせず、閉じたら戻して元のボタンにカーソルを戻す', async () => {
    await openDialog()
    expect(document.body.style.overflow).toBe('hidden')
    await userEvent.click(screen.getByRole('button', { name: 'キャンセル' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(document.body.style.overflow).toBe('')
    expect(screen.getByRole('button', { name: '削除する' })).toHaveFocus()
  })

  it('Esc キーと覆いのクリックは「キャンセル」と同じ', async () => {
    const first = await openDialog()
    await userEvent.keyboard('{Escape}')
    expect(first.onCancel).toHaveBeenCalledOnce()

    await userEvent.click(screen.getByRole('button', { name: '削除する' }))
    const overlay = screen.getByRole('alertdialog').parentElement!
    await userEvent.click(overlay)
    expect(first.onCancel).toHaveBeenCalledTimes(2)
  })

  it('ダイアログの中のクリックでは閉じない', async () => {
    const { dialog, onCancel } = await openDialog()
    await userEvent.click(dialog)
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('Tab キーのカーソルは 2 つのボタンの間だけで動く', async () => {
    await openDialog()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: '削除' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: 'キャンセル' })).toHaveFocus()
  })

  it('「削除」を押すと終わるまで「削除中…」にし、キャンセルできない', async () => {
    let finish: (() => void) | undefined
    const onConfirm = vi.fn(() => new Promise<void>((resolve) => {
      finish = resolve
    }))
    const { onCancel } = await openDialog(onConfirm)

    await userEvent.click(screen.getByRole('button', { name: '削除' }))

    expect(onConfirm).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '削除中…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'キャンセル' })).toBeDisabled()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).not.toHaveBeenCalled()
    finish?.()
  })
})
