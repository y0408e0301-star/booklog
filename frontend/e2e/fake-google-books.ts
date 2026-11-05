import { createHash } from 'node:crypto'
import { createServer } from 'node:http'

// E2E で本物の代わりに呼ぶ Google Books API の偽物（技術スタック定義書 5.4）。Playwright の webServer で起動する。
// 問い合わせの文字ごとに決まった本を返す。テストの実行ごとに文字を変えれば、新しい本になる。
// 文字に次の言葉が入っていると、動きを変える：
//   障害：500 を返す（B-8）  なし：0 件（M-13）  多数：45 件（「もっと見る」）
//   記号：書名・紹介文に HTML の記号を入れる（N-17）  表紙：表紙の URL を付ける（テストが page.route で画像を返す）

const PORT = 9100
const PAGE_SIZE = 20

type Volume = { id: string, volumeInfo: Record<string, unknown> }

// 検索で返した本。選んだ本の取り込み（GET /volumes/{id}）で、同じものを返す
const volumes = new Map<string, Volume>()

function volume(q: string, index: number): Volume {
  // id は books.google_books_id（40 文字まで）に入る。「e2e-」で始まるものを cleanup.ts が消す
  const id = `e2e-${createHash('md5').update(q).digest('hex').slice(0, 16)}-${index}`
  const isbn = q.startsWith('isbn:') ? q.slice('isbn:'.length) : '9784000000000'
  const title = q.includes('記号') ? `<script>alert(1)</script>${q} ${index}巻` : `${q} ${index}巻`
  const found: Volume = {
    id,
    volumeInfo: {
      title,
      authors: ['E2E 著者'],
      publisher: 'E2E 出版',
      publishedDate: '2026-01-15',
      description: q.includes('記号') ? '紹介文に a < b & c > d の記号があります。' : 'E2E の本の紹介文です。',
      industryIdentifiers: [{ type: 'ISBN_13', identifier: isbn }],
      ...(q.includes('表紙') ? { imageLinks: { thumbnail: 'https://covers.e2e.test/cover.png' } } : {}),
    },
  }
  volumes.set(id, found)
  return found
}

function search(q: string, startIndex: number): Volume[] {
  if (q.includes('なし')) return []
  const total = q.startsWith('isbn:') ? 1 : q.includes('多数') ? 45 : 3
  const all = Array.from({ length: total }, (_, i) => volume(q, i + 1))
  return all.slice(startIndex, startIndex + PAGE_SIZE)
}

createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://localhost:${PORT}`)
  const send = (status: number, body: unknown) => {
    res.writeHead(status, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(body))
  }
  if (url.pathname === '/health') {
    send(200, { status: 'UP' })
  } else if (url.pathname === '/volumes') {
    const q = url.searchParams.get('q') ?? ''
    if (q.includes('障害')) send(500, { error: 'e2e' })
    else send(200, { items: search(q, Number(url.searchParams.get('startIndex') ?? 0)) })
  } else if (url.pathname.startsWith('/volumes/')) {
    const found = volumes.get(decodeURIComponent(url.pathname.slice('/volumes/'.length)))
    if (found) send(200, found)
    else send(404, { error: 'not found' })
  } else {
    send(404, { error: 'not found' })
  }
}).listen(PORT)
