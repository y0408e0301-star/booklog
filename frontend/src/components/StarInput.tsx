import { useId, type RefObject } from 'react'
import styles from './Stars.module.css'
import ui from './ui.module.css'

type Props = {
  // 選んでいなければ null
  value: number | null
  onChange: (value: number) => void
  // G-6 を開いたときにカーソルを置く 1 つ目の星
  firstRef?: RefObject<HTMLInputElement | null>
  // 選ばずに送ったときのメッセージ（M-16）
  error?: string
}

const VALUES = [1, 2, 3, 4, 5]

// G-6 の星の入力（画面設計書 2.8、RV-3）。1 つのグループとして、それぞれを「星 1」〜「星 5」と読ませる。
// 押した星まで塗る。ラジオボタンなので、左右の矢印キーで 1 つずつ変えられる
export default function StarInput({ value, onChange, firstRef, error }: Props) {
  const id = useId()
  return (
    <fieldset className={styles.fieldset} aria-describedby={error ? `${id}-error` : undefined}>
      <legend className={styles.legend}>星（必須）</legend>
      <div className={styles.input}>
        <div className={styles.buttons}>
          {VALUES.map((n) => (
            <label key={n} className={value !== null && n <= value ? `${styles.star} ${styles.on}` : styles.star}>
              <input type="radio" name={`${id}-rating`} value={n} className={ui.srOnly} aria-label={`星 ${n}`}
                checked={value === n} onChange={() => onChange(n)}
                ref={n === 1 ? firstRef : undefined} />
              <span aria-hidden="true">{value !== null && n <= value ? '★' : '☆'}</span>
            </label>
          ))}
        </div>
        <span className={styles.selected}>{value === null ? '選んでいません' : `${value}（5 段階中）`}</span>
      </div>
      {error && <p className={styles.error} id={`${id}-error`}>{error}</p>}
    </fieldset>
  )
}
