-- レビュー（データ設計書 4.3）。1 人 1 冊に 1 つ（BR-2）。
-- ユーザーを消す（退会。A-12）と一緒に消え、本は消させない（本は消さない。データ設計書 5 章）
CREATE TABLE reviews (
    id         bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    book_id    bigint        NOT NULL REFERENCES books (id) ON DELETE RESTRICT,
    rating     smallint      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body       varchar(2000) NOT NULL,
    spoiler    boolean       NOT NULL DEFAULT false,
    created_at timestamptz   NOT NULL DEFAULT now(),
    edited_at  timestamptz,
    UNIQUE (user_id, book_id)
);

-- プロフィールのレビューの一覧と件数（PF-4）、「フォロー中」タブ（T-1）
CREATE INDEX reviews_user_id_id_idx ON reviews (user_id, id DESC);
-- 本のページのレビューの一覧・件数・平均評価（BK-3・BK-5）
CREATE INDEX reviews_book_id_id_idx ON reviews (book_id, id DESC);

-- 読書ステータス（データ設計書 4.8）。1 冊に 1 つ（ST-2）。ステータスを外したら行を消す（ST-3）
CREATE TABLE reading_statuses (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    book_id    bigint      NOT NULL REFERENCES books (id) ON DELETE RESTRICT,
    status     varchar(10) NOT NULL CHECK (status IN ('WANT', 'READING', 'READ')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, book_id)
);

-- 本棚の一覧と冊数（ST-4・ST-5）
CREATE INDEX reading_statuses_shelf_idx ON reading_statuses (user_id, status, updated_at DESC, book_id DESC);
