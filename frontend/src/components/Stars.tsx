import styles from './Stars.module.css'
import ui from './ui.module.css'

type Props = {
  // レビューの星（1〜5 の整数）か、本の平均評価（小数点以下 1 桁）
  value: number
  // 16px（カード・本棚）か 24px（G-5 の平均評価）
  size: 16 | 24
  // 平均評価は「4.2」のように小数点以下 1 桁で出す
  average?: boolean
}

// 星の表示（画面設計書 2.8）。「★★★★☆ 4」のように星 5 つと数字を並べる。
// 塗るのは値を四捨五入した数。読み上げでは星の形を読ませず「星 4（5 段階中）」と読ませる（N-24）
export default function Stars({ value, size, average = false }: Props) {
  const filled = Math.round(value)
  const label = average ? value.toFixed(1) : String(value)
  return (
    <span className={styles.stars} style={{ fontSize: size }}>
      <span className={ui.srOnly}>星 {label}（5 段階中）</span>
      <span aria-hidden="true">
        {[1, 2, 3, 4, 5].map((n) => (
          <span key={n} className={n <= filled ? styles.filled : styles.empty}>{n <= filled ? '★' : '☆'}</span>
        ))}
      </span>
      <span className={styles.number} aria-hidden="true">{label}</span>
    </span>
  )
}
