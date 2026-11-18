-- Who is invited, who is coming and what they need. Everything here is personal data:
-- it is kept in these tables and nowhere else, and never written to a log.

-- People who are invited together and answer together: a family, a couple.
CREATE TABLE household (
    id         uuid PRIMARY KEY,
    wedding_id uuid NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    name       text NOT NULL CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX household_name_unique ON household (wedding_id, lower(name));

CREATE TABLE guest (
    id                  uuid        PRIMARY KEY,
    wedding_id          uuid        NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    name                text        NOT NULL CHECK (length(btrim(name)) > 0),
    -- Whose guest: one of the two members, or nobody in particular when the guest is both's.
    side_member_id      uuid        REFERENCES wedding_member (id) ON DELETE SET NULL,
    -- A household that is removed leaves its guests behind, on their own.
    household_id        uuid        REFERENCES household (id) ON DELETE SET NULL,
    status              text        NOT NULL CHECK (status IN ('NOT_INVITED', 'INVITED', 'CONFIRMED', 'DECLINED', 'MAYBE')),
    -- Comes with one more person who is not on the list by name.
    plus_one            boolean     NOT NULL DEFAULT false,
    children            integer     NOT NULL DEFAULT 0 CHECK (children BETWEEN 0 AND 20),
    dietary_needs       text        NOT NULL DEFAULT '',
    needs_accommodation boolean     NOT NULL DEFAULT false,
    phone               text        NOT NULL DEFAULT '',
    email               text        NOT NULL DEFAULT '',
    notes               text        NOT NULL DEFAULT '',
    -- Counts the saves, so that a save based on an outdated copy can be refused.
    version             integer     NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

CREATE INDEX guest_wedding ON guest (wedding_id);
CREATE INDEX guest_household ON guest (household_id) WHERE household_id IS NOT NULL;
