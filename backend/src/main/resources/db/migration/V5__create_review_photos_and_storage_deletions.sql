-- 写真（データ設計書 4.4）。レビューを消すと一緒に消える。1 つのレビューに 4 枚まで（並び順 0〜3。BR-3）。
-- 編集で並べ直すときに途中で重ならないよう、並び順の一意の制約はトランザクションの終わりで確かめる
CREATE TABLE review_photos (
    id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id    bigint       NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    storage_key  varchar(100) NOT NULL UNIQUE,
    content_type varchar(20)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    byte_size    integer      NOT NULL CHECK (byte_size BETWEEN 1 AND 5242880),
    sort_order   smallint     NOT NULL CHECK (sort_order BETWEEN 0 AND 3),
    CONSTRAINT review_photos_review_id_sort_order_key UNIQUE (review_id, sort_order) DEFERRABLE INITIALLY DEFERRED
);

-- S3 から消せなかったファイル（データ設計書 4.10）。ほかの表とはつながない。1 日 1 回消し直す（BR-5）
CREATE TABLE storage_deletions (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    storage_key varchar(100) NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL DEFAULT now()
);
