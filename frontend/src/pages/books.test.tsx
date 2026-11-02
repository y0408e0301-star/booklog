import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, BookSearchItem } from '../api'
import { M, m13 } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { renderComponent } from '../test/render'
import BookPage from './BookPage'
import BookSearchPage from './BookSearchPage'

// 書籍検索の結果画面（G-4。FN-02）と本のページ（G-5。FN-10 の本の情報）。10 章のテストの観点のうち、画面で確かめるもの

const SEARCH = 'GET /api/books/search?q=%E5%A4%8F%E7%9B%AE%E6%BC%B1%E7%9F%B3'
const SEARCH_PAGE_2 = `${SEARCH}&page=2`
const G4 = '/books/search?q=%E5%A4%8F%E7%9B%AE%E6%BC%B1%E7%9F%B3'

const unavailable = () => json(503, { code: 'BOOK_SEARCH_UNAVAILABLE', message: M[14], field: null })

function item(id: string, overrides: Partial<BookSearchItem> = {}): BookSearchItem {
  return {
    googleBooksId: id, title: `書名 ${id}`, authors: ['夏目漱石'], publisher: '新潮社', publishedDate: '2003-06',
    isbn13: null, thumbnailUrl: `https://books.google.com/${id}`, ...overrides,
  }
}

const items = (from: number, count: number) => Array.from({ length: count }, (_, i) => item(`b${from + i}`))

const rows = () => within(screen.getByRole('list', { name: '検索結果' })).queryAllByRole('button')
const location = () => screen.getByRole('status', { name: '今の URL' })

