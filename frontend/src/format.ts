// 本の出版日を画面に出す形にそろえる（画面設計書 2.13）。日時の「3 分前」などは time.ts

// 本の出版日は、API が返したまま「2024」「2024年5月」「2024年5月10日」の形で出す
export function formatPublishedDate(date: string): string {
  const [year, month, day] = date.split('-')
  if (!month) return year
  if (!day) return `${year}年${Number(month)}月`
  return `${year}年${Number(month)}月${Number(day)}日`
}

// 「出版社 · 出版日」。ない項目は出さず、どちらもなければ null（画面設計書 6 章・7 章）
export function publisherAndDate(publisher: string | null, publishedDate: string | null): string | null {
  const parts = [publisher, publishedDate === null ? null : formatPublishedDate(publishedDate)]
    .filter((part): part is string => part !== null && part !== '')
  return parts.length === 0 ? null : parts.join(' · ')
}
