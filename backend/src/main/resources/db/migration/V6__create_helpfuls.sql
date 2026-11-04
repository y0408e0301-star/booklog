-- 参考になった（データ設計書 4.5）。1 人 1 レビューに 1 つ（主キー。H-3）。
-- レビューを消すと一緒に消え（FN-05 5 章の 3）、押した人が退会しても一緒に消える（A-12）
CREATE TABLE helpfuls (
    user_id    bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    review_id  bigint      NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, review_id)
);

-- 参考になった数（H-5）と、レビューを消したときの一緒の削除
CREATE INDEX helpfuls_review_id_idx ON helpfuls (review_id);
