import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookDetail, ReviewView } from '../api'
import { M } from '../messages'
import { json, mockFetch, sentForm } from '../test/fetchMock'
import { mockIntersectionObserver } from '../test/intersection'
import { renderComponent, review } from '../test/render'
import BookPage from './BookPage'
import ReviewFormPage from './ReviewFormPage'
import ReviewPage from './ReviewPage'

// レビュー（FN-03）と本のページのレビューの一覧・平均評価（FN-10）。10 章のテストの観点のうち、画面で確かめるもの

function book(overrides: Partial<BookDetail> = {}): BookDetail {
  return {
    id: 56, title: 'こころ', authors: ['夏目漱石'], publisher: null, publishedDate: null, isbn13: null,
    description: null, thumbnailUrl: null, averageRating: null, reviewCount: 0, myReviewId: null, myStatus: null, ...overrides,
  }
}

const page = (items: ReviewView[], nextCursor: string | null = null) => () => json(200, { items, nextCursor })
const location = () => screen.getByRole('status', { name: '今の URL' })

describe('本のページの平均評価とレビューの一覧（G-5）', () => {
  // TC-10-1・BK-3
  // BK-6
  it('平均評価と件数を出し、0 件なら「—」と M-26', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()), 'GET /api/books/56/reviews': page([]) })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })

    expect(await screen.findByLabelText('平均評価なし')).toHaveTextContent('—')
    expect(screen.getByText('レビュー 0 件')).toBeInTheDocument()
    expect(await screen.findByText(M[26])).toBeInTheDocument()
  })

  it('平均評価は星と小数点以下 1 桁', async () => {
    mockFetch({
      'GET /api/books/56': () => json(200, book({ averageRating: 4.3, reviewCount: 3 })),
      'GET /api/books/56/reviews': page([review()]),
    })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    const head = await screen.findByRole('region', { name: 'こころ' })
    expect(head).toHaveTextContent('星 4.3（5 段階中）★★★★☆4.3レビュー 3 件')
    expect(screen.getByRole('heading', { name: 'レビュー（3 件）' })).toBeInTheDocument()
  })

  // TC-03-3・TC-10-4（RV-1）
  it('まだ書いていなければ「レビューを書く」、書いていれば「レビューを編集」', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()), 'GET /api/books/56/reviews': page([]) })
    const { unmount } = renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    expect(await screen.findByRole('link', { name: 'レビューを書く' })).toHaveAttribute('href', '/books/56/review')
    unmount()

    mockFetch({ 'GET /api/books/56': () => json(200, book({ myReviewId: 1234 })), 'GET /api/books/56/reviews': page([]) })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    expect(await screen.findByRole('link', { name: 'レビューを編集' })).toHaveAttribute('href', '/books/56/review')
  })

  // BK-5
  it('レビューを新しい順に並べ、本は出さず、一番下まで見たら次の 20 件を足す', async () => {
    const io = mockIntersectionObserver()
    const fetch = mockFetch({
      'GET /api/books/56': () => json(200, book({ reviewCount: 2 })),
      'GET /api/books/56/reviews': page([review({ id: 2, body: '新しい' })], '2'),
      'GET /api/books/56/reviews?cursor=2': page([review({ id: 1, body: '古い' })]),
    })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    await screen.findByText('新しい')
    expect(screen.queryByRole('link', { name: /こころ/ })).not.toBeInTheDocument()

    await vi.waitFor(() => expect(io.observing()).toBe(true))
    act(() => io.scrollToBottom())
    expect(await screen.findByText('古い')).toBeInTheDocument()
    expect(screen.getAllByRole('article').map((a) => a.textContent)).toEqual([
      expect.stringContaining('新しい'), expect.stringContaining('古い'),
    ])
    expect(fetch.keys()).toContain('GET /api/books/56/reviews?cursor=2')
  })

  it('一覧の読み込みに失敗したら M-29 と「再読み込み」', async () => {
    mockFetch({
      'GET /api/books/56': () => json(200, book()),
      'GET /api/books/56/reviews': [() => json(500, { code: 'INTERNAL_ERROR', message: M[30], field: null }), page([review()])],
    })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    expect(await screen.findByRole('alert')).toHaveTextContent(M[29])
    await userEvent.click(screen.getByRole('button', { name: '再読み込み' }))
    expect(await screen.findByRole('article')).toBeInTheDocument()
  })

  // TC-10-2
  it('自分のレビューを消すと一覧から消し、平均評価と件数を読み直す', async () => {
    mockFetch({
      'GET /api/books/56': [
        () => json(200, book({ averageRating: 4, reviewCount: 1, myReviewId: 1234 })),
        () => json(200, book()),
      ],
      'GET /api/books/56/reviews': page([review()]),
      'DELETE /api/reviews/1234': () => json(204),
    })
    renderComponent(<BookPage />, { path: '/books/56', route: '/books/:id' })
    await userEvent.click(await screen.findByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除' }))

    expect(await screen.findByRole('link', { name: 'レビューを書く' })).toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.getByText('レビュー 0 件')).toBeInTheDocument()
  })
})