describe('書籍検索の結果画面（G-4）', () => {
  // TC-02-11
  it('URL の文字で探し、表紙・書名・著者・出版社と出版日を出す', async () => {
    mockFetch({ [SEARCH]: () => json(200, { items: [item('a')], hasNext: false }) })
    renderComponent(<BookSearchPage />, { path: G4 })

    expect(screen.getByRole('heading', { name: '「夏目漱石」の検索結果' })).toBeInTheDocument()
    expect(screen.getByText('検索中…')).toBeInTheDocument()
    const row = await screen.findByRole('button', { name: /書名 a/ })
    expect(within(row).getByRole('img', { name: '書名 aの表紙' })).toBeInTheDocument()
    expect(row).toHaveTextContent('夏目漱石')
    expect(row).toHaveTextContent('新潮社 · 2003年6月')
    expect(screen.queryByText('検索中…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'もっと見る' })).not.toBeInTheDocument()
  })

  // TC-02-8：表紙のない本・ない項目
  it('表紙がなければ「表紙なし」を出し、ない項目は出さない', async () => {
    mockFetch({
      [SEARCH]: () => json(200, {
        items: [item('a', { thumbnailUrl: null, authors: [], publisher: null, publishedDate: null })], hasNext: false,
      }),
    })
    renderComponent(<BookSearchPage />, { path: G4 })
    const row = await screen.findByRole('button', { name: /書名 a/ })
    expect(row).toHaveTextContent(/^表紙なし書名 a$/)
  })

  // TC-02-3
  it('「もっと見る」で次の 20 件を足し、続きがなければ出さない', async () => {
    const fetch = mockFetch({
      [SEARCH]: () => json(200, { items: items(1, 20), hasNext: true }),
      [SEARCH_PAGE_2]: () => json(200, { items: items(21, 20), hasNext: false }),
    })
    renderComponent(<BookSearchPage />, { path: G4 })
    await userEvent.click(await screen.findByRole('button', { name: 'もっと見る' }))

    await screen.findByRole('button', { name: /書名 b40/ })
    expect(rows()).toHaveLength(40)
    expect(screen.queryByRole('button', { name: 'もっと見る' })).not.toBeInTheDocument()
    expect(fetch.keys()).toEqual([SEARCH, SEARCH_PAGE_2])
  })

  // TC-02-4（画面）
  it('0 件なら M-13', async () => {
    mockFetch({ [SEARCH]: () => json(200, { items: [], hasNext: false }) })
    renderComponent(<BookSearchPage />, { path: G4 })
    expect(await screen.findByText(m13('夏目漱石'))).toBeInTheDocument()
  })

  // TC-02-6
  it('Google Books API が使えなければ M-14 を出し、「再読み込み」で探し直す', async () => {
    mockFetch({ [SEARCH]: [unavailable, () => json(200, { items: [item('a')], hasNext: false })] })
    renderComponent(<BookSearchPage />, { path: G4 })

    expect(await screen.findByRole('alert')).toHaveTextContent(M[14])
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('button', { name: /書名 a/ })).toBeInTheDocument()
  })

  it('「もっと見る」で使えなかったときは、出した結果を残してボタンの場所に M-14 を出す', async () => {
    mockFetch({ [SEARCH]: () => json(200, { items: items(1, 20), hasNext: true }), [SEARCH_PAGE_2]: unavailable })
    renderComponent(<BookSearchPage />, { path: G4 })
    await userEvent.click(await screen.findByRole('button', { name: 'もっと見る' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(M[14])
    expect(rows()).toHaveLength(20)
  })

  it('つながらないなど、それ以外の失敗は M-29', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))
    renderComponent(<BookSearchPage />, { path: G4 })
    expect(await screen.findByRole('alert')).toHaveTextContent(M[29])
  })

  // B-5
  it('本を押すと取り込んで G-5 を開く。取り込んでいる間はその行に「開いています…」を出し、ほかの行も押せない', async () => {
    let finish: ((res: Response) => void) | undefined
    const fetch = mockFetch({
      [SEARCH]: () => json(200, { items: [item('a'), item('b')], hasNext: false }),
      'POST /api/books': () => new Promise<Response>((resolve) => {
        finish = resolve
      }),
    })
    renderComponent(<BookSearchPage />, { path: G4 })
    await userEvent.click(await screen.findByRole('button', { name: /書名 b/ }))

    expect(screen.getByRole('button', { name: /書名 b/ })).toHaveTextContent('開いています…')
    expect(rows().every((row) => (row as HTMLButtonElement).disabled)).toBe(true)
    expect(sentJson(fetch, 1)).toEqual({ googleBooksId: 'b' })

    finish?.(json(201, { id: 56 }))
    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent('/books/56')
  })

  it('選んだ本が Google Books にないときは M-15 をトーストで出し、一覧はそのまま', async () => {
    mockFetch({
      [SEARCH]: () => json(200, { items: [item('a')], hasNext: false }),
      'POST /api/books': () => json(404, { code: 'GOOGLE_BOOK_NOT_FOUND', message: M[15], field: null }),
    })
    renderComponent(<BookSearchPage />, { path: G4 })
    await userEvent.click(await screen.findByRole('button', { name: /書名 a/ }))

    expect(await screen.findByText(M[15])).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /書名 a/ })).toBeEnabled()
    expect(location()).toHaveTextContent(G4)
  })

  // TC-02-9
  it('書名の <script> は文字のまま出す', async () => {
    mockFetch({ [SEARCH]: () => json(200, { items: [item('a', { title: '<script>alert(1)</script>' })], hasNext: false }) })
    renderComponent(<BookSearchPage />, { path: G4 })
    expect(await screen.findByText('<script>alert(1)</script>')).toBeInTheDocument()
  })

  it('検索する文字がなければ何も出さず、探さない', () => {
    const fetch = mockFetch({})
    const { container } = renderComponent(<BookSearchPage />, { path: '/books/search?q=%20' })
    expect(container.querySelector('h1')).toBeNull()
    expect(fetch.calls).toHaveLength(0)
  })
})

function book(overrides: Partial<BookDetail> = {}): BookDetail {
  return {
    id: 56, title: '吾輩は猫である', authors: ['夏目漱石'], publisher: '新潮社', publishedDate: '2003-06-01',
    isbn13: '9784101010014', description: '猫の目から見た人間の話。', thumbnailUrl: 'https://books.google.com/a',
    averageRating: null, reviewCount: 0, myReviewId: null, ...overrides,
  }
}

