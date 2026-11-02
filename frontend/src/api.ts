import type { components } from './api-schema'
import { M } from './messages'

// API が返す形・受け取る形は、API の仕様書（docs/api/openapi.yaml）から作った api-schema.d.ts の型を使う。
// API を変えたら、backend の mvnw verify で仕様書を書き直し、npm run gen:api で型を作り直す（README）
type Schemas = components['schemas']

// ログインしている自分（GET /api/auth/me などが返す）
export type Me = Schemas['Me']

// API がエラーを返したとき（機能定義書 3.3）。message は画面にそのまま出せる文、
// field があればその入力欄の下に出す
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly field: string | null

  constructor(status: number, code: string, message: string, field: string | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.field = field
  }
}

// 200 番台以外なら ApiError にする。本文が API のエラーの形でなければ（API サーバーが止まっているなど）M-30 を出す
export async function ensureOk(res: Response): Promise<Response> {
  if (res.ok) return res
  let body: { code?: unknown, message?: unknown, field?: unknown } = {}
  try {
    body = await res.json()
  } catch {
    // 本文が JSON でない
  }
  const code = typeof body.code === 'string' ? body.code : 'UNKNOWN'
  const message = typeof body.message === 'string' && body.message ? body.message : M[30]
  throw new ApiError(res.status, code, message, typeof body.field === 'string' ? body.field : null)
}

// ---------------------------------------------------------------- JWT の取り直し（機能定義書 FN-01 3.3）

let onSessionExpired: () => void = () => {}

// 取り直しに失敗したとき（14 日たった・ログアウトした）に呼ぶ処理を決める。画面は M-8 を出して G-1 に移す
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

let refreshing: Promise<boolean> | null = null

// 取り直しに成功した回数。API を送ったときの値と比べて、送った後に取り直しが済んだかを知る
let refreshed = 0

// 同時に複数の API が 401 になっても、取り直しは 1 回だけにする。
// 2 回送ると、2 回目は無効にしたリフレッシュトークンを使ったことになり、全部のログインが切れるため（FN-01 5 章の 5）
function refreshOnce(): Promise<boolean> {
  refreshing ??= fetch('/api/auth/refresh', { method: 'POST', headers: csrfHeader() })
    .then((res) => res.ok, () => false)
    .then((ok) => {
      if (ok) refreshed++
      return ok
    })
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

// 取り直しを試さない API。ログイン・登録の 401 はパスワードの間違いなどで、取り直しでは直らない
const NO_REFRESH = ['/api/auth/login', '/api/auth/signup', '/api/auth/refresh']

// ---------------------------------------------------------------- 呼び出し

// Cookie の XSRF-TOKEN を X-XSRF-TOKEN ヘッダーで送り返す（CSRF 対策。機能定義書 3.2）
function csrfHeader(): Record<string, string> {
  const token = document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length)
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {}
}

// body が FormData なら multipart/form-data（写真と一緒に送るレビュー）、それ以外は JSON で送る
async function call(method: string, url: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = method === 'GET' ? {} : csrfHeader()
  const isForm = body instanceof FormData
  // multipart の境界の文字列はブラウザが付けるので、FormData のときは Content-Type を決めない
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'
  try {
    return await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    })
  } catch {
    // つながらない
    throw new ApiError(0, 'NETWORK_ERROR', M[30])
  }
}

// API を呼ぶ。ログインの 401 のときは JWT を取り直して 1 回だけやり直す。
// access_token の Cookie は JWT と同じ 15 分で消えるので、期限切れでも TOKEN_EXPIRED ではなく
// UNAUTHENTICATED（Cookie がない）が返ることがある。そのため 401 ならどちらでも取り直しを試す（FN-01 3.3）。
// notifyExpired が false なら、取り直しに失敗しても M-8 を出さない
async function request(method: string, url: string, body?: unknown, notifyExpired = true): Promise<Response> {
  const sentAt = refreshed
  const res = await call(method, url, body)
  if (res.status !== 401 || NO_REFRESH.includes(url)) return ensureOk(res)
  // 送った後に、ほかの API の 401 で取り直しが済んでいたら、取り直さずにやり直す
  if (refreshed === sentAt && !(await refreshOnce())) {
    if (notifyExpired) onSessionExpired()
    return ensureOk(res)
  }
  return ensureOk(await call(method, url, body))
}

