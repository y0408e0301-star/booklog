// k6 のシナリオ（api.js）で使う共通の部品。ログイン・CSRF のトークン・JWT の取り直し・結果の書き出し
import http from 'k6/http'
import { fail } from 'k6'

// 測る先。手元は Docker の中から 8080 の API（jar）につなぐ。公開先は CloudFront の URL を渡す
export const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:8080').replace(/\/$/, '')

// perf/seed.sql で入れたユーザーと本
export const PASSWORD = 'perf-pass1'
export const username = (n) => `perf_${String(n).padStart(4, '0')}`
export const bookId = (n) => 900000000 + n

// GET 以外の API は、Cookie の XSRF-TOKEN を X-XSRF-TOKEN ヘッダーで送り返す（機能定義書 3.2）
function xsrfHeader() {
  const token = http.cookieJar().cookiesForURL(`${BASE_URL}/api/`)['XSRF-TOKEN']
  return token ? { 'X-XSRF-TOKEN': token[0] } : {}
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

// ログインする。BCrypt は重いので、測る API には含めない（タグ name=login で分ける）。
// 最初の GET で XSRF-TOKEN の Cookie を受け取ってから送る
export function login(n) {
  http.get(`${BASE_URL}/api/auth/me`, { tags: { name: 'setup' }, responseCallback: http.expectedStatuses(200, 401) })
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username: username(n), password: PASSWORD }),
    { headers: { ...JSON_HEADERS, ...xsrfHeader() }, tags: { name: 'login' } })
  if (res.status !== 200) {
    fail(`${username(n)} でログインできません（${res.status}）。先に性能テストのデータを入れてください：npm run perf -- seed`)
  }
}

// JWT（15 分で切れる）が切れていたら取り直して、もう一度送る（画面と同じ。FN-01 3.3）
function send(method, path, name, body) {
  // 測る API には api=yes を付け、失敗の割合を数える（ログイン・取り直しは数えない）
  const params = () => ({
    headers: { ...(method === 'GET' ? {} : xsrfHeader()), ...(body ? JSON_HEADERS : {}) },
    tags: { name, api: 'yes' },
  })
  const payload = body ? JSON.stringify(body) : null
  let res = http.request(method, `${BASE_URL}${path}`, payload, params())
  if (res.status === 401) {
    http.post(`${BASE_URL}/api/auth/refresh`, null, { headers: xsrfHeader(), tags: { name: 'setup' } })
    res = http.request(method, `${BASE_URL}${path}`, payload, params())
  }
  if (res.status !== 200) {
    console.error(`${method} ${path} → ${res.status} ${res.body}`)
  }
  return res
}

export const get = (path, name) => send('GET', path, name)
export const put = (path, name, body) => send('PUT', path, name, body)
export const del = (path, name) => send('DELETE', path, name)

// 測る API と、その目標（ミリ秒。要件定義書 11.1）。P95 で判定する（基本設計書 2.4）。
// 本のページは 1 回の表示で 2 つの API を呼ぶ（N-3 の 2 秒は画面で測る）ので、それぞれ 1 秒を目安にする
export const TARGETS = {
  timeline_following: { id: 'N-2', label: 'タイムライン（フォロー中）', ms: 1000 },
  timeline_all: { id: 'N-2', label: 'タイムライン（すべて）', ms: 1000 },
  timeline_next: { id: 'N-2', label: 'タイムラインの続き（2 ページ目）', ms: 1000 },
  book: { id: 'N-3', label: '本の情報', ms: 1000 },
  book_reviews: { id: 'N-3', label: '本のレビューの一覧', ms: 1000 },
  helpful: { id: 'N-4', label: '参考になったを付ける', ms: 500 },
  unhelpful: { id: 'N-4', label: '参考になったを取り消す', ms: 500 },
  follow: { id: 'N-4', label: 'フォローする', ms: 500 },
  unfollow: { id: 'N-4', label: 'フォローを解除する', ms: 500 },
  status: { id: 'N-4', label: '読書ステータスを付ける', ms: 500 },
  unstatus: { id: 'N-4', label: '読書ステータスを外す', ms: 500 },
}

export function thresholds() {
  const result = {
    // 失敗した応答（4xx・5xx）は 1% 未満
    'http_req_failed{api:yes}': ['rate<0.01'],
  }
  for (const [name, target] of Object.entries(TARGETS)) {
    result[`http_req_duration{name:${name}}`] = [`p(95)<${target.ms}`]
  }
  return result
}

// 結果を、表（画面に出す）と JSON（perf/results/ に残す）にする
export function summary(data, name, title) {
  const rows = Object.entries(TARGETS).map(([name, target]) => {
    const metric = data.metrics[`http_req_duration{name:${name}}`]
    if (!metric || metric.values.count === undefined) {
      return { name, ...target, count: 0 }
    }
    const v = metric.values
    return {
      name, ...target, count: v.count ?? 0,
      p50: v['p(50)'], p95: v['p(95)'], p99: v['p(99)'], max: v.max,
      ok: v['p(95)'] < target.ms,
    }
  }).filter((row) => row.count > 0)
  const failed = data.metrics['http_req_failed{api:yes}']?.values.rate ?? 0
  const ms = (value) => value === undefined ? '-' : `${Math.round(value)}ms`
  const lines = [
    '',
    `■ API の性能：${title}`,
    '',
    '| 要件 | API | 回数 | P50 | P95 | P99 | 最大 | 目標（P95） | 判定 |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
    ...rows.map((r) => `| ${r.id} | ${r.label} | ${r.count} | ${ms(r.p50)} | ${ms(r.p95)} | ${ms(r.p99)} | ${ms(r.max)} | ${r.ms}ms 以内 | ${r.ok ? '合格' : '不合格'} |`),
    '',
    `失敗した応答の割合：${(failed * 100).toFixed(2)}%（1% 未満で合格）`,
    '',
  ]
  return {
    stdout: lines.join('\n'),
    [`/perf/results/api-${name}.json`]: JSON.stringify({ name, baseUrl: BASE_URL, rows, failedRate: failed }, null, 2),
    [`/perf/results/api-${name}.md`]: lines.join('\n'),
  }
}
