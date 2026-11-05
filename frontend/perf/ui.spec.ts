import { expect, test } from '@playwright/test'
import type { Page, Response } from '@playwright/test'
import { bookId, fillLogin, newContext, RUNS, record, throttle, UNDER_LOAD, username, xsrfHeader } from './measure.ts'
import { noisePng } from './png.ts'

// 画面の性能テスト（開発計画書 2-2、基本設計書 2.4）。要件定義書 11.1 の「画面に出るまで」を、ブラウザで押してから
// 表示されるまでの時間で測り、RUNS 回（20 回）の P95 で判定する。データは perf/seed.sql で入れておく。
// 動かし方は frontend/perf/cli.ts（npm run perf -- ui / mixed）

// 時間を測る。押してから表示されるまでを、Node 側の時計で測る
const now = () => performance.now()

// 画面に count 件以上のレビューのカードが出るまで待つ
async function waitForCards(page: Page, count = 20): Promise<void> {
  await page.waitForFunction((n) => document.querySelectorAll('article').length >= n, count, { polling: 'raf', timeout: 30_000 })
}

// 画面に見えているレビューの写真が、すべて読み込み終わるまで待つ
async function waitForVisibleImages(page: Page): Promise<void> {
  await page.waitForFunction(() => [...document.querySelectorAll('article img')]
    .filter((img) => img.getBoundingClientRect().top < window.innerHeight)
    .every((img) => (img as HTMLImageElement).complete && (img as HTMLImageElement).naturalWidth > 0),
  null, { polling: 'raf', timeout: 30_000 })
}

// 感想 text のカードに写真が 4 枚あるか。loaded なら、4 枚とも読み込み終わっているか（ブラウザで動く）
function hasFourImages({ text, loaded }: { text: string, loaded: boolean }): boolean {
  const card = [...document.querySelectorAll('article')].find((a) => a.textContent?.includes(text))
  const imgs = card ? [...card.querySelectorAll('img')] : []
  return imgs.length === 4 && (!loaded || imgs.every((img) => img.complete && img.naturalWidth > 0))
}

// LCP（いちばん大きな内容が出るまで）と CLS（表示のずれの合計）。ページを開いてからの値
async function webVitals(page: Page): Promise<{ lcp: number, cls: number }> {
  return page.evaluate(() => new Promise<{ lcp: number, cls: number }>((resolve) => {
    let lcp = 0
    let cls = 0
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) lcp = entry.startTime
    }).observe({ type: 'largest-contentful-paint', buffered: true })
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries() as (PerformanceEntry & { value: number, hadRecentInput: boolean })[]) {
        if (!entry.hadRecentInput) cls += entry.value
      }
    }).observe({ type: 'layout-shift', buffered: true })
    setTimeout(() => resolve({ lcp, cls }), 100)
  }))
}

// 最初に開いたときに読んだ JS・CSS の大きさ（KB。圧縮されて届いたときは圧縮後）
async function assetKilobytes(page: Page): Promise<number> {
  return page.evaluate(() => (performance.getEntriesByType('resource') as PerformanceResourceTiming[])
    .filter((entry) => /\.(js|css)(\?|$)/.test(entry.name))
    .reduce((sum, entry) => sum + entry.encodedBodySize, 0) / 1024)
}

async function login(page: Page, n: number): Promise<void> {
  await fillLogin(page, n)
  await page.getByRole('button', { name: 'ログイン' }).click()
  await page.getByRole('link', { name: '自分のプロフィール' }).waitFor()
}