function renderForm() {
  return renderComponent(<ReviewFormPage />, { path: '/books/56/review', route: '/books/:id/review' })
}

const field = (name: string) => screen.getByLabelText(name)

describe('レビューの投稿（G-6）', () => {
  it('本を出し、星の 1 つ目にカーソルを置く', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    expect(await screen.findByRole('heading', { name: 'レビューを書く' })).toBeInTheDocument()
    expect(screen.getByText('こころ')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '星 1' })).toHaveFocus()
    expect(screen.getByText('残り 2,000')).toBeInTheDocument()
  })

  // TC-03-1・TC-03-2（M-16・M-17）
  // RV-3
  it('星を選ばず感想が空なら M-16 と M-17 を出し、送らない', async () => {
    const fetch = mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    await userEvent.type(await screen.findByLabelText('感想（必須）'), '  \n ')
    await userEvent.click(screen.getByRole('button', { name: '投稿する' }))

    expect(screen.getByText(M[16])).toBeInTheDocument()
    expect(screen.getByText(M[17])).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '星 1' })).toHaveFocus()
    expect(fetch.keys()).toEqual(['GET /api/books/56'])

    // 星を選ぶと M-16 を消す
    await userEvent.click(screen.getByRole('radio', { name: '星 3' }))
    expect(screen.queryByText(M[16])).not.toBeInTheDocument()
  })

  // TC-03-2（RV-4）
  it('2,000 文字を超えたら残りを負の数で出し、投稿できない。入力はできる', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    await userEvent.click(await screen.findByLabelText('感想（必須）'))
    await userEvent.paste('あ'.repeat(2001))
    expect(screen.getByText('-1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '投稿する' })).toBeDisabled()

    await userEvent.keyboard('{Backspace}')
    expect(screen.getByText('残り 0')).toBeInTheDocument()
  })

  it('投稿すると星・トリムした感想・ネタバレありを送り、G-5 に戻る', async () => {
    const fetch = mockFetch({
      'GET /api/books/56': () => json(200, book()),
      'POST /api/books/56/reviews': () => json(201, review()),
    })
    renderForm()
    await userEvent.click(await screen.findByRole('radio', { name: '星 5' }))
    await userEvent.type(field('感想（必須）'), '  面白かった{Enter}二行目  ')
    await userEvent.click(screen.getByRole('checkbox', { name: 'ネタバレあり' }))
    await userEvent.click(screen.getByRole('button', { name: '投稿する' }))

    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/books\/56$/)
    const sent = sentForm(fetch, 1)
    expect(Object.fromEntries(sent)).toEqual({ rating: '5', body: '面白かった\n二行目', spoiler: 'true' })
  })

  it('感想の入力欄で Ctrl＋Enter を押すと投稿する', async () => {
    const fetch = mockFetch({
      'GET /api/books/56': () => json(200, book()),
      'POST /api/books/56/reviews': () => json(201, review()),
    })
    renderForm()
    await userEvent.click(await screen.findByRole('radio', { name: '星 2' }))
    await userEvent.type(field('感想（必須）'), 'いまいち{Control>}{Enter}{/Control}')
    await vi.waitFor(() => expect(fetch.keys()).toContain('POST /api/books/56/reviews'))
  })

  it('送信中は「投稿中…」にして押せない。失敗したら M-21 を出し、入力した内容を残す', async () => {
    let fail: (() => void) | undefined
    mockFetch({
      'GET /api/books/56': () => json(200, book()),
      'POST /api/books/56/reviews': () => new Promise<Response>((resolve) => {
        fail = () => resolve(json(500, { code: 'INTERNAL_ERROR', message: M[21], field: null }))
      }),
    })
    renderForm()
    await userEvent.click(await screen.findByRole('radio', { name: '星 4' }))
    await userEvent.type(field('感想（必須）'), '残しておく')
    await userEvent.click(screen.getByRole('button', { name: '投稿する' }))
    expect(screen.getByRole('button', { name: '投稿中…' })).toBeDisabled()

    act(() => fail?.())
    expect(await screen.findByText(M[21])).toBeInTheDocument()
    expect(field('感想（必須）')).toHaveValue('残しておく')
    expect(screen.getByRole('radio', { name: '星 4' })).toBeChecked()
    expect(location()).toHaveTextContent('/books/56/review')
  })

  // TC-03-3（M-18）
  it('別のブラウザで先に書いていたら M-18 を出し、編集の状態で開き直す', async () => {
    mockFetch({
      'GET /api/books/56': [() => json(200, book()), () => json(200, book({ myReviewId: 1234 }))],
      'POST /api/books/56/reviews': () => json(409, { code: 'REVIEW_EXISTS', message: M[18], field: null }),
      'GET /api/reviews/1234': () => json(200, review({ body: '先に書いた感想' })),
    })
    renderForm()
    await userEvent.click(await screen.findByRole('radio', { name: '星 3' }))
    await userEvent.type(field('感想（必須）'), '二つ目')
    await userEvent.click(screen.getByRole('button', { name: '投稿する' }))

    expect(await screen.findByText(M[18])).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'レビューを編集' })).toBeInTheDocument()
    expect(field('感想（必須）')).toHaveValue('先に書いた感想')
  })

  it('「キャンセル」で入力を捨てて G-5 に戻る', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    await userEvent.click(await screen.findByRole('button', { name: 'キャンセル' }))
    expect(location()).toHaveTextContent(/^\/books\/56$/)
  })

  it('ない本は M-15', async () => {
    mockFetch({ 'GET /api/books/56': () => json(404, { code: 'BOOK_NOT_FOUND', message: M[15], field: null }) })
    renderForm()
    expect(await screen.findByText(M[15])).toBeInTheDocument()
  })
})

