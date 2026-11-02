import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError, bookSearchUrl, bookUrl, createReview, deleteReview, editReview, ensureOk, fetchBook, fetchBookReviews, fetchMe,
  fetchReview, importBook, isUsernameAvailable, login, logout, profileUrl, reviewFormUrl, reviewUrl, searchBooks,
  setSessionExpiredHandler, signup,
} from './api'
import { M } from './messages'
import { json, mockFetch, sentForm, sentJson } from './test/fetchMock'

const me = { username: 'taro_y', displayName: 'たろう' }

const expired = () => json(401, { code: 'TOKEN_EXPIRED', message: '…' })
const refreshFailed = () => json(401, { code: 'REFRESH_FAILED', message: '…' })

const AVAILABILITY = 'GET /api/auth/username-availability?username=taro_y'

afterEach(() => {
  setSessionExpiredHandler(() => {})
  document.cookie = 'XSRF-TOKEN=; max-age=0'
})

describe('ensureOk', () => {
  it('API のエラーの code・message・field を持つ', async () => {
    const error = await ensureOk(json(409, { code: 'USERNAME_TAKEN', message: M[1], field: 'username' }))
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'USERNAME_TAKEN', message: M[1], field: 'username' })
  })

  it('field がなければ null', async () => {
    await expect(ensureOk(json(401, { code: 'LOGIN_FAILED', message: 'ユーザー ID かパスワードが違います。' })))
      .rejects.toMatchObject({ code: 'LOGIN_FAILED', field: null })
  })

  it('message が空・code が文字列でないときは M-30 と UNKNOWN', async () => {
    await expect(ensureOk(json(500, { code: 1, message: '' })))
      .rejects.toMatchObject({ status: 500, code: 'UNKNOWN', message: M[30] })
  })

  it('200 番台ならそのまま返す', async () => {
    const res = json(204)
    await expect(ensureOk(res)).resolves.toBe(res)
  })

  it('本文が JSON でないときは M-30', async () => {
    await expect(ensureOk(new Response('Bad Gateway', { status: 502 }))).rejects.toThrow(M[30])
  })
})

describe('つながらないとき', () => {
  it('fetch が失敗したら NETWORK_ERROR の ApiError にして M-30 を出す', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))
    await expect(login('taro_y', 'password1')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR', message: M[30] })
  })
})

// TC-01-7 のうち画面の側。API が確かめることは backend のテストで確かめている
describe('CSRF', () => {
  it('GET 以外は Cookie の XSRF-TOKEN を X-XSRF-TOKEN ヘッダーで送り返す', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetch = mockFetch({ 'POST /api/auth/login': () => json(200, me) })
    await login('taro_y', 'password1')
    expect(fetch.calls[0].init?.headers).toMatchObject({ 'X-XSRF-TOKEN': 'abc-123', 'Content-Type': 'application/json' })
  })

  it('GET では送らない', async () => {
    document.cookie = 'XSRF-TOKEN=abc-123'
    const fetch = mockFetch({ 'GET /api/auth/me': () => json(200, me) })
    await fetchMe()
    expect(fetch.calls[0].init?.headers).toEqual({})
  })

  it('Cookie の値は URL のエンコードを戻して送る', async () => {
    document.cookie = 'XSRF-TOKEN=a%2Bb%3D'
    const fetch = mockFetch({ 'POST /api/auth/logout': () => json(204) })
    await logout()
    expect(fetch.calls[0].init?.headers).toEqual({ 'X-XSRF-TOKEN': 'a+b=' })
  })

  it('Cookie がなければ送らない', async () => {
    const fetch = mockFetch({ 'POST /api/auth/logout': () => json(204) })
    await logout()
    expect(fetch.calls[0].init?.headers).toEqual({})
  })
})

