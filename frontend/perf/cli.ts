import { execFileSync, spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { accountId, remotePsql } from './aws.ts'
import type { Result } from './measure.ts'
import { noisePng } from './png.ts'

// 性能テスト（開発計画書 2-2、基本設計書 2.4）をまとめて動かす。frontend で npm run perf -- <コマンド>。
// 先に DB（docker compose up -d）と、jar を perf プロファイルで起動しておく（README「性能テスト」）
const USAGE = `使い方：npm run perf -- <コマンド>
  seed   DB booklog_perf にデータ（要件定義書 11.1 の量）を入れ、タイムラインの写真を S3 に置く
  clean  データと S3 の写真を消す
  api    API を 1 人で測る（k6。N-2・N-3・N-4）
  load   API を 30 人の同時アクセスで測る（k6。約 6 分半）
  ui     画面で測る（Playwright。N-1〜N-6、LCP・CLS、JS・CSS の大きさ）
  mixed  k6 で 30 人の負荷をかけながら、画面で N-1〜N-4 を測る
  report perf/results/ の結果を 1 つの表（summary.md）にまとめる
  all    api → load → ui → mixed → report の順にすべて動かす

公開先（AWS）で測るとき：PERF_DB=aws を付けて seed・clean すると、公開先の DB（RDS）と写真のバケットに入れる・消す`

const ROOT = fileURLToPath(new URL('../../', import.meta.url))
const FRONTEND = fileURLToPath(new URL('../', import.meta.url))
// Docker の -v に渡すので、末尾の区切り文字を外す
const PERF_DIR = fileURLToPath(new URL('../../perf', import.meta.url))
const RESULTS = new URL('../../perf/results/', import.meta.url)
const PLAYWRIGHT = fileURLToPath(new URL('../node_modules/@playwright/test/cli.js', import.meta.url))

// 測る先。公開先で測るときは CloudFront の URL を入れる
const BASE_URL = process.env.PERF_BASE_URL ?? 'http://localhost:8080'
const K6_IMAGE = 'grafana/k6:2.2.0'
// 公開先（AWS）の DB と写真のバケットに入れる・消すか（PERF_DB=aws）。公開先では、API が使う DB にそのまま入れる
const ON_AWS = process.env.PERF_DB === 'aws'
const DB = ON_AWS ? 'booklog' : 'booklog_perf'
// タイムラインの写真を置くバケット（手元は application.properties と同じ既定値）。S3_ENDPOINT があれば S3 以外（MinIO）に置く
const BUCKET = process.env.S3_BUCKET ?? (ON_AWS ? `bl-photos-${accountId()}` : 'bl-photos-dev-467112307516')
// タイムラインに付ける写真（perf/seed.sql）の枚数と大きさ
const SAMPLE_IMAGES = 100
const sampleImage = () => noisePng(320, 240)

const START_API = `jar を perf プロファイルで起動してから、もう一度実行してください（README「性能テスト」）：
  java -Xmx768m -jar backend/target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=perf`

function psql(database: string, args: string[], input?: string): string {
  if (ON_AWS) return remotePsql(database, args, input)
  return execFileSync('docker', ['compose', 'exec', '-T', 'db', 'psql', '-U', 'booklog', '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-q', ...args], { cwd: ROOT, input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'] })
}

function s3(args: string[]): void {
  const endpoint = process.env.S3_ENDPOINT ? ['--endpoint-url', process.env.S3_ENDPOINT] : []
  execFileSync('aws', ['s3', ...args, ...endpoint, '--region', process.env.AWS_REGION ?? 'ap-northeast-1',
    '--only-show-errors'], { stdio: 'inherit' })
}

// 公開先の DB は API が使っているものなので、あって表もある
const dbExists = () => ON_AWS || psql('booklog', ['-tAc', `SELECT 1 FROM pg_database WHERE datname = '${DB}'`]).trim() === '1'

function seed(): void {
  if (!ON_AWS && !dbExists()) {
    psql('booklog', ['-c', `CREATE DATABASE ${DB}`])
    console.log(`DB ${DB} を作りました。表は API が起動したときに Flyway が作ります。\n${START_API}`)
    process.exit(1)
  }
  if (!ON_AWS && psql(DB, ['-tAc', "SELECT to_regclass('public.users') IS NOT NULL"]).trim() !== 't') {
    console.log(`DB ${DB} に表がありません。\n${START_API}`)
    process.exit(1)
  }
  // タイムラインの写真（reviews/perf-001.png〜）を S3 に置く
  const dir = new URL('images/', RESULTS)
  rmSync(dir, { recursive: true, force: true })
  mkdirSync(dir, { recursive: true })
  let bytes = 0
  for (let i = 1; i <= SAMPLE_IMAGES; i++) {
    const png = sampleImage()
    bytes = png.length
    writeFileSync(new URL(`perf-${String(i).padStart(3, '0')}.png`, dir), png)
  }
  console.log(`写真 ${SAMPLE_IMAGES} 枚を s3://${BUCKET}/reviews/ に置きます`)
  s3(['cp', fileURLToPath(dir), `s3://${BUCKET}/reviews/`, '--recursive', '--content-type', 'image/png'])
  rmSync(dir, { recursive: true, force: true })

  console.log('データを入れます（1 分ほどかかります）')
  const sql = readFileSync(new URL('seed.sql', new URL('../../perf/', import.meta.url)), 'utf8')
  process.stdout.write(psql(DB, ['-v', `image_bytes=${bytes}`, '-f', '-'], sql))
}

function clean(): void {
  if (dbExists()) {
    // N-5 の測定が途中で止まると、投稿した写真が S3 に残る。行を消す前に、そのファイルを消す
    const keys = psql(DB, ['-tAc', `SELECT p.storage_key FROM review_photos p JOIN reviews r ON r.id = p.review_id
      JOIN users u ON u.id = r.user_id WHERE left(u.username, 5) = 'perf_' AND left(p.storage_key, 13) <> 'reviews/perf-'`])
      .split(/\r?\n/).filter(Boolean)
    for (const key of keys) {
      s3(['rm', `s3://${BUCKET}/${key}`])
    }
    psql(DB, ['-c', "DELETE FROM users WHERE left(username, 5) = 'perf_'", '-c', "DELETE FROM books WHERE left(google_books_id, 5) = 'perf-'"])
  }
  s3(['rm', `s3://${BUCKET}/reviews/`, '--recursive', '--exclude', '*', '--include', 'perf-*'])
  console.log('性能テストのデータと写真を消しました')
}

// k6 を Docker で動かす。手元の API（localhost）には、コンテナの中から host.docker.internal でつなぐ
function k6Args(scenario: string, resultName: string, containerName: string, env: Record<string, string> = {}): string[] {
  const baseUrl = BASE_URL.replace(/\/\/(localhost|127\.0\.0\.1)(?=[:/]|$)/, '//host.docker.internal')
  // Linux では結果のファイルを書けるよう、自分のユーザーで動かす（Windows・Mac の Docker Desktop では要らない）
  const user = process.getuid && process.getgid ? ['--user', `${process.getuid()}:${process.getgid()}`] : []
  const vars = { BASE_URL: baseUrl, SCENARIO: scenario, RESULT_NAME: resultName, ...env }
  return ['run', '--rm', '--name', containerName, '--add-host=host.docker.internal:host-gateway', ...user,
    '-v', `${PERF_DIR}:/perf`, ...Object.entries(vars).flatMap(([k, v]) => ['-e', `${k}=${v}`]),
    K6_IMAGE, 'run', '--quiet', '/perf/k6/api.js']
}

// 終了コード 0 なら合格。k6 は目標を外すと 99、Playwright は 1 を返す
function k6(scenario: string): boolean {
  mkdirSync(RESULTS, { recursive: true })
  return spawnSync('docker', k6Args(scenario, scenario, `perf-k6-${scenario}`), { stdio: 'inherit' }).status === 0
}

function playwright(env: Record<string, string> = {}, args: string[] = []): boolean {
  return spawnSync(process.execPath, [PLAYWRIGHT, 'test', '--config', 'playwright.perf.config.ts', ...args],
    { cwd: FRONTEND, stdio: 'inherit', env: { ...process.env, PERF_BASE_URL: BASE_URL, ...env } }).status === 0
}

async function mixed(): Promise<boolean> {
  mkdirSync(RESULTS, { recursive: true })
  const name = 'perf-k6-mixed'
  // 画面の測定が終わるまで負荷をかけ続け、終わったら止める（止めても k6 は結果を書き出す）
  const load = spawn('docker', k6Args('load', 'load-mixed', name, { LOAD_DURATION: '60m' }), { stdio: 'inherit' })
  const loadEnded = new Promise((resolve) => load.on('exit', resolve))
  console.log('30 人まで増えるのを待ちます（70 秒）')
  await sleep(70_000)
  const ok = playwright({ PERF_UNDER_LOAD: '1' }, ['--grep', 'N-1|N-2|N-3|N-4'])
  spawnSync('docker', ['kill', '--signal=SIGINT', name], { stdio: 'ignore' })
  await loadEnded
  return ok
}

// 表に書く値。時間はミリ秒、大きさは KB、CLS は単位なし
const value = (r: Result, v: number) => r.unit === 'ms' ? `${Math.round(v)}ms`
  : r.unit === 'KB' ? `${Math.round(v)}KB` : v.toFixed(3)

// 結果を 1 つの Markdown の表にまとめる
function report(): void {
  const parts = ['# 性能テストの結果', '', `- 日時：${new Date().toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo' })}`,
    `- 測った先：${BASE_URL}`, '- 判定：P95（小さい順に並べて 95% の位置の値）が目標以内なら合格（基本設計書 2.4）']
  for (const name of ['baseline', 'load', 'load-mixed']) {
    const file = new URL(`api-${name}.md`, RESULTS)
    if (existsSync(file)) parts.push(readFileSync(file, 'utf8'))
  }
  const uiDir = new URL('ui/', RESULTS)
  if (existsSync(uiDir)) {
    const results = readdirSync(uiDir).filter((f) => f.endsWith('.json'))
      .map((f) => JSON.parse(readFileSync(new URL(f, uiDir), 'utf8')) as Result)
      .toSorted((a, b) => Number(a.underLoad) - Number(b.underLoad) || a.project.localeCompare(b.project)
        || a.id.localeCompare(b.id))
    parts.push('', '■ 画面の性能（Edge。下り 10Mbps・上り 30Mbps。スマートフォンは 375px・CPU 4 倍遅く）', '',
      '| 要件 | 項目 | 画面 | 負荷 | 回数 | P50 | P95 | 最大 | 目標（P95） | 判定 |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |',
      ...results.map((r) => `| ${r.id} | ${r.label} | ${r.project === 'pc' ? 'パソコン' : 'スマートフォン'} | `
        + `${r.underLoad ? '30 人' : 'なし'} | ${r.samples.length} | ${value(r, r.p50)} | ${value(r, r.p95)} | `
        + `${value(r, r.max)} | ${r.target === undefined ? '-' : `${value(r, r.target)} 以内`} | `
        + `${r.ok === undefined ? '参考' : r.ok ? '合格' : '不合格'} |`))
  }
  const text = parts.join('\n') + '\n'
  mkdirSync(RESULTS, { recursive: true })
  writeFileSync(new URL('summary.md', RESULTS), text)
  console.log(`\n${text}\n結果：${fileURLToPath(new URL('summary.md', RESULTS))}`)
}

const command = process.argv[2]
let ok = true
switch (command) {
  case 'seed': seed(); break
  case 'clean': clean(); break
  case 'api': ok = k6('baseline'); break
  case 'load': ok = k6('load'); break
  case 'ui': ok = playwright(); break
  case 'mixed': ok = await mixed(); break
  case 'report': report(); break
  case 'all': {
    rmSync(RESULTS, { recursive: true, force: true })
    // どれかが目標を外しても、残りも測ってから結果をまとめる
    const results = [k6('baseline'), k6('load'), playwright(), await mixed()]
    report()
    ok = results.every(Boolean)
    break
  }
  default:
    console.log(USAGE)
    ok = command === undefined
}
process.exit(ok ? 0 : 1)