// 自分のレビューがある本（G-6 を編集の状態で開く）
const editRoutes = (overrides: Partial<ReviewView> = {}) => ({
  'GET /api/books/56': () => json(200, book({ myReviewId: 1234 })),
  'GET /api/reviews/1234': () => json(200, review({ rating: 3, body: '今の感想', spoiler: true, ...overrides })),
})

describe('レビューの編集（G-6）', () => {

  // TC-03-5（RV-10）
  it('今の星・感想・ネタバレありを入れておき、何も変えていなければ「保存する」を押せない', async () => {
    mockFetch(editRoutes())
    renderForm()
    expect(await screen.findByRole('heading', { name: 'レビューを編集' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '星 3' })).toBeChecked()
    expect(field('感想（必須）')).toHaveValue('今の感想')
    expect(screen.getByRole('checkbox', { name: 'ネタバレあり' })).toBeChecked()
    expect(screen.getByRole('button', { name: '保存する' })).toBeDisabled()

    // 前後の空白を足しただけでは、変えたことにならない
    await userEvent.type(field('感想（必須）'), ' ')
    expect(screen.getByRole('button', { name: '保存する' })).toBeDisabled()
  })

  // TC-03-9
  it('ネタバレありを外して保存すると PATCH で送り、G-5 に戻る', async () => {
    const fetch = mockFetch({ ...editRoutes(), 'PATCH /api/reviews/1234': () => json(200, review()) })
    renderForm()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'ネタバレあり' }))
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))

    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/books\/56$/)
    expect(Object.fromEntries(sentForm(fetch, 2))).toEqual({ rating: '3', body: '今の感想', spoiler: 'false' })
  })
})

