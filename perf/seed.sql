-- 性能テスト（開発計画書 2-2、基本設計書 2.4）のデータ。要件定義書 11.1 の測定の条件の量を入れる：
-- ユーザー 1,000 人・本 5,000 冊・レビュー 1 万件・コメント 3 万件・参考になった 5 万件・読書ステータス 3 万件、
-- 測るユーザー（perf_0001〜perf_0050）は自分以外の 999 人をフォロー。
-- ユーザー ID はすべて「perf_」、本の Google Books の ID は「perf-」で始まり、パスワードは perf-pass1。
-- 前回のデータは消してから入れ直すので、何回流しても同じになる。
-- 本の ID は 900000001 から決めて入れる（測るときに本を指定するため。frontend/perf/measure.ts の bookId）。
--   1〜20 番：25 人（perf_0101〜perf_0125）のレビューがある本（N-3 の 20 件を出す）
--   21〜30 番：レビューがない本（N-5 で投稿する）
-- 表は API（Flyway）が作るので、先に API を起動しておく。psql の変数 image_bytes に、写真 1 枚の大きさ（バイト）を渡す
-- （写真のファイルは frontend/perf/cli.ts が作って S3 に置く）
BEGIN;

-- レビュー・写真の行・コメント・参考になった・フォロー・読書ステータスは ON DELETE CASCADE で一緒に消える。
-- LIKE だと「_」が 1 文字の記号になるので、先頭の 5 文字で比べる
DELETE FROM users WHERE left(username, 5) = 'perf_';
DELETE FROM books WHERE left(google_books_id, 5) = 'perf-';

-- 乱数の種を決め、毎回同じデータにする（結果の表は出さない）
SELECT setseed(0.42) \g /dev/null

-- ユーザー 1,000 人。パスワードの BCrypt のハッシュは 1 つを使い回す（1,000 回計算すると遅いため）
INSERT INTO users (username, display_name, password_hash, bio, created_at, updated_at)
SELECT format('perf_%s', lpad(n::text, 4, '0')),
       format('性能テスト %s', lpad(n::text, 4, '0')),
       '$2a$10$j6OIEMdIr/obCW25DF61ielNl3OX54GuohvdC5mKRKGqrUNe846fa',
       '性能テスト用のユーザーです。',
       now() - interval '120 days',
       now() - interval '120 days'
FROM generate_series(1, 1000) AS n;

CREATE TEMP TABLE perf_users ON COMMIT DROP AS
SELECT id, row_number() OVER (ORDER BY username)::int AS n
FROM users WHERE left(username, 5) = 'perf_';

-- 本 5,000 冊。Google Books API からは取らず、作った値を入れる（基本設計書 2.4）。表紙はなし（Google の画像の速さは測らない）
INSERT INTO books (id, google_books_id, isbn13, title, authors, publisher, published_date, description, created_at)
OVERRIDING SYSTEM VALUE
SELECT 900000000 + n,
       format('perf-%s', lpad(n::text, 5, '0')),
       format('978%s', lpad(n::text, 10, '0')),
       format('性能テストの本 %s', lpad(n::text, 5, '0')),
       ARRAY[format('著者 %s', n % 500)],
       '性能テスト出版',
       '2024-05-10',
       repeat('性能テストの本の紹介文です。', 20),
       now() - interval '120 days'
FROM generate_series(1, 5000) AS n;

-- 測るユーザー（1〜50 番）は自分以外の全員をフォロー（11.1 の「フォロー 1,000 人」。全員で 1,000 人なので 999 人）。
-- ほかの人は、ランダムに 20 人をフォロー
INSERT INTO follows (follower_id, followee_id)
SELECT a.id, b.id FROM perf_users a JOIN perf_users b ON a.id <> b.id WHERE a.n <= 50;

INSERT INTO follows (follower_id, followee_id)
SELECT a.id, b.id
FROM perf_users a
CROSS JOIN LATERAL (SELECT id FROM perf_users b WHERE b.id <> a.id ORDER BY random() LIMIT 20) b
WHERE a.n > 50;

-- レビュー 1 万件。1〜20 番の本に 25 件ずつ（500 件）、残りは 31 番より後の本にランダムに（1 人 1 冊に 1 つ）。
-- 日時は 13 分おき（約 90 日分）で、古い順に入れて ID の順と日時の順をそろえる。感想の長さは 20〜900 文字くらいにばらつかせる
CREATE TEMP TABLE perf_picks ON COMMIT DROP AS
SELECT un, bn, row_number() OVER (ORDER BY random())::int AS n
FROM (
  SELECT 100 + u AS un, b AS bn FROM generate_series(1, 25) AS u CROSS JOIN generate_series(1, 20) AS b
  UNION ALL
  (SELECT DISTINCT 1 + floor(random() * 1000)::int, 31 + floor(random() * 4970)::int
   FROM generate_series(1, 9700) LIMIT 9500)
) picks;