// レビューの一覧は空（レビューの一覧のテストは reviews.test.tsx）
const noReviews = { 'GET /api/books/56/reviews': () => json(200, { items: [], nextCursor: null }) }

function renderBookPage(path = '/books/56') {
  return renderComponent(<BookPage />, { path, route: '/books/:id' })
}

describe('本のページ（G-5）', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('本の情報を出す（BK-1・BK-2）', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()), ...noReviews })
    renderBookPage()

    expect(await screen.findByRole('heading', { name: '吾輩は猫である' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '吾輩は猫であるの表紙' })).toHaveStyle({ width: '128px', height: '192px' })
    expect(screen.getByText('夏目漱石')).toBeInTheDocument()
    expect(screen.getByText('新潮社 · 2003年6月1日')).toBeInTheDocument()
    expect(screen.getByText('ISBN 9784101010014')).toBeInTheDocument()
    expect(screen.getByText('猫の目から見た人間の話。')).toBeInTheDocument()
  })

  // TC-10-3
  it('出版社・出版日・ISBN・紹介文・表紙がない本は、その行を出さず「表紙なし」を出す', async () => {
    mockFetch({
      'GET /api/books/56': () => json(200, book({
        authors: [], publisher: null, publishedDate: null, isbn13: null, description: null, thumbnailUrl: null,
      })),
      ...noReviews,
    })
    renderBookPage()

    const section = await screen.findByRole('region', { name: '吾輩は猫である' })
    // 書名・平均評価（0 件なので「—」）・「レビューを書く」だけが出る
    expect(section).toHaveTextContent(/^表紙なし吾輩は猫である—レビュー 0 件レビューを書く$/)
  })

  // TC-10-7
  it('紹介文の HTML の記号は文字のまま出し、改行はそのまま出す', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book({ description: '<b>太字</b> & 記号\n二行目' })), ...noReviews })
    renderBookPage()
    const description = await screen.findByText(/<b>太字<\/b> & 記号/)
    expect(description.textContent).toBe('<b>太字</b> & 記号\n二行目')
  })

  it('紹介文が 5 行に収まらなければ「続きを読む」で全部出し、「閉じる」で戻す', async () => {
    // jsdom は大きさを測らないので、5 行で切った紹介文が収まっていないことにする
    vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(200)
    vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(100)
    mockFetch({ 'GET /api/books/56': () => json(200, book()), ...noReviews })
    renderBookPage()

    const more = await screen.findByRole('button', { name: '続きを読む' })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(more)
    expect(screen.getByRole('button', { name: '閉じる' })).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(screen.getByRole('button', { name: '閉じる' }))
    expect(screen.getByRole('button', { name: '続きを読む' })).toBeInTheDocument()
  })

  it('5 行に収まれば「続きを読む」を出さない', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()), ...noReviews })
    renderBookPage()
    await screen.findByText('猫の目から見た人間の話。')
    expect(screen.queryByRole('button', { name: '続きを読む' })).not.toBeInTheDocument()
  })

  // TC-10-6
  it('存在しない本は M-15', async () => {
    mockFetch({ 'GET /api/books/999999': () => json(404, { code: 'BOOK_NOT_FOUND', message: M[15], field: null }) })
    renderBookPage('/books/999999')
    expect(await screen.findByText(M[15])).toBeInTheDocument()
  })

  it('数でない ID は API を呼ばずに M-15', async () => {
    const fetch = mockFetch({})
    renderBookPage('/books/abc')
    expect(await screen.findByText(M[15])).toBeInTheDocument()
    expect(fetch.calls).toHaveLength(0)
  })

  it('読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({
      'GET /api/books/56': [() => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }), () => json(200, book())],
      ...noReviews,
    })
    renderBookPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(M[29])
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('heading', { name: '吾輩は猫である' })).toBeInTheDocument()
  })
})
