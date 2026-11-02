import { useLayoutEffect, type KeyboardEvent, type RefObject } from 'react'

// 複数行の入力欄を、入力に合わせて maxLines 行まで高くする（G-6 の感想は 8 行から 20 行まで。画面設計書 8 章）。
// 入力した・空にしたなど、描画のたびに高さを測り直す。最低の高さは CSS の min-height で決める
export function useAutoGrow(ref: RefObject<HTMLTextAreaElement | null>, maxLines: number): void {
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    const style = getComputedStyle(el)
    const lineHeight = Number.parseFloat(style.lineHeight) || 22
    const padding = Number.parseFloat(style.paddingTop) + Number.parseFloat(style.paddingBottom) || 0
    // box-sizing: border-box なので、高さには枠線も含める（scrollHeight は枠線を含まない）
    const border = Number.parseFloat(style.borderTopWidth) + Number.parseFloat(style.borderBottomWidth) || 0
    el.style.height = `${Math.min(el.scrollHeight, lineHeight * maxLines + padding) + border}px`
  })
}

// 感想・コメント・自己紹介の入力欄では Enter キーで改行し、Ctrl＋Enter（macOS では ⌘＋Enter）で送る。
// 日本語入力の変換を確定するための Enter キーでは送らない（画面設計書 2.12）
export function isSubmitKey(e: KeyboardEvent): boolean {
  return e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.nativeEvent.isComposing
}

// 残りの文字数の表示。上限を超えたら「-12」のように負の数だけを出す（RV-4）
export function remainingText(remaining: number): string {
  return remaining < 0 ? String(remaining) : `残り ${remaining.toLocaleString('ja-JP')}`
}
