import { execFileSync } from 'node:child_process'

// テストで作ったものを開発用 DB から消す。ユーザー（ID が「e2e_」で始まる）を消すと、レビュー・コメント・フォローなどは
// ON DELETE CASCADE で一緒に消える。そのあとで、Google Books API の偽物から取り込んだ本（ID が「e2e-」で始まる）と、
// 偽物の検索の結果の使い回しを消す。S3 の写真は消えないので、写真を使うテストは、テストの中で画面からレビューを消す。
// 前の実行が途中で止まって残ったものも消せるよう、始める前（globalSetup）と終わった後（globalTeardown）に呼ぶ
export default function cleanup(): void {
  execFileSync('docker', [
    'compose', 'exec', '-T', 'db',
    'psql', '-U', 'booklog', '-d', 'booklog', '-v', 'ON_ERROR_STOP=1', '-q',
    // LIKE だと「_」が 1 文字の記号になるので、先頭の 4 文字で比べる
    '-c', "DELETE FROM users WHERE left(username, 4) = 'e2e_'",
    '-c', "DELETE FROM books WHERE left(google_books_id, 4) = 'e2e-'",
    '-c', "DELETE FROM book_search_results WHERE result::text LIKE '%e2e-%'",
  ], { cwd: new URL('../..', import.meta.url), stdio: 'inherit' })
}
