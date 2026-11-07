// 要件 ID とテストの対応表を作る（テスト仕様書。要件定義書 13.1）。
// docs/ の表で定めた要件 ID を集め、テストのファイルからテストごとに書かれた要件 ID を読み、
// docs/test/requirement-coverage.md に書き出す。Node だけで動く（依存なし）。
//
//   node scripts/requirement-coverage.mjs           一覧を書き出す
//   node scripts/requirement-coverage.mjs --check   テストのない ID があるか、一覧が古ければ失敗する（CI）
//
// テストに要件 ID を書く場所（テスト仕様書 2 章）：
//   - テストの名前（it・test の文字列、JUnit のメソッド名）
//   - テストの直前のコメント、テストの中
//   - 区切りのコメント「// ---- 登録（A-1〜A-4）----」、describe・@Nested のクラス名。次の区切りまでのテストすべてに付く
//   「A-1〜A-4」のような範囲も書ける
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..')
const output = join(root, 'docs', 'test', 'requirement-coverage.md')

// テストで確かめる種類の要件 ID（docs/README.md の ID の一覧）
const TESTED = ['A', 'B', 'RV', 'PH', 'H', 'C', 'F', 'ST', 'T', 'BK', 'PF', 'G', 'M', 'N', 'BR']
const ID = new RegExp(`\\b(${TESTED.join('|')})-(\\d+)(?:〜(?:\\1-)?(\\d+))?\\b`, 'g')

// テストではなく、ほかの方法で確かめる要件 ID。確かめ方を一覧に書く
const OTHER = {
  'N-7': '公開先（AWS）の稼働に従う。確かめるものはない',
  'N-9': '公開先で、RDS の自動バックアップの設定を見る（Terraform の rds.tf、無料プランの間は 1 日。開発計画書 2-8）',
  'N-11': '公開先で、http で開くと https に移ることと、画面・API が https で動くことを見る（開発計画書 2-8）',
  'N-20': '画面の文言は機能定義書・画面設計書のとおり日本語で書いている。全画面を目で見て確かめる（テスト仕様書の手動の項目）',
  'N-25': 'GitHub のリポジトリで管理している',
  'N-26': 'README の「起動方法」「公開環境」を見る',
  'N-27': 'application.properties だけで環境変数を読んでいることを見る',
  'N-28': '.github/workflows/ci.yml で、Pull Request ごとに書式・lint・テスト・E2E を動かしている。E2E は Google Books API の偽物につなぐ',
}

// 機能定義書 10 章のテストの観点の ID（TC-03-1 など）。「TC-03-1〜3」のように範囲でも書ける
const TC = /\bTC-(\d\d)-(\d+)(?:〜(?:TC-\1-)?(\d+))?\b/g

// テストではなく、目で見て確かめる観点（テスト仕様書の手動の項目）
const OTHER_TC = {
}

function walk(dir, match, out = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === 'results') continue
    const path = join(dir, entry.name)
    if (entry.isDirectory()) walk(path, match, out)
    else if (match(path)) out.push(path)
  }
  return out
}

const posix = (path) => relative(root, path).split(sep).join('/')

function ids(text) {
  const found = new Set()
  for (const m of text.matchAll(ID)) {
    const [, prefix, from, to] = m
    const last = to ? Number(to) : Number(from)
    for (let n = Number(from); n <= last; n++) found.add(`${prefix}-${n}`)
  }
  for (const m of text.matchAll(TC)) {
    const [, fn, from, to] = m
    const last = to ? Number(to) : Number(from)
    for (let n = Number(from); n <= last; n++) found.add(`TC-${fn}-${n}`)
  }
  return found
}

// ---- 要件 ID の定義：docs/ の表の 1 列目が ID の行 ----

