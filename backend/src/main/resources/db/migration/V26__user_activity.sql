-- What people with access did: one row per recorded request. Sessions are not stored; they are
-- computed from gaps between a user's events. Rows older than two years are purged daily.
CREATE TABLE user_activity (
    id        bigserial PRIMARY KEY,
    user_id   bigint REFERENCES app_user (id) ON DELETE SET NULL,
    email     text NOT NULL,
    at        timestamptz NOT NULL DEFAULT now(),
    method    text NOT NULL,
    path      text NOT NULL,
    query     text,
    status    integer NOT NULL,
    kind      text NOT NULL,
    target    text
);

CREATE INDEX idx_user_activity_user_at ON user_activity (user_id, at);
CREATE INDEX idx_user_activity_at ON user_activity (at);