// それぞれの API を、機能定義書 FN-01 6 章の URL・メソッド・形で呼ぶか
describe('API の呼び出し方', () => {
  it('登録・ログインは JSON で送る', async () => {
    const fetch = mockFetch({ 'POST /api/auth/signup': () => json(201, me), 'POST /api/auth/login': () => json(200, me) })
    await expect(signup('taro_y', 'たろう', 'password1')).resolves.toEqual(me)
    await expect(login('taro_y', 'password1')).resolves.toEqual(me)
    expect(sentJson(fetch, 0)).toEqual({ username: 'taro_y', displayName: 'たろう', password: 'password1' })
    expect(sentJson(fetch, 1)).toEqual({ username: 'taro_y', password: 'password1' })
  })

  it('ユーザー ID が使えるかは、ユーザー ID を URL のエンコードをして聞く', async () => {
    mockFetch({ 'GET /api/auth/username-availability?username=a%26b%3Dc': () => json(200, { available: false }) })
    await expect(isUsernameAvailable('a&b=c')).resolves.toBe(false)
  })

  it('書籍検索は q と、2 ページ目からは page を送る', async () => {
    const fetch = mockFetch({
      'GET /api/books/search?q=%E5%A4%8F%E7%9B%AE+%E6%BC%B1%E7%9F%B3': () => json(200, { items: [], hasNext: false }),
      'GET /api/books/search?q=978-4&page=2': () => json(200, { items: [], hasNext: false }),
    })
    await searchBooks('夏目 漱石')
    await searchBooks('978-4', 2)
    expect(fetch.keys()).toHaveLength(2)
  })

  it('本の取り込みは Google Books の ID を JSON で送り、本の ID を返す', async () => {
    const fetch = mockFetch({ 'POST /api/books': () => json(201, { id: 56 }) })
    await expect(importBook('zyTCAlFPjgYC')).resolves.toBe(56)
    expect(sentJson(fetch)).toEqual({ googleBooksId: 'zyTCAlFPjgYC' })
  })

  it('本の情報', async () => {
    const fetch = mockFetch({ 'GET /api/books/56': () => json(200, {}) })
    await fetchBook(56)
    expect(fetch.keys()).toEqual(['GET /api/books/56'])
  })

  it('レビューの投稿・編集は multipart で送り、Content-Type はブラウザに任せる', async () => {
    const fetch = mockFetch({
      'POST /api/books/56/reviews': () => json(201, {}),
      'PATCH /api/reviews/7': () => json(200, {}),
    })
    await createReview(56, { rating: 4, body: '面白い', spoiler: false })
    await editReview(7, { rating: 2, body: '読み直した', spoiler: true })
    expect(Object.fromEntries(sentForm(fetch, 0))).toEqual({ rating: '4', body: '面白い', spoiler: 'false' })
    expect(Object.fromEntries(sentForm(fetch, 1))).toEqual({ rating: '2', body: '読み直した', spoiler: 'true' })
    expect(fetch.calls[0].init?.headers).not.toHaveProperty('Content-Type')
  })

  it('レビューの詳細・削除と、本のレビューの一覧（続きは cursor）', async () => {
    const fetch = mockFetch({
      'GET /api/reviews/7': () => json(200, {}),
      'DELETE /api/reviews/7': () => json(204),
      'GET /api/books/56/reviews': () => json(200, { items: [], nextCursor: null }),
      'GET /api/books/56/reviews?cursor=80': () => json(200, { items: [], nextCursor: null }),
    })
    await fetchReview(7)
    await deleteReview(7)
    await fetchBookReviews(56)
    await fetchBookReviews(56, '80')
    expect(fetch.keys()).toHaveLength(4)
  })

  it('画面の URL を作る', () => {
    expect(profileUrl('taro_y')).toBe('/users/taro_y')
    expect(profileUrl('a/b')).toBe('/users/a%2Fb')
    expect(bookSearchUrl('猫 & 犬')).toBe('/books/search?q=%E7%8C%AB+%26+%E7%8A%AC')
    expect(bookUrl(56)).toBe('/books/56')
    expect(reviewFormUrl(56)).toBe('/books/56/review')
    expect(reviewUrl(7)).toBe('/reviews/7')
  })
})

