import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useToast } from './ToastContext'
import { ToastProvider } from './ToastProvider'

// トースト（画面設計書 2.4）。下中央に 1 つだけ出し、「×」か 5 秒で消す

function Buttons() {
  const toast = useToast()
  return (
    <>
      <button type="button" onClick={() => toast('1 つ目')}>1 つ目</button>
      <button type="button" onClick={() => toast('2 つ目')}>2 つ目</button>
    </>
  )
}

describe('ToastProvider', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('出したメッセージを 5 秒で消す', () => {
    render(<ToastProvider><Buttons /></ToastProvider>)
    fireEvent.click(screen.getByRole('button', { name: '1 つ目' }))
    expect(screen.getByRole('status')).toHaveTextContent('1 つ目')

    act(() => vi.advanceTimersByTime(4999))
    expect(screen.getByRole('status')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('新しいものを出したら古いものは消し、5 秒を数え直す', () => {
    render(<ToastProvider><Buttons /></ToastProvider>)
    fireEvent.click(screen.getByRole('button', { name: '1 つ目' }))
    act(() => vi.advanceTimersByTime(3000))
    fireEvent.click(screen.getByRole('button', { name: '2 つ目' }))

    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('2 つ目')
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.getByRole('status')).toBeInTheDocument()
  })

  it('同じメッセージを続けて出しても 5 秒を数え直す', () => {
    render(<ToastProvider><Buttons /></ToastProvider>)
    fireEvent.click(screen.getByRole('button', { name: '1 つ目' }))
    act(() => vi.advanceTimersByTime(3000))
    fireEvent.click(screen.getByRole('button', { name: '1 つ目' }))
    act(() => vi.advanceTimersByTime(3000))
    expect(screen.getByRole('status')).toHaveTextContent('1 つ目')
  })

  it('「×」で消す', () => {
    render(<ToastProvider><Buttons /></ToastProvider>)
    fireEvent.click(screen.getByRole('button', { name: '1 つ目' }))
    fireEvent.click(screen.getByRole('button', { name: '閉じる' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('ToastProvider の外では使えない', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Buttons />)).toThrow('ToastProvider の中で使ってください')
  })
})