const defined = new Map()
for (const file of walk(join(root, 'docs'), (p) => p.endsWith('.md'))) {
  if (file === output) continue
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\| ([A-Z]+-\d+) \| ([^|]*)\|/)
    if (!m || !TESTED.includes(m[1].split('-')[0]) || defined.has(m[1])) continue
    defined.set(m[1], { doc: posix(file).replace(/^docs\//, ''), text: m[2].trim() })
  }
}

// ---- テストの観点：機能定義書 FN-01〜FN-11 の 10 章の「- TC-03-1：…」の行 ----

const viewpoints = new Map()
for (const file of walk(join(root, 'docs', 'functions'), (p) => /fn\d\d-.*\.md$/.test(p))) {
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^- (TC-\d\d-\d+)：(.*)/)
    if (m) viewpoints.set(m[1], { doc: posix(file).replace(/^docs\//, ''), text: m[2].trim() })
  }
}

// ---- テスト：ファイルを行ごとに読み、テストの始まりで区切る ----

const KINDS = [
  { kind: 'API', dir: 'backend/src/test/java', match: (p) => p.endsWith('Tests.java') },
  { kind: '画面', dir: 'frontend/src', match: (p) => /\.test\.tsx?$/.test(p) },
  { kind: 'E2E', dir: 'frontend/e2e', match: (p) => p.endsWith('.spec.ts') },
  { kind: '性能', dir: 'frontend/perf', match: (p) => p.endsWith('.spec.ts') },
]

const JAVA_TEST = /^\s*void (\S+)\(/
const TS_TEST = /^\s*(?:it|test)(?:\.\w+)?\(\s*(['"`])(.+?)\1/
const JAVA_SECTION = /^\s*(?:\/\/ ----(.*)----|class (\S+) \{)/
const TS_SECTION = /^\s*(?:\/\/ ----(.*)----|(?:describe|test\.describe)\(\s*['"`](.+?)['"`])/

const tests = []
for (const { kind, dir, match } of KINDS) {
  for (const file of walk(join(root, dir), match)) {
    const java = file.endsWith('.java')
    const lines = readFileSync(file, 'utf8').split('\n')
    let section = ''
    let current = null
    let comment = []
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      const sectionMatch = line.match(java ? JAVA_SECTION : TS_SECTION)
      // JUnit のテストは @Test・@ParameterizedTest の次の行（ほかの注釈を挟むこともある）のメソッド
      const isAnnotated = java && lines.slice(Math.max(0, i - 4), i).some((l) => /@(Test|ParameterizedTest)\b/.test(l))
      const testMatch = java ? (isAnnotated ? line.match(JAVA_TEST) : null) : line.match(TS_TEST)
      if (sectionMatch && !testMatch && (!java || !/^\s*class \S+ \{/.test(line) || /@Nested/.test(lines[i - 1] ?? ''))) {
        current = null
        section = sectionMatch[1] ?? sectionMatch[2]
      } else if (testMatch) {
        current = { kind, file: posix(file), name: java ? testMatch[1] : testMatch[2], text: [section, ...comment, line] }
        tests.push(current)
      } else if (current) {
        current.text.push(line)
      }
      // テストの直前のコメント。JUnit の注釈（@Test など）を挟んでもよい
      if (/^\s*(\/\/|\*|\/\*)/.test(line)) comment = [...comment, line]
      else if (!(java && /^\s*@/.test(line))) comment = []
    }
  }
}

// k6 の API の性能テストはファイルで 1 つ（シナリオの中で N-2・N-3・N-5 をまとめて測る）
for (const file of walk(join(root, 'perf', 'k6'), (p) => p.endsWith('.js'))) {
  const text = readFileSync(file, 'utf8')
  const header = text.split('\n').filter((l) => l.startsWith('//')).join('\n')
  if (ids(header).size) tests.push({ kind: '性能', file: posix(file), name: 'k6 のシナリオ', text: [header] })
}

const coverage = new Map([...defined.keys(), ...viewpoints.keys()].map((id) => [id, []]))
for (const test of tests) {
  for (const id of ids(test.text.join('\n'))) coverage.get(id)?.push(test)
}

// ---- 書き出す ----

const order = (a, b) => {
  const [pa, na] = a.split('-')
  const [pb, nb] = b.split('-')
  return TESTED.indexOf(pa) - TESTED.indexOf(pb) || Number(na) - Number(nb)
}
const tcOrder = (a, b) => a.localeCompare(b, 'en', { numeric: true })
const cell = (s) => s.replaceAll('|', '／').replace(/\s+/g, ' ')
const short = (s) => (s.length > 60 ? `${s.slice(0, 60)}…` : s)

// ID ごとに、確かめるテスト（なければ「テストなし」）の行を作る。テストのない ID は missing に入れる
function table(definitions, other, sort, missing) {
  const rows = []
  for (const id of [...definitions.keys()].sort(sort)) {
    const { doc, text } = definitions.get(id)
    const found = coverage.get(id)
    let how
    if (other[id]) {
      how = `（テスト以外）${other[id]}`
    } else if (found.length === 0) {
      missing.push(id)
      how = '**テストなし**'
    } else {
      const byKind = Map.groupBy(found, (t) => t.kind)
      how = [...byKind].map(([kind, list]) => `${kind} ${list.length}：${list.map((t) => `${t.file.split('/').pop()} › ${t.name}`).join('、')}`).join('<br>')
    }
    rows.push(`| ${id} | ${cell(short(text))} | ${doc} | ${cell(how)} |`)
  }
  return rows.join('\n')
}

const missing = []
const missingTc = []
const idRows = table(defined, OTHER, order, missing)
const tcRows = table(viewpoints, OTHER_TC, tcOrder, missingTc)

const counted = [...defined.keys()].filter((id) => !OTHER[id])
const countedTc = [...viewpoints.keys()].filter((id) => !OTHER_TC[id])
const markdown = `# 要件 ID とテストの対応表

このファイルは \`node scripts/requirement-coverage.mjs\` で作る。手で直さない。テストに要件 ID・観点の ID を書く決まりは [テスト仕様書](test-specification.md) 2 章。

- テストで確かめる要件 ID：${counted.length} 個（うちテストのないもの ${missing.length} 個）。テスト以外で確かめる要件 ID：${Object.keys(OTHER).length} 個
- テストで確かめる観点（機能定義書 10 章）：${countedTc.length} 件（うちテストのないもの ${missingTc.length} 件）。目で見て確かめる観点：${Object.keys(OTHER_TC).length} 件
- 読んだテスト：${tests.length} 件（API ${tests.filter((t) => t.kind === 'API').length}、画面 ${tests.filter((t) => t.kind === '画面').length}、E2E ${tests.filter((t) => t.kind === 'E2E').length}、性能 ${tests.filter((t) => t.kind === '性能').length}）

## 1. 要件 ID

| ID | 要件 | 定めている文書 | 確かめるテスト（種類 件数：ファイル › テスト名） |
| --- | --- | --- | --- |
${idRows}

## 2. テストの観点（機能定義書 10 章）

| ID | 観点 | 定めている文書 | 確かめるテスト（種類 件数：ファイル › テスト名） |
| --- | --- | --- | --- |
${tcRows}
`

if (process.argv.includes('--check')) {
  let failed = false
  if (missing.length) {
    console.error(`テストのない要件 ID があります：${missing.join('、')}`)
    failed = true
  }
  if (missingTc.length) {
    console.error(`テストのない観点があります：${missingTc.join('、')}`)
    failed = true
  }
  let current = ''
  try { current = readFileSync(output, 'utf8') } catch { /* まだない */ }
  if (current !== markdown) {
    console.error('docs/test/requirement-coverage.md が古いです。node scripts/requirement-coverage.mjs で作り直してコミットしてください')
    failed = true
  }
  if (failed) process.exit(1)
  console.log(`OK：${counted.length} 個の要件 ID と ${countedTc.length} 件の観点のすべてにテストがあります`)
} else {
  writeFileSync(output, markdown)
  console.log(`${posix(output)} を書き出しました（テストのない ID：${[...missing, ...missingTc].join('、') || 'なし'}）`)
}