test('N-1 ログインしてからタイムラインの最初の 20 件が出るまで', async ({ browser }, info) => {
  const shown: number[] = []
  const images: number[] = []
  const lcp: number[] = []
  const cls: number[] = []
  for (let i = 0; i < RUNS; i++) {
    // 毎回、Cookie もキャッシュもないブラウザで、ログイン画面から始める
    const context = await newContext(browser, info)
    const page = await context.newPage()
    await throttle(page, info)
    await fillLogin(page, 41)
    const start = now()
    await page.getByRole('button', { name: 'ログイン' }).click()
    await waitForCards(page)
    shown.push(now() - start)
    // 写真は S3 から遅れて届くので、合否には入れず参考として記録する
    await waitForVisibleImages(page)
    images.push(now() - start)

    if (!UNDER_LOAD) {
      if (i === 0) {
        record(info, 'assets', '参考', '最初に読む JS・CSS の大きさ', [await assetKilobytes(page)], { unit: 'KB' })
      }
      // タイムラインを開き直したときの LCP・CLS
      await page.reload()
      await waitForCards(page)
      const vitals = await webVitals(page)
      lcp.push(vitals.lcp)
      cls.push(vitals.cls)
    }
    await context.close()
  }
  const result = record(info, 'n1', 'N-1', 'ログイン → タイムラインの 20 件', shown, { target: 3000 })
  record(info, 'n1-images', '参考', 'ログイン → 見えている写真まで', images)
  if (!UNDER_LOAD) {
    record(info, 'lcp', '参考', 'タイムラインを開き直したときの LCP', lcp)
    record(info, 'cls', '参考', 'タイムラインを開き直したときの CLS', cls, { unit: '' })
  }
  expect.soft(result.p95, `N-1 の P95（${result.samples.join(', ')}）`).toBeLessThanOrEqual(3000)
})

// タブ（tabpanel）の中に、カードが count 件以上出るまで待つ
async function waitForTabCards(page: Page, label: string, count: number): Promise<void> {
  await page.waitForFunction((arg) =>
    document.querySelectorAll(`[role="tabpanel"][aria-label="${arg.label}"] article`).length >= arg.count,
  { label, count }, { polling: 'raf', timeout: 30_000 })
}

test('N-2 タブを切り替えてから 20 件が出るまで、一番下まで見てから続きの 20 件が出るまで', async ({ browser }, info) => {
  const context = await newContext(browser, info)
  const page = await context.newPage()
  await throttle(page, info)
  await login(page, 44)
  // 最後に開いたタブは覚えられるので、「フォロー中」から始める
  await page.goto('/?tab=following')
  await waitForTabCards(page, 'フォロー中', 20)

  // 「すべて」⇔「フォロー中」を交互に押す。切り替えるとすぐタブの名前は変わるが、一覧は API の応答で入れ替わるので、
  // 応答が届いた後に 20 件そろうまでを測る（応答を待つ前の古い 20 件で止めない）
  const tabs = { すべて: [] as number[], フォロー中: [] as number[] }
  for (let i = 0; i < RUNS * 2; i++) {
    const label = i % 2 === 0 ? 'すべて' : 'フォロー中'
    const responded = page.waitForResponse((r) => r.url().includes('/api/timeline?') && !r.url().includes('cursor='))
    const start = now()
    await page.getByRole('tab', { name: label }).click()
    expect((await responded).ok()).toBe(true)
    await waitForTabCards(page, label, 20)
    tabs[label].push(now() - start)
  }

  // 続きの読み込み（T-2）。毎回読み直して最初の 20 件にしてから（測らない）、一番下まで動かす
  const more: number[] = []
  for (let i = 0; i < RUNS; i++) {
    await page.goto('/?tab=following')
    await waitForTabCards(page, 'フォロー中', 20)
    const start = now()
    // 表示の直後は「続きを読む」が付いてカードの高さが変わるので、40 件になるまで一番下に動かし続ける
    await page.waitForFunction(() => {
      window.scrollTo(0, document.documentElement.scrollHeight)
      return document.querySelectorAll('[role="tabpanel"][aria-label="フォロー中"] article').length >= 40
    }, null, { polling: 'raf', timeout: 30_000 })
    more.push(now() - start)
  }
  await context.close()

  const results = [
    record(info, 'n2-tab-all', 'N-2', 'タブ：「すべて」を押す → 20 件', tabs.すべて, { target: 1000 }),
    record(info, 'n2-tab-following', 'N-2', 'タブ：「フォロー中」を押す → 20 件', tabs.フォロー中, { target: 1000 }),
    record(info, 'n2-more', 'N-2', '一番下まで見る → 続きの 20 件', more, { target: 1000 }),
  ]
  for (const r of results) {
    expect.soft(r.p95, `${r.label} の P95（${r.samples.join(', ')}）`).toBeLessThanOrEqual(1000)
  }
})

