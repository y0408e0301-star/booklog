import ui from './ui.module.css'

type Props = {
  displayName: string
  size: 32 | 40 | 80
}

// ユーザーを表す丸（画面設計書 2.10）。アイコン画像はなく、表示名の 1 文字目を出す（要件定義書 2.2）
export default function Avatar({ displayName, size }: Props) {
  const initial = Array.from(displayName)[0] ?? '?'
  return (
    <span className={ui.avatar} style={{ width: size, height: size, fontSize: Math.round(size * 0.4) }}>
      <span aria-hidden="true">{initial}</span>
    </span>
  )
}