// 写真の選択（FN-04 PH-1〜PH-5）
const photo = (name: string, type = 'image/jpeg', size = 10) => new File([new Uint8Array(size)], name, { type })
const fileInput = () => screen.getByLabelText<HTMLInputElement>('添える写真のファイル')
const previews = () => within(screen.getByRole('list', { name: '添える写真' })).getAllByRole('img')
  .map((img) => img.getAttribute('src'))

describe('写真の選択（G-6）', () => {
  // jsdom には URL.createObjectURL がないので、ファイル名を返す偽物にする
  beforeEach(() => {
    URL.createObjectURL = (file: Blob) => `blob:${(file as File).name}`
    URL.revokeObjectURL = () => {}
  })

  // TC-04-1（PH-2・PH-4）
  it('選んだ写真を並べ、一度に 6 枚選ぶと先の 4 枚だけを残して M-22。4 枚あれば「写真を選ぶ」を押せない', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    await screen.findByRole('button', { name: '写真を選ぶ' })
    await userEvent.upload(fileInput(), [1, 2, 3, 4, 5, 6].map((i) => photo(`${i}.jpg`)))

    expect(await screen.findByText(M[22])).toBeInTheDocument()
    expect(previews()).toEqual(['blob:1.jpg', 'blob:2.jpg', 'blob:3.jpg', 'blob:4.jpg'])
    expect(screen.getByRole('button', { name: '写真を選ぶ' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: '写真 2 を外す' }))
    expect(previews()).toEqual(['blob:1.jpg', 'blob:3.jpg', 'blob:4.jpg'])
    expect(screen.getByRole('button', { name: '写真を選ぶ' })).toBeEnabled()
  })

  // TC-04-2・TC-04-3（PH-3）
  it('5MB を超える写真・GIF は添えず M-23', async () => {
    mockFetch({ 'GET /api/books/56': () => json(200, book()) })
    renderForm()
    await screen.findByRole('button', { name: '写真を選ぶ' })
    // accept を外して、選べない形式も渡す
    await userEvent.upload(fileInput(), [photo('a.jpg', 'image/jpeg', 5 * 1024 * 1024 + 1), photo('b.gif', 'image/gif'),
      photo('c.png', 'image/png')], { applyAccept: false })

    expect(await screen.findByText(M[23])).toBeInTheDocument()
    expect(previews()).toEqual(['blob:c.png'])
  })

  // PH-5
  it('投稿すると写真を選んだ順に送る。API が形式の誤りを返したら M-23 を出し、写真を残す', async () => {
    const fetch = mockFetch({
      'GET /api/books/56': () => json(200, book()),
      'POST /api/books/56/reviews': () => json(400, { code: 'PHOTO_TYPE_INVALID', message: M[23], field: 'photos' }),
    })
    renderForm()
    await userEvent.click(await screen.findByRole('radio', { name: '星 4' }))
    await userEvent.type(field('感想（必須）'), '写真つき')
    await userEvent.upload(fileInput(), [photo('a.jpg'), photo('b.png', 'image/png')])
    await userEvent.click(screen.getByRole('button', { name: '投稿する' }))

    expect(await screen.findByText(M[23])).toBeInTheDocument()
    expect(sentForm(fetch, 1).getAll('photos').map((f) => (f as File).name)).toEqual(['a.jpg', 'b.png'])
    expect(previews()).toEqual(['blob:a.jpg', 'blob:b.png'])
  })

  // TC-04-1・TC-04-5（PH-4、FN-04 5 章の 5）
  it('編集では今の写真も並べ、残す写真を keepPhotoIds、足す写真を photos で送る。合わせて 4 枚まで', async () => {
    const photos = [77, 78, 79].map((id) => ({ id, url: `https://s3/${id}.jpg`, expiresAt: '2999-01-01T00:00:00Z' }))
    const fetch = mockFetch({ ...editRoutes({ photos }), 'PATCH /api/reviews/1234': () => json(200, review()) })
    renderForm()
    await screen.findByRole('heading', { name: 'レビューを編集' })
    expect(previews()).toEqual(['https://s3/77.jpg', 'https://s3/78.jpg', 'https://s3/79.jpg'])

    // 残す写真が 3 枚なので、足せるのは 1 枚まで
    await userEvent.upload(fileInput(), [photo('a.jpg'), photo('b.jpg')])
    expect(await screen.findByText(M[22])).toBeInTheDocument()
    expect(previews()).toEqual(['https://s3/77.jpg', 'https://s3/78.jpg', 'https://s3/79.jpg', 'blob:a.jpg'])

    await userEvent.click(screen.getByRole('button', { name: '写真 2 を外す' }))
    await userEvent.click(screen.getByRole('button', { name: '保存する' }))
    await vi.waitFor(() => expect(fetch.keys()).toContain('PATCH /api/reviews/1234'))
    const sent = sentForm(fetch, 2)
    expect(sent.getAll('keepPhotoIds')).toEqual(['77', '79'])
    expect(sent.getAll('photos').map((f) => (f as File).name)).toEqual(['a.jpg'])
  })

  it('編集で写真を外しただけでも「保存する」を押せる', async () => {
    const photos = [{ id: 77, url: 'https://s3/77.jpg', expiresAt: '2999-01-01T00:00:00Z' }]
    mockFetch(editRoutes({ photos }))
    renderForm()
    expect(await screen.findByRole('button', { name: '保存する' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: '写真 1 を外す' }))
    expect(screen.getByRole('button', { name: '保存する' })).toBeEnabled()
  })
})