test('N-3 本のページ（本の情報・平均評価・最初の 20 件のレビュー）が出るまで', async ({ browser }, info) => {
  const context = await newContext(browser, info)
  const page = await context.newPage()
  await throttle(page, info)
  await login(page, 44)

  const shown: number[] = []
  for (let i = 0; i < RUNS; i++) {
    // 毎回違う本（1〜20 番には 25 件のレビューがある）。URL を開いてから測る
    const id = bookId(1 + (i % 20))
    const start = now()
    await page.goto(`/books/${id}`)
    await page.getByRole('heading', { name: /性能テストの本/ }).waitFor()
    await waitForCards(page)
    shown.push(now() - start)
    await expect(page.getByText('レビュー 25 件')).toBeVisible()
  }
  await context.close()

  const result = record(info, 'n3', 'N-3', '本のページ → 情報と 20 件', shown, { target: 2000 })
  expect.soft(result.p95, `N-3 の P95（${result.samples.join(', ')}）`).toBeLessThanOrEqual(2000)
})

const helpfulUrl = (method: string) => (r: Response) => r.url().endsWith('/helpful') && r.request().method() === method
const statusUrl = (method: string) => (r: Response) => r.url().endsWith('/status') && r.request().method() === method

test('N-4 参考になった・フォロー・読書ステータスを押してから画面に出るまで', async ({ browser }, info) => {
  const context = await newContext(browser, info)
  const page = await context.newPage()
  await throttle(page, info)
  await login(page, 42)

  // 画面は押した直後に表示を変え（先に画面を変える）、応答が届いて確定する。両方を測り、両方で判定する。
  // button を押して after の状態になるまでと、matches の応答が届くまでの時間を返す
  async function measure(button: ReturnType<Page['getByRole']>, after: string, matches: (r: Response) => boolean) {
    let confirmedAt = 0
    const confirmed = page.waitForResponse((r) => {
      if (!matches(r)) return false
      confirmedAt = now()
      return true
    })
    const start = now()
    await button.click()
    await expect(button).toHaveAttribute('aria-pressed', after)
    const shown = now() - start
    expect((await confirmed).ok()).toBe(true)
    return { shown, confirmed: confirmedAt - start }
  }

  // 応答を待ちながら押す（測らない）
  async function pressAndWait(button: ReturnType<Page['getByRole']>, matches: (r: Response) => boolean) {
    const done = page.waitForResponse(matches)
    await button.click()
    await done
  }

  const helpfulShown: number[] = []
  const helpfulConfirmed: number[] = []
  await page.goto(`/books/${bookId(1)}`)
  await waitForCards(page)
  for (let i = 0; i < RUNS; i++) {
    const button = page.getByRole('article').nth(i % 10).getByRole('button', { name: /参考になった/ })
    // もともと付けているレビューは、先に取り消しておく（測らない）
    if (await button.getAttribute('aria-pressed') === 'true') await pressAndWait(button, helpfulUrl('DELETE'))
    const result = await measure(button, 'true', helpfulUrl('PUT'))
    helpfulShown.push(result.shown)
    helpfulConfirmed.push(result.confirmed)
    await pressAndWait(button, helpfulUrl('DELETE'))
  }

  // 測るユーザーは全員をフォローしているので、外してから（測らない）フォローし直す
  const target = username(800)
  const followUrl = `/api/users/${target}/follow`
  const follow = (method: string) => (r: Response) => r.url().endsWith(followUrl) && r.request().method() === method
  await page.goto(`/users/${target}`)
  const followButton = page.getByRole('region', { name: 'プロフィール' }).getByRole('button')
  await expect(followButton).toHaveAttribute('aria-pressed', 'true')
  const followShown: number[] = []
  const followConfirmed: number[] = []
  for (let i = 0; i < RUNS; i++) {
    await pressAndWait(followButton, follow('DELETE'))
    await expect(followButton).toHaveAttribute('aria-pressed', 'false')
    const result = await measure(followButton, 'true', follow('PUT'))
    followShown.push(result.shown)
    followConfirmed.push(result.confirmed)
  }

  // 21 番の本（読書ステータスがない）で、付けて外す
  await page.goto(`/books/${bookId(21)}`)
  const status = page.getByRole('group', { name: '読書ステータス' }).getByRole('button', { name: '読書中' })
  const statusShown: number[] = []
  const statusConfirmed: number[] = []
  for (let i = 0; i < RUNS; i++) {
    const result = await measure(status, 'true', statusUrl('PUT'))
    statusShown.push(result.shown)
    statusConfirmed.push(result.confirmed)
    await pressAndWait(status, statusUrl('DELETE'))
  }
  await context.close()

  const results = [
    record(info, 'n4-helpful-shown', 'N-4', '参考になった：押す → 画面が変わる', helpfulShown, { target: 500 }),
    record(info, 'n4-helpful-confirmed', 'N-4', '参考になった：押す → 応答で確定', helpfulConfirmed, { target: 500 }),
    record(info, 'n4-follow-shown', 'N-4', 'フォロー：押す → 画面が変わる', followShown, { target: 500 }),
    record(info, 'n4-follow-confirmed', 'N-4', 'フォロー：押す → 応答で確定', followConfirmed, { target: 500 }),
    record(info, 'n4-status-shown', 'N-4', '読書ステータス：押す → 画面が変わる', statusShown, { target: 500 }),
    record(info, 'n4-status-confirmed', 'N-4', '読書ステータス：押す → 応答で確定', statusConfirmed, { target: 500 }),
  ]
  for (const r of results) {
    expect.soft(r.p95, `${r.label} の P95（${r.samples.join(', ')}）`).toBeLessThanOrEqual(500)
  }
})

