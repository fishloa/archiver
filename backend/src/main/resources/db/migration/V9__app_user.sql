CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    display_name TEXT,
    role         TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('user', 'admin')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user_email (
    id      BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    email   TEXT NOT NULL UNIQUE
);

CREATE INDEX idx_app_user_email_email ON app_user_email(email);

INSERT INTO app_user (id, display_name, role) VALUES (1, 'Tim', 'admin');
INSERT INTO app_user_email (user_id, email) VALUES (1, 'timothy.corbettclark@gmail.com');

SELECT setval('app_user_id_seq', (SELECT MAX(id) FROM app_user));
