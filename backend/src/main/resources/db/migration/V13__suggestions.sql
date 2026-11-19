-- What the e-mail import proposes, and what became of it. A suggestion holds parts of someone's mail (a sender,
-- a subject, a quotation): it is personal data, kept here and nowhere else, and never written to a log.

CREATE TABLE suggestion (
    id               uuid        PRIMARY KEY,
    wedding_id       uuid        NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    -- What the import calls this proposal: the same key handed in again is the same suggestion.
    source_key       text        NOT NULL CHECK (length(btrim(source_key)) > 0 AND length(source_key) <= 200),
    kind             text        NOT NULL CHECK (kind IN ('TASK', 'PAYMENT', 'PLAN_ITEM', 'VENDOR')),
    state            text        NOT NULL CHECK (state IN ('WAITING', 'ACCEPTED', 'REJECTED')),

    -- What is proposed. Nothing here points to a row by a foreign key: a vendor or category that is gone
    -- must be said at accepting, not turned into "none" behind the person's back.
    title            text        NOT NULL CHECK (length(btrim(title)) > 0 AND length(title) <= 200),
    notes            text        NOT NULL DEFAULT '' CHECK (length(notes) <= 10000),
    start_date       date,
    due_date         date,
    amount           bigint      CHECK (amount >= 0),
    payment_kind     text        CHECK (payment_kind IN ('DEPOSIT', 'INSTALMENT', 'FINAL')),
    category_id      uuid,
    vendor_id        uuid,
    vendor_name      text        CHECK (length(vendor_name) <= 200),
    vendor_email     text        CHECK (length(vendor_email) <= 254),
    vendor_phone     text        CHECK (length(vendor_phone) <= 40),
    vendor_website   text        CHECK (length(vendor_website) <= 2000),

    -- Where it comes from.
    sender_name      text        NOT NULL DEFAULT '' CHECK (length(sender_name) <= 200),
    sender_address   text        NOT NULL DEFAULT '' CHECK (length(sender_address) <= 254),
    subject          text        NOT NULL DEFAULT '' CHECK (length(subject) <= 300),
    received_at      timestamptz NOT NULL,
    inbox_member_id  uuid        NOT NULL REFERENCES wedding_member (id) ON DELETE CASCADE,
    -- Only ever a message in Gmail: an https address at mail.google.com, nothing else.
    mail_link        text        NOT NULL CHECK (length(mail_link) <= 2000 AND mail_link ~ '^https://mail\.google\.com(/[^[:space:]\\]*)?$'),

    -- Why: a short quotation, never the whole message.
    passage          text        NOT NULL DEFAULT '' CHECK (length(passage) <= 1000),
    confidence       smallint    NOT NULL CHECK (confidence BETWEEN 0 AND 100),

    -- What became of it.
    decided_by       uuid        REFERENCES wedding_member (id) ON DELETE SET NULL,
    decided_at       timestamptz,
    -- The entry or vendor that accepting made. Changing or deleting it later does not touch the suggestion.
    made_entry_id    uuid,
    made_vendor_id   uuid,
    created_at       timestamptz NOT NULL,

    CONSTRAINT suggestion_source_unique UNIQUE (wedding_id, source_key),
    CONSTRAINT suggestion_decided_together CHECK ((state = 'WAITING') = (decided_at IS NULL)),
    CONSTRAINT suggestion_made_only_when_accepted CHECK (state = 'ACCEPTED' OR (made_entry_id IS NULL AND made_vendor_id IS NULL)),
    CONSTRAINT suggestion_made_one_thing CHECK (made_entry_id IS NULL OR made_vendor_id IS NULL)
);

CREATE INDEX suggestion_by_state ON suggestion (wedding_id, state, received_at DESC);
