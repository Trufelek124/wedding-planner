-- The pictures of an inspiration. The files themselves are on disk, under the media directory,
-- named after the id here and nothing else.

CREATE TABLE inspiration_picture (
    id             uuid        PRIMARY KEY,
    inspiration_id uuid        NOT NULL REFERENCES inspiration (id) ON DELETE CASCADE,
    -- The order within the inspiration; the first one is what a card shows.
    position       integer     NOT NULL,
    -- Worked out from the file's content when it arrived, never taken from the sender.
    content_type   text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/gif', 'image/webp')),
    size_bytes     bigint      NOT NULL CHECK (size_bytes > 0),
    -- As a person sees the picture, the right way up; unknown for kinds the server cannot read.
    width          integer,
    height         integer,
    -- Whether a smaller version for the board was made.
    has_thumbnail  boolean     NOT NULL,
    created_at     timestamptz NOT NULL
);

CREATE INDEX inspiration_picture_inspiration ON inspiration_picture (inspiration_id, position);
