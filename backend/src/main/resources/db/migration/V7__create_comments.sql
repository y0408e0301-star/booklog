-- コメント（データ設計書 4.6）。レビューを消すと一緒に消え（FN-06 5 章の 2）、書いた人が退会しても一緒に消える（A-12）
CREATE TABLE comments (
    id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id  bigint       NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id    bigint       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body       varchar(500) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now()
);

-- コメントの一覧と件数（C-3）
CREATE INDEX comments_review_id_id_idx ON comments (review_id, id);
-- 退会のときの一緒の削除
CREATE INDEX comments_user_id_idx ON comments (user_id);
