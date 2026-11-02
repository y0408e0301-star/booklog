// 日付・日時の表示（画面設計書 2.13）。API の日時は UTC なので、日本時間に直して出す

const parts = new Intl.DateTimeFormat('ja-JP', {
  timeZone: 'Asia/Tokyo',
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function jst(date: Date) {
  const get = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.formatToParts(date).find((p) => p.type === type)?.value)
  return { year: get('year'), month: get('month'), day: get('day'), hour: get('hour'), minute: get('minute') }
}

const pad = (n: number) => String(n).padStart(2, '0')

// 「2026/10/16 15:30」（日本時間、24 時間表記）。日時にマウスを乗せたときに出す
export function fullTime(iso: string): string {
  const t = jst(new Date(iso))
  return `${t.year}/${pad(t.month)}/${pad(t.day)} ${pad(t.hour)}:${pad(t.minute)}`
}

// 「たった今」「3 分前」「5 時間前」「10月16日」「2025年10月16日」
export function relativeTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  const diff = now.getTime() - date.getTime()
  if (diff < 60_000) return 'たった今'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 時間前`
  const t = jst(date)
  const md = `${t.month}月${t.day}日`
  return t.year === jst(now).year ? md : `${t.year}年${md}`
}