describe('JWT の取り直し（FN-01 3.3）', () => {
  // TC-01-10
  it('401 なら取り直して 1 回だけやり直す', async () => {
    const fetch = mockFetch({
      'GET /api/auth/me': [expired, () => json(200, me)],
      'POST /api/auth/refresh': () => json(200, me),
    })
    await expect(fetchMe()).resolves.toEqual(me)
    expect(fetch.keys()).toEqual(['GET /api/auth/me', 'POST /api/auth/refresh', 'GET /api/auth/me'])
  })

  it('access_token の Cookie が消えていて UNAUTHENTICATED のときも取り直す', async () => {
    const fetch = mockFetch({
      'GET /api/auth/me': [() => json(401, { code: 'UNAUTHENTICATED', message: '…' }), () => json(200, me)],
      'POST /api/auth/refresh': () => json(200, me),
    })
    await expect(fetchMe()).resolves.toEqual(me)
    expect(fetch.keys()).toContain('POST /api/auth/refresh')
  })

  // TC-01-10
  it('同時に 401 になっても、取り直しは 1 回だけ', async () => {
    const fetch = mockFetch({
      'GET /api/auth/me': [expired, expired, () => json(200, me)],
      'POST /api/auth/refresh': async () => {
        await new Promise((r) => setTimeout(r, 10))
        return json(200, me)
      },
    })
    await Promise.all([fetchMe(), fetchMe()])
    expect(fetch.keys().filter((k) => k === 'POST /api/auth/refresh')).toHaveLength(1)
  })

  it('取り直しが終わった後に 401 が返ってきた API も、取り直さずにやり直す', async () => {
    let releaseSlow: (() => void) | undefined
    const fetch = mockFetch({
      // 1 回目はすぐ 401、2 回目は取り直しが終わるまで 401 を返さない
      'GET /api/auth/me': [
        expired,
        () => new Promise<Response>((resolve) => {
          releaseSlow = () => resolve(expired())
        }),
        () => json(200, me),
      ],
      'POST /api/auth/refresh': () => json(200, me),
    })
    const first = fetchMe()
    const second = fetchMe()
    await expect(first).resolves.toEqual(me)
    releaseSlow?.()
    await expect(second).resolves.toEqual(me)
    expect(fetch.keys().filter((k) => k === 'POST /api/auth/refresh')).toHaveLength(1)
  })

  // TC-01-12
  it('取り直しに失敗したら、M-8 を出す処理を呼ぶ', async () => {
    const expiredHandler = vi.fn()
    setSessionExpiredHandler(expiredHandler)
    mockFetch({ [AVAILABILITY]: expired, 'POST /api/auth/refresh': refreshFailed })
    await expect(isUsernameAvailable('taro_y')).rejects.toMatchObject({ status: 401 })
    expect(expiredHandler).toHaveBeenCalledOnce()
  })

  it('取り直しの通信が失敗しても、取り直しに失敗したものとして扱う', async () => {
    const expiredHandler = vi.fn()
    setSessionExpiredHandler(expiredHandler)
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') throw new TypeError('Failed to fetch')
      return expired()
    }))
    await expect(isUsernameAvailable('taro_y')).rejects.toMatchObject({ status: 401 })
    expect(expiredHandler).toHaveBeenCalledOnce()
  })

  it('取り直しに成功してもやり直した API が失敗したら、そのエラーを返す', async () => {
    mockFetch({
      [AVAILABILITY]: [expired, () => json(400, { code: 'USERNAME_INVALID', message: M[2], field: 'username' })],
      'POST /api/auth/refresh': () => json(200, me),
    })
    await expect(isUsernameAvailable('taro_y')).rejects.toMatchObject({ status: 400, code: 'USERNAME_INVALID' })
  })

  it('画面を開いたとき・ログアウトのときは、取り直しに失敗しても M-8 を出さない', async () => {
    const expiredHandler = vi.fn()
    setSessionExpiredHandler(expiredHandler)
    mockFetch({
      'POST /api/auth/logout': expired,
      'POST /api/auth/refresh': refreshFailed,
      'GET /api/auth/me': expired,
    })
    await expect(fetchMe()).resolves.toBeNull()
    await expect(logout()).rejects.toBeInstanceOf(ApiError)
    expect(expiredHandler).not.toHaveBeenCalled()
  })

  it('画面を開いたときの 401 以外の失敗は、そのまま投げる', async () => {
    mockFetch({ 'GET /api/auth/me': () => json(500, { code: 'INTERNAL_ERROR', message: '…' }) })
    await expect(fetchMe()).rejects.toMatchObject({ status: 500 })
  })

  it('ログインの 401 では取り直さない', async () => {
    const fetch = mockFetch({
      'POST /api/auth/login': () => json(401, { code: 'LOGIN_FAILED', message: 'ユーザー ID かパスワードが違います。' }),
    })
    await expect(login('taro_y', 'wrong')).rejects.toThrow('ユーザー ID かパスワードが違います。')
    expect(fetch.keys()).toEqual(['POST /api/auth/login'])
  })

  it('登録の 401 でも取り直さない', async () => {
    const fetch = mockFetch({
      'POST /api/auth/signup': () => json(401, { code: 'UNAUTHENTICATED', message: '…' }),
    })
    await expect(signup('taro_y', 'たろう', 'password1')).rejects.toMatchObject({ status: 401 })
    expect(fetch.keys()).toEqual(['POST /api/auth/signup'])
  })
})
