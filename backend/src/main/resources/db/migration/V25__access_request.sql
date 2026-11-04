-- Someone who signs in with an address that is not on the allowlist can ask for access. One row
-- per address: a pending request is updated in place, a decided one stays as the record of who
-- decided and when. Deleting a denied row lets that address ask again.
CREATE TABLE access_request (
    id          bigserial PRIMARY KEY,
    email       text NOT NULL UNIQUE CHECK (email = lower(email)),
    name        text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    message     text NOT NULL DEFAULT '' CHECK (length(message) <= 2000),
    status      text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'denied')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    decided_at  timestamptz,
    decided_by  text,
    user_id     bigint REFERENCES app_user (id) ON DELETE SET NULL
);

CREATE INDEX idx_access_request_status ON access_request (status);
