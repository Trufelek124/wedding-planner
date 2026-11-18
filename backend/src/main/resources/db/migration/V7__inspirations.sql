-- A place for ideas: links and pictures, kept on boards the couple names themselves.

CREATE TABLE board (
    id         uuid    PRIMARY KEY,
    wedding_id uuid    NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    name       text    NOT NULL CHECK (length(btrim(name)) > 0),
    position   integer NOT NULL
);

CREATE UNIQUE INDEX board_name_unique ON board (wedding_id, lower(name));

-- The starter boards are offered once; removing them all must not bring them back.
CREATE TABLE inspirations_setup (
    wedding_id        uuid        PRIMARY KEY REFERENCES wedding (id) ON DELETE CASCADE,
    boards_offered_at timestamptz NOT NULL
);

CREATE TABLE inspiration (
    id         uuid        PRIMARY KEY,
    wedding_id uuid        NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    -- The page the idea comes from; none for pictures of one's own.
    url        text        CHECK (url IS NULL OR url ~ '^https?://'),
    -- Where it comes from, worked out from the link; PHOTO when there is no link.
    source     text        NOT NULL CHECK (source IN ('INSTAGRAM', 'TIKTOK', 'PINTEREST', 'WEB', 'PHOTO')),
    title      text        NOT NULL DEFAULT '',
    note       text        NOT NULL DEFAULT '',
    -- A board that is removed leaves its ideas behind, without a board, unless the couple chose otherwise.
    board_id   uuid        REFERENCES board (id) ON DELETE SET NULL,
    -- The plan item the idea belongs to; the link goes when the plan item does.
    entry_id   uuid        REFERENCES entry (id) ON DELETE SET NULL,
    -- Counts the saves, so that a save based on an outdated copy can be refused.
    version    integer     NOT NULL DEFAULT 0,
    added_by   uuid        REFERENCES wedding_member (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT inspiration_source_fits_link CHECK ((url IS NULL) = (source = 'PHOTO'))
);

CREATE INDEX inspiration_wedding ON inspiration (wedding_id, created_at DESC);
CREATE INDEX inspiration_board ON inspiration (board_id);
CREATE INDEX inspiration_entry ON inspiration (entry_id);

-- Tags are kept in lower case, so that "Boho" and "boho" are one tag.
CREATE TABLE inspiration_tag (
    inspiration_id uuid NOT NULL REFERENCES inspiration (id) ON DELETE CASCADE,
    tag            text NOT NULL CHECK (length(btrim(tag)) > 0 AND tag = lower(tag)),
    PRIMARY KEY (inspiration_id, tag)
);

CREATE INDEX inspiration_tag_tag ON inspiration_tag (tag);
