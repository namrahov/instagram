--liquibase formatted sql

--changeset instagram:006-posts
-- Minimal post: one uploaded media file and a caption. Likes need something to point at.
CREATE TABLE posts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL REFERENCES media(id),
    caption VARCHAR(2200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_posts_user ON posts (user_id, created_at DESC);
--rollback DROP TABLE posts;

--changeset instagram:006-likes
-- Section 10: who liked what. The primary key makes a like unique per (user, post): a double tap, a retry or two
-- parallel requests insert one row, the others hit ON CONFLICT DO NOTHING. Each like is its own row, so 10,000
-- likes on one post are 10,000 inserts into different rows, without fighting over one row lock.
CREATE TABLE likes (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);
-- Who liked this post, newest first.
CREATE INDEX idx_likes_post ON likes (post_id, created_at DESC);

-- Queue of count changes, written in the same transaction as the like row: +1 for a new like, -1 for an unlike.
-- The counter job takes a batch, adds it up per post and deletes it, in one statement. A row that is here has not
-- been counted; a counted row is gone. That is how it never counts twice and never loses one.
CREATE TABLE like_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    post_id BIGINT NOT NULL,
    delta SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_like_events_delta CHECK (delta IN (-1, 1))
);

-- The aggregate shown to users. Updated only by the counter job, a few times per second instead of once per like.
-- Not checked >= 0: two job instances may apply a like and its unlike in either order, so it can dip briefly.
CREATE TABLE post_like_counts (
    post_id BIGINT PRIMARY KEY REFERENCES posts(id) ON DELETE CASCADE,
    like_count BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--rollback DROP TABLE post_like_counts;
--rollback DROP TABLE like_events;
--rollback DROP TABLE likes;
