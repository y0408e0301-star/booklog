-- フォロー（データ設計書 4.7）。同じ人を 2 回フォローしない（主キー。F-4）、自分自身はフォローできない（F-3）。
-- どちらのユーザーを消しても（退会。A-12）一緒に消える
CREATE TABLE follows (
    follower_id bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id bigint      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);

-- フォロワーの一覧と件数（F-5）
CREATE INDEX follows_followee_id_created_at_idx ON follows (followee_id, created_at DESC);
