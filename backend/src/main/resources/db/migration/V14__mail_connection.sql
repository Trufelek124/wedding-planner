-- Each owner's connection to their own mailbox (Gmail), and the one-time tickets that start it.
-- What reads the mailbox later adds its own tables; here is only what connecting needs.
-- Nothing here may hold a secret in plain: the token is encrypted by the application with a key that is
-- not in the database, and a ticket is only ever stored as hashes.

CREATE TABLE mail_connection (
    -- One connection per owner. Removing the owner removes the connection with its token.
    member_id        uuid        PRIMARY KEY REFERENCES wedding_member (id) ON DELETE CASCADE,
    state            text        NOT NULL CHECK (state IN ('CONNECTED', 'NEEDS_RECONNECTING')),
    -- The mailbox, as Google names it (lower case). Shown to its owner only.
    address          text        NOT NULL CHECK (length(address) BETWEEN 3 AND 254 AND address = lower(address) AND position('@' IN address) > 1),
    -- Google's long-lived token, sealed with AES-256-GCM: version byte, nonce, ciphertext and tag.
    -- Empty exactly when the connection has to be made again: a token that is no good is not kept.
    token_sealed     bytea       CHECK (octet_length(token_sealed) BETWEEN 30 AND 4096),
    connected_at     timestamptz NOT NULL,
    -- Where the sync stands, and when it last looked: the sync fills them in, connecting another mailbox clears them.
    sync_marker      text        CHECK (length(sync_marker) <= 200),
    last_look_at     timestamptz,
    CONSTRAINT mail_connection_token_when_connected CHECK ((state = 'CONNECTED') = (token_sealed IS NOT NULL))
);

CREATE TABLE mail_connect_ticket (
    -- SHA-256 of the random value ("state") that Google sends back. The value itself is never stored.
    state_hash       bytea       PRIMARY KEY CHECK (octet_length(state_hash) = 32),
    member_id        uuid        NOT NULL REFERENCES wedding_member (id) ON DELETE CASCADE,
    -- SHA-256 of the random value set as a cookie in the browser that asked: the ticket is good only from there.
    cookie_hash      bytea       NOT NULL CHECK (octet_length(cookie_hash) = 32),
    -- The PKCE verifier, sealed like the token (its additional data is the state's hash).
    verifier_sealed  bytea       NOT NULL CHECK (octet_length(verifier_sealed) BETWEEN 30 AND 1024),
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    -- Set by the one return that takes the ticket; a ticket with a time here is spent.
    used_at          timestamptz,
    CONSTRAINT mail_connect_ticket_lives_on CHECK (expires_at > created_at)
);

CREATE INDEX mail_connect_ticket_by_member ON mail_connect_ticket (member_id);
CREATE INDEX mail_connect_ticket_by_expiry ON mail_connect_ticket (expires_at);
