import { useState } from 'react'
import styles from './BookCover.module.css'

type Props = {
  title: string
  thumbnailUrl: string | null
  // 画面設計書 2.9 の大きさ。レビューのカード・G-6：48×72、G-4：64×96、G-5：128×192
  width: 48 | 64 | 128
}

// 本の表紙（画面設計書 2.9）。Google Books の URL をそのまま読む（要件定義書 P-3）。
// URL がない・読めないときは「表紙なし」の枠を出す（B-7）
export default function BookCover({ title, thumbnailUrl, width }: Props) {
  // 読めなかった URL。別の本に変わって URL が変わったら、また画像を出す
  const [brokenUrl, setBrokenUrl] = useState<string | null>(null)
  const size = { width, height: width * 1.5 }
  if (thumbnailUrl && thumbnailUrl !== brokenUrl) {
    return (
      <img className={styles.cover} style={size} src={thumbnailUrl} alt={`${title}の表紙`}
        onError={() => setBrokenUrl(thumbnailUrl)} />
    )
  }
  return <span className={styles.none} style={size}>表紙なし</span>
}
