-- The one wedding of this installation and the two people it belongs to.

CREATE TABLE wedding (
    id           uuid        PRIMARY KEY,
    -- Empty until the couple has fixed the day.
    wedding_date date,
    -- ISO 4217, for example PLN. All amounts of the planner are in this currency.
    currency     text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- IANA name, for example Europe/Warsaw. "Today" and every due date are read in this zone.
    time_zone    text        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    -- One wedding per installation: this column can hold only one value, and it is unique.
    only_one     boolean     NOT NULL DEFAULT true CHECK (only_one),
    CONSTRAINT wedding_only_one UNIQUE (only_one)
);

-- A member is one of the two people. It exists from the setup on, so that things can be given to
-- "the other one" before that person has signed in; signing in ties the member to a user.
CREATE TABLE wedding_member (
    id           uuid     PRIMARY KEY,
    wedding_id   uuid     NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    position     smallint NOT NULL CHECK (position IN (1, 2)),
    display_name text     NOT NULL CHECK (length(btrim(display_name)) > 0),
    user_id      uuid     REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT wedding_member_position_unique UNIQUE (wedding_id, position),
    CONSTRAINT wedding_member_user_unique UNIQUE (user_id)
);
