import { useRef, useState } from 'react'
import { ApiError, follow, unfollow, type FollowResult } from '../api'
import { M } from '../messages'
import styles from './FollowButton.module.css'
import { useToast } from './ToastContext'
import ui from './ui.module.css'

type Props = {
  username: string
  following: boolean
  // 送り終わったら、相手の最新のフォロワー数を知らせる（G-8 の件数）
  onChange?: (result: FollowResult) => void
}

// フォローボタン（画面設計書 2.3・2.11、FN-07 F-1・F-2）。「フォローする」は主ボタン、「フォロー中」は通常ボタンで、
// マウスを乗せている間だけ「フォロー解除」（危険色）にする。
// 押してから反映までを 0.5 秒以内（N-4）にするため、API の応答を待たずに画面を変える（FN-07 3 章）
export default function FollowButton({ username, following: initial, onChange }: Props) {
  const toast = useToast()
  // API が受け付けた状態。一覧が読み直されたら、その値に戻す
  const [saved, setSaved] = useState({ initial, following: initial })
  const confirmed = saved.initial === initial ? saved.following : initial
  // 送っている間だけ、押した後の状態を出す。null なら confirmed を出す
  const [pending, setPending] = useState<boolean | null>(null)
  // 最後に押した状態。送っている間に押されたら、ここだけを書き換える
  const wantRef = useRef<boolean | null>(null)
  const sendingRef = useRef(false)

  const following = pending ?? confirmed

  async function toggle() {
    const want = !(wantRef.current ?? following)
    wantRef.current = want
    setPending(want)
    if (sendingRef.current) return
    sendingRef.current = true
    try {
      // 送っている間に押されて状態が変わっていたら、最後の状態だけを送り直す。
      // PUT・DELETE は何回送っても同じ結果になるので、送り直してよい（FN-07 5 章の 2）
      let sent: boolean
      let result: FollowResult
      do {
        sent = wantRef.current ?? want
        result = await (sent ? follow(username) : unfollow(username))
      } while (wantRef.current !== sent)
      setSaved({ initial, following: result.following })
      onChange?.(result)
    } catch (e) {
      // 押す前の状態に戻す。相手が退会していたら M-28（FN-07 9 章）
      toast(e instanceof ApiError && e.code === 'USER_NOT_FOUND' ? M[28] : M[30])
    } finally {
      sendingRef.current = false
      wantRef.current = null
      setPending(null)
    }
  }

  return following
    ? (
      <button type="button" className={`${ui.secondary} ${styles.following}`} aria-pressed="true"
        onClick={() => void toggle()}>
        <span className={styles.label}>フォロー中</span>
        <span className={styles.hoverLabel} aria-hidden="true">フォロー解除</span>
      </button>
    )
    : (
      <button type="button" className={ui.primary} aria-pressed="false" onClick={() => void toggle()}>
        フォローする
      </button>
    )
}
