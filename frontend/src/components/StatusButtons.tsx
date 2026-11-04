import { useRef, useState } from 'react'
import { removeReadingStatus, setReadingStatus, type ReadingStatus } from '../api'
import { M } from '../messages'
import styles from './StatusButtons.module.css'
import { useToast } from './ToastContext'

const LABELS: { status: ReadingStatus, label: string }[] = [
  { status: 'WANT', label: '読みたい' },
  { status: 'READING', label: '読書中' },
  { status: 'READ', label: '読了' },
]

// G-5 の読書ステータスの切り替えボタン（画面設計書 7 章、FN-08 ST-1〜ST-3・ST-8）。
// 押してから反映までを 0.5 秒以内（N-4）にするため、API の応答を待たずに画面を変える（FN-08 3 章）
export default function StatusButtons({ bookId, initial }: { bookId: number, initial: ReadingStatus | null }) {
  const toast = useToast()
  // API が受け付けた状態。失敗したらここに戻す
  const [saved, setSaved] = useState(initial)
  // 送っている間だけ、押した後の状態を出す。undefined なら saved を出す
  const [pending, setPending] = useState<ReadingStatus | null | undefined>(undefined)
  // 最後に押した状態。送っている間に押されたら、ここだけを書き換える
  const wantRef = useRef<ReadingStatus | null | undefined>(undefined)
  const sendingRef = useRef(false)

  const current = pending === undefined ? saved : pending

  async function choose(status: ReadingStatus) {
    // 選んでいるボタンをもう一度押すと外す（ST-3）
    const want = (wantRef.current === undefined ? current : wantRef.current) === status ? null : status
    wantRef.current = want
    setPending(want)
    if (sendingRef.current) return
    sendingRef.current = true
    try {
      // 送っている間に押されて状態が変わっていたら、最後の状態だけを送り直す。
      // PUT・DELETE は何回送っても同じ結果になるので、送り直してよい（FN-08 5 章の 2）
      let sent: ReadingStatus | null
      do {
        sent = wantRef.current ?? null
        await (sent === null ? removeReadingStatus(bookId) : setReadingStatus(bookId, sent))
      } while (wantRef.current !== sent)
      setSaved(sent)
    } catch {
      // 押す前の状態に戻す
      toast(M[30])
    } finally {
      sendingRef.current = false
      wantRef.current = undefined
      setPending(undefined)
    }
  }

  return (
    <fieldset className={styles.buttons} aria-label="読書ステータス">
      {LABELS.map(({ status, label }) => (
        <button key={status} type="button" className={current === status ? `${styles.button} ${styles.on}` : styles.button}
          aria-pressed={current === status} onClick={() => void choose(status)}>
          {label}
        </button>
      ))}
    </fieldset>
  )
}
