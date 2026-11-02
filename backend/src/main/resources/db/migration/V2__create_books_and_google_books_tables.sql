-- 本（データ設計書 4.2）。Google Books API から取り込み、取り込んだ後は変えず、消さない。
-- 同じ本（Google Books の ID が同じ）は 1 つにまとめる（B-6、BR-11）
CREATE TABLE books (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    google_books_id varchar(40)  NOT NULL UNIQUE,
    isbn13          char(13),
    title           varchar(500) NOT NULL,
    authors         text[]       NOT NULL DEFAULT '{}',
    publisher       varchar(200),
    published_date  varchar(10),
    description     text,
    thumbnail_url   varchar(500),
    created_at      timestamptz  NOT NULL DEFAULT now()
);

-- 書籍検索の結果（データ設計書 4.11）。同じ問い合わせ・同じページを 24 時間使い回す（B-9、BR-12）
CREATE TABLE book_search_results (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    query      varchar(110) NOT NULL,
    page       smallint     NOT NULL CHECK (page BETWEEN 1 AND 5),
    result     jsonb        NOT NULL,
    fetched_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (query, page)
);

-- 24 時間を過ぎたものをまとめて消す（BR-12）
CREATE INDEX book_search_results_fetched_at_idx ON book_search_results (fetched_at);

-- Google Books API の日ごとの呼び出し回数（データ設計書 4.12）。日付は日本時間（BR-13）
CREATE TABLE google_books_usage (
    usage_date date        PRIMARY KEY,
    call_count integer     NOT NULL DEFAULT 0 CHECK (call_count BETWEEN 0 AND 1000),
    updated_at timestamptz NOT NULL DEFAULT now()
);
