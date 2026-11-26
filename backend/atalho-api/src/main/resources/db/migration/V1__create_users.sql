-- E-mails are normalized (trimmed, lower-cased) by the application before they reach the
-- database; the CHECK constraint guarantees it, so the plain UNIQUE constraint is case-insensitive
-- in practice. This avoids citext, whose comparisons silently become case-sensitive when the
-- driver binds parameters as varchar.
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash TEXT         NOT NULL,
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);
