import { vi } from 'vitest'

export type Handler = (init: RequestInit | undefined) => Response | Promise<Response>

export const json = (status: number, body?: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

// 「メソッド URL」ごとに返すものを決めて fetch を差し替える。呼ばれた順に calls に残る
export function mockFetch(routes: Record<string, Handler | Handler[]>) {
  const calls: { key: string, init: RequestInit | undefined }[] = []
  const queues = new Map(Object.entries(routes).map(([k, v]) => [k, Array.isArray(v) ? [...v] : v]))
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${url}`
    calls.push({ key, init })
    const route = queues.get(key)
    if (!route) throw new Error(`想定していない呼び出し：${key}`)
    // 配列なら 1 回ごとに次のものを返す（最後のものは何度でも返す）
    const handler = Array.isArray(route) ? (route.length > 1 ? route.shift()! : route[0]) : route
    return handler(init)
  })
  vi.stubGlobal('fetch', fetch)
  return { calls, keys: () => calls.map((c) => c.key) }
}

// index 番目の呼び出しで送った JSON
export function sentJson(fetch: ReturnType<typeof mockFetch>, index = 0): unknown {
  return JSON.parse(String(fetch.calls[index]?.init?.body))
}

// index 番目の呼び出しで送った multipart の中身
export function sentForm(fetch: ReturnType<typeof mockFetch>, index = 0): FormData {
  const body = fetch.calls[index]?.init?.body
  if (!(body instanceof FormData)) throw new Error(`${index} 番目の呼び出しは multipart で送っていない`)
  return body
}