test('N-5 写真 4 枚（合計 20MB）のレビューを投稿してから本のページに出るまで', async ({ browser }, info) => {
  // 手元では API から S3 への送信が家の回線を通り、公開先（Fargate → S3 は AWS の中）と条件が違うので、
  // PERF_UPLOAD=1 のとき（GitHub Actions のランナーの中の MinIO・公開先で測るとき）だけ測る。
  // スマートフォンでは測らない（20MB の送信を 20 回ずつ 2 通り行うと時間がかかるため。回線の条件は同じ）
  test.skip(process.env.PERF_UPLOAD !== '1', '写真の送信は PERF_UPLOAD=1 のときだけ測る')
  test.skip(info.project.name === 'mobile' || UNDER_LOAD, 'パソコンの幅で、負荷をかけないときだけ測る')
  const context = await newContext(browser, info)
  const page = await context.newPage()
  await throttle(page, info)
  await login(page, 43)

  // 1 枚 約 5.07MB（上限 5MB = 5,242,880 バイトの少し下）を 4 枚。合計 約 20.3MB
  const files = [1, 2, 3, 4].map((k) => ({ name: `perf-${k}.png`, mimeType: 'image/png', buffer: noisePng(1300, 1300) }))
  const book = bookId(22)
  const shown: number[] = []
  const images: number[] = []
  for (let i = 0; i < RUNS; i++) {
    const body = `N-5 の測定 ${Date.now()}`
    await page.goto(`/books/${book}/review`)
    await page.getByRole('radio', { name: '星 4' }).check({ force: true })
    await page.getByLabel('感想（必須）').fill(body)
    await page.getByLabel('添える写真のファイル').setInputFiles(files)
    const start = now()
    await page.getByRole('button', { name: '投稿する' }).click()
    // 写真 4 枚の枠が付いたカードが出るまで（N-1 と同じく、写真の読み込みは合否に入れない）
    await page.waitForFunction(hasFourImages, { text: body, loaded: false }, { polling: 'raf', timeout: 180_000 })
    shown.push(now() - start)
    // 4 枚の写真を読み込み終わるまで（参考。20MB を下り 10Mbps で読むと、それだけで約 16 秒かかる）
    await page.waitForFunction(hasFourImages, { text: body, loaded: true }, { polling: 'raf', timeout: 180_000 })
    images.push(now() - start)

    // レビューを消して、S3 の写真も消す（測らない）。ID はカードのリンクから取る
    const id = (await page.getByRole('link', { name: body }).getAttribute('href'))?.split('/').pop()
    const deleted = await page.request.delete(`/api/reviews/${id}`, { headers: await xsrfHeader(context) })
    expect(deleted.ok(), `レビュー ${id} を消せません（${deleted.status()}）`).toBe(true)
  }
  await context.close()

  const result = record(info, 'n5', 'N-5', '写真 4 枚（20MB）の投稿 → 本のページにカード', shown, { target: 10_000 })
  record(info, 'n5-images', '参考', '写真 4 枚（20MB）の投稿 → 写真まで', images)
  expect.soft(result.p95, `N-5 の P95（${result.samples.join(', ')}）`).toBeLessThanOrEqual(10_000)
})

