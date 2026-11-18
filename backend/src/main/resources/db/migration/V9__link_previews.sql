-- For a saved link the server tries to fetch the page's title and its preview picture.

ALTER TABLE inspiration
    -- NONE: nothing to fetch, or nothing could be fetched. PENDING: still to be tried. FETCHED: something was found.
    ADD COLUMN preview_status   text        NOT NULL DEFAULT 'NONE' CHECK (preview_status IN ('NONE', 'PENDING', 'FETCHED')),
    -- The title the page gives itself. Kept apart from the title a person types, so neither overwrites the other.
    ADD COLUMN preview_title    text        NOT NULL DEFAULT '',
    ADD COLUMN preview_attempts integer     NOT NULL DEFAULT 0,
    -- When to try (again); only meaningful while PENDING.
    ADD COLUMN preview_next_at  timestamptz;

CREATE INDEX inspiration_preview_pending ON inspiration (preview_next_at) WHERE preview_status = 'PENDING';

-- Whether the picture was fetched from the link rather than sent by a person.
ALTER TABLE inspiration_picture ADD COLUMN preview boolean NOT NULL DEFAULT false;