function renderDetail(path = '/reviews/1234') {
  return renderComponent(<ReviewPage />, { path, route: '/reviews/:id' })
}

describe('レビューの詳細（G-7）', () => {
  it('カードを出し、感想は全部出す（RV-6）', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(200, review({ body: '一行目\n二行目' })) })
    renderDetail()
    expect(await screen.findByRole('heading', { name: 'レビュー' })).toBeInTheDocument()
    const card = await screen.findByRole('article')
    expect(within(card).getByText(/一行目/).textContent).toBe('一行目\n二行目')
  })

  // TC-03-12（RV-13）
  it('削除されたレビューは M-20', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(404, { code: 'REVIEW_NOT_FOUND', message: M[20], field: null }) })
    renderDetail()
    expect(await screen.findByText(M[20])).toBeInTheDocument()
  })

  it('削除したらその本の G-5 に移る', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(200, review()), 'DELETE /api/reviews/1234': () => json(204) })
    renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: 'メニュー' }))
    await userEvent.click(screen.getByRole('menuitem', { name: '削除' }))
    await userEvent.click(screen.getByRole('button', { name: '削除' }))
    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/books\/56$/)
  })

  it('直接開いたときの「←」は、その本の G-5 に移る', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(200, review()) })
    renderDetail()
    await screen.findByRole('article')
    await userEvent.click(screen.getByRole('button', { name: '戻る' }))
    expect(location()).toHaveTextContent(/^\/books\/56$/)
  })

  // TC-03-8：画面を開き直すと伏せた状態に戻る
  it('ネタバレありは伏せて出し、開き直すと伏せた状態に戻る', async () => {
    mockFetch({ 'GET /api/reviews/1234': () => json(200, review({ spoiler: true, body: '犯人は執事' })) })
    const { unmount } = renderDetail()
    await userEvent.click(await screen.findByRole('button', { name: 'ネタバレを表示' }))
    expect(screen.getByText('犯人は執事')).toBeInTheDocument()
    unmount()

    renderDetail()
    expect(await screen.findByRole('button', { name: 'ネタバレを表示' })).toBeInTheDocument()
    expect(screen.queryByText('犯人は執事')).not.toBeInTheDocument()
  })
})