INSERT INTO reviews (user_id, book_id, rating, body, spoiler, created_at)
SELECT u.id,
       900000000 + p.bn,
       1 + floor(random() * 5)::int,
       left(format('性能テストのレビュー %s です。', p.n) || repeat('本のページとタイムラインの表示の速さを測るための文章。', floor(random() * 30)::int), 2000),
       random() < 0.05,
       now() - (10001 - p.n) * interval '13 minutes'
FROM perf_picks p
JOIN perf_users u ON u.n = p.un
ORDER BY p.n;

CREATE TEMP TABLE perf_reviews ON COMMIT DROP AS
SELECT r.id, r.user_id, r.book_id, r.created_at, row_number() OVER (ORDER BY r.id)::int AS n
FROM reviews r JOIN perf_users u ON u.id = r.user_id;

-- 新しい 1,000 件のうち 10 件に 1 件（100 件）に、写真を 1 枚ずつ付ける（S3 の reviews/perf-001.png〜perf-100.png）
INSERT INTO review_photos (review_id, storage_key, content_type, byte_size, sort_order)
SELECT r.id, format('reviews/perf-%s.png', lpad(((r.count_all - r.n) / 10 + 1)::text, 3, '0')), 'image/png', :image_bytes, 0
FROM (SELECT *, count(*) OVER ()::int AS count_all FROM perf_reviews) r
WHERE r.n > r.count_all - 1000 AND (r.count_all - r.n) % 10 = 0;

-- コメント 3 万件。レビューと書いた人はランダム、日時はレビューの 1 日以内（今より後にはしない）
INSERT INTO comments (review_id, user_id, body, created_at)
SELECT r.id, u.id, format('性能テストのコメント %s', c.g), least(now(), r.created_at + c.delay * interval '1 day')
FROM (SELECT g, 1 + floor(random() * 10000)::int AS rn, 1 + floor(random() * 1000)::int AS un, random() AS delay
      FROM generate_series(1, 31000) AS g) c
JOIN perf_reviews r ON r.n = c.rn
JOIN perf_users u ON u.n = c.un
LIMIT 30000;

-- 参考になった 5 万件。1 人 1 レビューに 1 つで、自分のレビューには付けない（H-4）ので、重なりを除いて 5 万件にする
INSERT INTO helpfuls (user_id, review_id, created_at)
SELECT u.id, r.id, least(now(), r.created_at + interval '1 hour')
FROM (SELECT DISTINCT 1 + floor(random() * 1000)::int AS un, 1 + floor(random() * 10000)::int AS rn
      FROM generate_series(1, 55000)) h
JOIN perf_users u ON u.n = h.un
JOIN perf_reviews r ON r.n = h.rn
WHERE r.user_id <> u.id
LIMIT 50000;

-- 読書ステータス 3 万件。レビューを書いた本は「読了」（RV-5）、残りは 31 番より後の本にランダムに。21〜30 番の本には入れない
INSERT INTO reading_statuses (user_id, book_id, status, updated_at)
SELECT user_id, book_id, 'READ', created_at FROM perf_reviews;

INSERT INTO reading_statuses (user_id, book_id, status, updated_at)
SELECT u.id, 900000000 + s.bn, (ARRAY['WANT', 'READING', 'READ'])[1 + floor(random() * 3)::int], now() - random() * interval '90 days'
FROM (SELECT DISTINCT 1 + floor(random() * 1000)::int AS un, 31 + floor(random() * 4970)::int AS bn
      FROM generate_series(1, 26000)) s
JOIN perf_users u ON u.n = s.un
WHERE NOT EXISTS (SELECT 1 FROM perf_reviews r WHERE r.user_id = u.id AND r.book_id = 900000000 + s.bn)
LIMIT 20000;

COMMIT;

-- 入れた量で、検索の計画（索引を使うか）を立て直させる
ANALYZE users, books, follows, reviews, review_photos, comments, helpfuls, reading_statuses;

SELECT (SELECT count(*) FROM users WHERE left(username, 5) = 'perf_') AS users,
       (SELECT count(*) FROM books WHERE left(google_books_id, 5) = 'perf-') AS books,
       (SELECT count(*) FROM reviews r JOIN users u ON u.id = r.user_id WHERE left(u.username, 5) = 'perf_') AS reviews,
       (SELECT count(*) FROM comments c JOIN users u ON u.id = c.user_id WHERE left(u.username, 5) = 'perf_') AS comments,
       (SELECT count(*) FROM helpfuls h JOIN users u ON u.id = h.user_id WHERE left(u.username, 5) = 'perf_') AS helpfuls,
       (SELECT count(*) FROM reading_statuses s JOIN users u ON u.id = s.user_id WHERE left(u.username, 5) = 'perf_') AS statuses,
       (SELECT count(*) FROM follows f JOIN users u ON u.id = f.follower_id
        WHERE left(u.username, 5) = 'perf_' AND u.username <= 'perf_0050') AS measured_follows;
