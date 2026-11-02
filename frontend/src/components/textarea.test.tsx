import { fireEvent, render, screen } from '@testing-library/react'
import { useRef, type KeyboardEvent } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { isSubmitKey, remainingText, useAutoGrow } from './textarea'

// 複数行の入力欄の決まり（画面設計書 2.12・8 章）

// キーを押したときに isSubmitKey が返した値を渡す
function Probe({ onResult }: { onResult: (submit: boolean) => void }) {
  return <textarea aria-label="本文" onKeyDown={(e: KeyboardEvent) => onResult(isSubmitKey(e))} />
}

function press(init: KeyboardEventInit & { isComposing?: boolean }) {
  const onResult = vi.fn()
  const { unmount } = render(<Probe onResult={onResult} />)
  fireEvent.keyDown(screen.getByRole('textbox', { name: '本文' }), init)
  unmount()
  return onResult.mock.calls[0][0] as boolean
}

// 1 行 20px、上下の余白 8px ずつ、枠線 1px ずつ。scrollHeight は内容の高さ（余白を含み、枠線を含まない）
function Grow({ scrollHeight }: { scrollHeight: number }) {
  const ref = useRef<HTMLTextAreaElement>(null)
  useAutoGrow(ref, 10)
  return (
    <textarea aria-label="本文" ref={(el) => {
      ref.current = el
      // jsdom は描画しないので、内容の高さを決めておく
      if (el) Object.defineProperty(el, 'scrollHeight', { configurable: true, value: scrollHeight })
    }} style={{
      lineHeight: '20px', paddingTop: '8px', paddingBottom: '8px',
      borderStyle: 'solid', borderTopWidth: '1px', borderBottomWidth: '1px',
    }} />
  )
}

describe('remainingText', () => {
  it('残りがあれば「残り 〇」、超えたら負の数だけ', () => {
    expect(remainingText(2000)).toBe('残り 2,000')
    expect(remainingText(0)).toBe('残り 0')
    expect(remainingText(-12)).toBe('-12')
  })
})

describe('isSubmitKey', () => {
  it('Ctrl＋Enter と ⌘＋Enter で送る', () => {
    expect(press({ key: 'Enter', ctrlKey: true })).toBe(true)
    expect(press({ key: 'Enter', metaKey: true })).toBe(true)
  })

  it('Enter だけ・Shift＋Enter では改行する', () => {
    expect(press({ key: 'Enter' })).toBe(false)
    expect(press({ key: 'Enter', shiftKey: true })).toBe(false)
    expect(press({ key: 'a', ctrlKey: true })).toBe(false)
  })

  it('日本語入力の変換を確定する Enter では送らない', () => {
    expect(press({ key: 'Enter', ctrlKey: true, isComposing: true })).toBe(false)
  })
})

describe('useAutoGrow', () => {
  it('内容の高さに枠線を足した高さにする', () => {
    render(<Grow scrollHeight={56} />)
    expect(screen.getByRole('textbox', { name: '本文' })).toHaveStyle({ height: '58px' })
  })

  it('10 行（と余白）より高くはしない', () => {
    render(<Grow scrollHeight={1000} />)
    // 20px × 10 行 ＋ 余白 16px ＋ 枠線 2px
    expect(screen.getByRole('textbox', { name: '本文' })).toHaveStyle({ height: '218px' })
  })
})

