import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ReviewPhoto } from '../api'
import PhotoDialog from './PhotoDialog'

// G-11 写真の拡大ダイアログ（画面設計書 13 章、FN-04 PH-7）

const photos: ReviewPhoto[] = [1, 2, 3].map((id) => ({ id, url: `https://s3/posts/${id}.png`, expiresAt: '2999-01-01T00:00:00Z' }))

function Dialog({ list = photos, start = 0, onClose = () => {}, onError = () => {} }: {
  list?: ReviewPhoto[]
  start?: number
  onClose?: () => void
  onError?: () => void
}) {
  const [index, setIndex] = useState(start)
  return <PhotoDialog photos={list} index={index} onIndexChange={setIndex} onClose={onClose} onError={onError} />
}

const shown = () => screen.getByRole('img')

describe('PhotoDialog', () => {
  it('開いたら「×」にカーソルを置き、何枚目かを出す', () => {
    render(<Dialog start={1} />)
    expect(screen.getByRole('button', { name: '閉じる' })).toHaveFocus()
    expect(shown()).toHaveAccessibleName('写真 2 / 3')
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
  })

  // PH-7
  it('「‹」「›」と左右の矢印キーで切り替え、端まで行ったら反対の端に戻る', async () => {
    render(<Dialog />)
    await userEvent.click(screen.getByRole('button', { name: '前の写真' }))
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/3.png')
    await userEvent.click(screen.getByRole('button', { name: '次の写真' }))
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/1.png')
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/3.png')
    await userEvent.keyboard('{ArrowRight}')
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/1.png')
    await userEvent.keyboard('{ArrowLeft}')
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/3.png')
  })

  it('1 枚のときは「‹」「›」と枚数を出さず、矢印キーでも変わらない', async () => {
    render(<Dialog list={photos.slice(0, 1)} />)
    expect(screen.queryByRole('button', { name: '次の写真' })).not.toBeInTheDocument()
    expect(screen.queryByText('1 / 1')).not.toBeInTheDocument()
    await userEvent.keyboard('{ArrowRight}')
    expect(shown()).toHaveAttribute('src', 'https://s3/posts/1.png')
  })

  // PH-7
  it('Esc キー・「×」・写真の外側のクリックで閉じる', async () => {
    const onClose = vi.fn()
    render(<Dialog onClose={onClose} />)
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    await userEvent.click(screen.getByRole('dialog').parentElement!)
    expect(onClose).toHaveBeenCalledTimes(3)
  })

  it('写真を押しても閉じない', async () => {
    const onClose = vi.fn()
    render(<Dialog onClose={onClose} />)
    await userEvent.click(shown())
    expect(onClose).not.toHaveBeenCalled()
  })

  it('Tab キーのカーソルはダイアログのボタンの間だけで動き、Shift＋Tab で戻る', async () => {
    render(<Dialog />)
    await userEvent.tab()
    expect(screen.getByRole('button', { name: '前の写真' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: '次の写真' })).toHaveFocus()
    await userEvent.tab()
    expect(screen.getByRole('button', { name: '閉じる' })).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(screen.getByRole('button', { name: '次の写真' })).toHaveFocus()
  })

  it('写真を読めなかったら onError を呼ぶ', () => {
    const onError = vi.fn()
    render(<Dialog onError={onError} />)
    fireEvent.error(shown())
    expect(onError).toHaveBeenCalledOnce()
  })

  it('開いている間は後ろの画面をスクロールさせない', () => {
    const { unmount } = render(<Dialog />)
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('')
  })
})