// 本物の Google Books API を呼ぶ言葉。前の結果の使い回し（24 時間）に当たらないよう、2 つを組み合わせて毎回変える
const SEARCH_WORDS = ['夏目漱石', '宮沢賢治', '太宰治', '芥川龍之介', '川端康成', '三島由紀夫', '森鴎外', '樋口一葉', '吉川英治', '司馬遼太郎',
  '小説', '随筆', '歴史', '数学', '料理', '旅行', '経済', '宇宙', '音楽', '美術']

test('N-6 書籍検索で Enter を押してから結果が出るまで（本物の Google Books API）', async ({ browser }, info) => {
  // 1 日の上限を使い切らないよう、PERF_BOOK_SEARCH=1 のときに、パソコンの幅で RUNS 回（20 回）だけ呼ぶ（基本設計書 2.4）。
  // API サーバーに Google Books API の API キーを渡しておく
  test.skip(process.env.PERF_BOOK_SEARCH !== '1', '本物の Google Books API を呼ぶので、PERF_BOOK_SEARCH=1 のときだけ測る')
  test.skip(info.project.name === 'mobile' || UNDER_LOAD, 'パソコンの幅で、負荷をかけないときだけ測る')
  const context = await newContext(browser, info)
  const page = await context.newPage()
  await throttle(page, info)
  await login(page, 44)

  const shown: number[] = []
  const seed = Date.now()
  for (let i = 0; i < RUNS; i++) {
    const q = `${SEARCH_WORDS[(seed + i) % 10]} ${SEARCH_WORDS[10 + ((seed + i * 3) % 10)]}`
    const search = page.getByRole('searchbox', { name: '本を探す文字' })
    await search.fill(q)
    const responded = page.waitForResponse((r) => r.url().includes('/api/books/search?'), { timeout: 30_000 })
    const start = now()
    await search.press('Enter')
    const response = await responded
    expect(response.ok(), `検索が ${response.status()}。API キーを確かめてください`).toBe(true)
    await page.getByRole('heading', { name: `「${q}」の検索結果` }).waitFor()
    await page.getByRole('list', { name: '検索結果' }).getByRole('listitem').first().waitFor()
    shown.push(now() - start)
  }
  await context.close()

  const result = record(info, 'n6', 'N-6', '書籍検索：Enter → 結果', shown, { target: 3000 })
  expect.soft(result.p95, `N-6 の P95（${result.samples.join(', ')}）`).toBeLessThanOrEqual(3000)
})
