-- Who may use the planner, and their sign-in sessions.

CREATE TABLE app_user (
    id             uuid        PRIMARY KEY,
    email          text        NOT NULL,
    -- Google's permanent identifier of the account; empty until the first sign-in with Google.
    google_subject text,
    display_name   text        NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT app_user_email_is_lowercase CHECK (email = lower(email)),
    CONSTRAINT app_user_email_unique UNIQUE (email),
    CONSTRAINT app_user_google_subject_unique UNIQUE (google_subject)
);

-- A refresh token is used once and replaced. Only its hash is stored.
-- All tokens that descend from one sign-in share a family, so they can be revoked together.
CREATE TABLE refresh_token (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash text        NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_token_family ON refresh_token (family_id);
CREATE INDEX refresh_token_user ON refresh_token (user_id);