async function requestJson<T>(method: string, url: string, body?: unknown, notifyExpired = true): Promise<T> {
  const res = await request(method, url, body, notifyExpired)
  return res.json() as Promise<T>
}

// ---------------------------------------------------------------- FN-01 ユーザー登録・ログイン

// ログインしている自分。ログインしていなければ（取り直しにも失敗したら）null
// 画面を開いたときに呼ぶ。もともとログインしていない人もいるので M-8 は出さない（FN-01 6 章）
export async function fetchMe(): Promise<Me | null> {
  try {
    return await requestJson<Me>('GET', '/api/auth/me', undefined, false)
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null
    throw e
  }
}

// ユーザー ID が使えるか（入力欄から離れたとき）
export async function isUsernameAvailable(username: string): Promise<boolean> {
  const res = await requestJson<Schemas['UsernameAvailability']>(
    'GET', `/api/auth/username-availability?username=${encodeURIComponent(username)}`)
  return res.available
}

export function signup(username: string, displayName: string, password: string): Promise<Me> {
  return requestJson<Me>('POST', '/api/auth/signup', { username, displayName, password } satisfies Schemas['SignupRequest'])
}

export function login(username: string, password: string): Promise<Me> {
  return requestJson<Me>('POST', '/api/auth/login', { username, password } satisfies Schemas['LoginRequest'])
}

// ログアウトする。ログインが切れていても G-1 に戻るだけなので M-8 は出さない
export async function logout(): Promise<void> {
  await request('POST', '/api/auth/logout', undefined, false)
}

// ---------------------------------------------------------------- FN-02 書籍検索・登録

// 検索の結果の 1 件（FN-02 6 章）。まだ取り込んでいない本もあるので、アプリの本の ID は持たない
export type BookSearchItem = Schemas['BookSearchItem']

// 20 件ずつ。hasNext が false なら続きはない（最大 5 ページ・100 件。B-3）
export type BookSearchPage = Schemas['BookSearchPage']

export function searchBooks(q: string, page = 1): Promise<BookSearchPage> {
  const query = new URLSearchParams({ q })
  if (page > 1) query.set('page', String(page))
  return requestJson<BookSearchPage>('GET', `/api/books/search?${query}`)
}

// 選んだ本を取り込み、本の ID を返す。もう取り込んであれば同じ本の ID が返る（B-5・B-6）
export async function importBook(googleBooksId: string): Promise<number> {
  const res = await requestJson<Schemas['ImportedBook']>('POST', '/api/books',
    { googleBooksId } satisfies Schemas['ImportBookRequest'])
  return res.id
}

// ---------------------------------------------------------------- FN-10 本のページ

// 本の詳細（FN-10 6 章）。平均評価・レビュー（1-4）、自分の読書ステータス（1-7）は、その機能で足す
export type BookDetail = Schemas['BookDetail']

export function fetchBook(id: number): Promise<BookDetail> {
  return requestJson<BookDetail>('GET', `/api/books/${id}`)
}

// ---------------------------------------------------------------- 画面の URL（画面一覧 1 章）

// G-4 書籍検索の結果画面。検索した文字を ?q= に入れる（開き直しても同じ結果を出す。B-10）
export const bookSearchUrl = (q: string) => `/books/search?${new URLSearchParams({ q })}`

// G-5 本のページ
export const bookUrl = (id: number) => `/books/${id}`

// G-8 プロフィール画面
export const profileUrl = (username: string) => `/users/${encodeURIComponent(username)}`
