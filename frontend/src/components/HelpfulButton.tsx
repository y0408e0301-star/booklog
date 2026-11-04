import { useRef, useState } from 'react'
import { ApiError, markHelpful, unmarkHelpful, type HelpfulResult, type ReviewView } from '../api'
import { M } from '../messages'
import styles from './ReviewCard.module.css'
import { useToast } from './ToastContext'

type Props = {
  review: ReviewView
  // レビューが削除されていたら、カードを一覧から消す（FN-05 9 章）
  onDelete: (id: number) => void
}

// レビューのカードの「参考になった」（画面設計書 2.7、FN-05 H-1〜H-5）。
// 押してから反映までを 0.5 秒以内（N-4）にするため、API の応答を待たずに画面を変える（FN-05 3 章）
export default function HelpfulButton({ review, onDelete }: Props) {
  const toast = useToast()
  // API が受け付けた状態。レビューが読み直されたら（一覧の読み直しなど）、その値に戻す
  const initial = { helpful: review.helpfulByMe, helpfulCount: review.helpfulCount }
  const [saved, setSaved] = useState({ review, result: initial })
  const confirmed = saved.review === review ? saved.result : initial
  // 送っている間だけ、押した後の状態を出す。null なら confirmed を出す
  const [pending, setPending] = useState<HelpfulResult | null>(null)
  // 最後に押した状態。送っている間に押されたら、ここだけを書き換える
  const wantRef = useRef<boolean | null>(null)
  const sendingRef = useRef(false)

  const { helpful, helpfulCount } = pending ?? confirmed

  async function toggle() {
    const want = !(wantRef.current ?? helpful)
    wantRef.current = want
    setPending((prev) => {
      const current = prev ?? confirmed
      const delta = want === current.helpful ? 0 : want ? 1 : -1
      return { helpful: want, helpfulCount: Math.max(0, current.helpfulCount + delta) }
    })
    if (sendingRef.current) return
    sendingRef.current = true
    try {
      // 送っている間に押されて状態が変わっていたら、最後の状態だけを送り直す。
      // PUT・DELETE は何回送っても同じ結果になるので、送り直してよい（FN-05 5 章の 2）
      let sent: boolean
      let result: HelpfulResult
      do {
        sent = wantRef.current ?? want
        result = await (sent ? markHelpful(review.id) : unmarkHelpful(review.id))
      } while (wantRef.current !== sent)
      // 返ってきた helpfulCount で、ほかの人の操作も含めた最新の数に合わせる
      setSaved({ review, result })
    } catch (e) {
      // 押す前の状態と数に戻す
      if (e instanceof ApiError && e.code === 'REVIEW_NOT_FOUND') {
        toast(M[20])
        onDelete(review.id)
      } else {
        toast(M[30])
      }
    } finally {
      sendingRef.current = false
      wantRef.current = null
      setPending(null)
    }
  }

  // 自分のレビューには付けられない。ボタンの代わりに件数だけを出す（H-4）
  if (review.mine) {
    return <span className={styles.action} aria-label={`参考になった ${helpfulCount} 件`}>👍 {helpfulCount}</span>
  }
  return (
    <button type="button" className={helpful ? `${styles.helpful} ${styles.helpfulOn}` : styles.helpful}
      aria-pressed={helpful} onClick={() => void toggle()}>
      👍 参考になった {helpfulCount}
    </button>
  )
}
