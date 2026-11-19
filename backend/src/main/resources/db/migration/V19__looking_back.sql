-- Earlier mail is noted only when its owner asks for it (D-009, S03 sections 5 and 6): the first look no longer
-- notes the last ninety days, and a look back is asked for on the e-mail page, for the last 7 to 90 days.
--
-- What was asked is kept with the connection, so that it is done again after a restart or a refusal by Google.
-- Nothing here is about the content of a message: a number of days, moments, and a count.

ALTER TABLE mail_connection
    -- How many days were asked for; null while nothing was asked.
    ADD COLUMN look_back_days     integer,
    -- When it was asked, and from which moment on mail is wanted (fixed when it was asked: then, less the days).
    ADD COLUMN look_back_asked_at timestamptz,
    ADD COLUMN look_back_from     timestamptz,
    -- The look of the ask (the sequence of the looks): a message found by it is as new as that look, so a look back that
    -- is done again after being cut off keeps the order the first run began with. A new ask has a new one.
    ADD COLUMN look_back_look     bigint,
    -- When the last page was stored; null while it is under way.
    ADD COLUMN look_back_done_at  timestamptz,
    -- How many messages this ask noted. Counted in the same transaction as the noting, so a run that is done
    -- again counts each message once.
    ADD COLUMN look_back_found    integer     NOT NULL DEFAULT 0,
    ADD CONSTRAINT mail_connection_look_back_days CHECK (look_back_days IS NULL OR look_back_days BETWEEN 7 AND 90),
    ADD CONSTRAINT mail_connection_look_back_found CHECK (look_back_found >= 0),
    -- Asked means: all of days, asked_at, from and look are there, and the moment from lies before the asking.
    -- Not asked means: none is there, and nothing was found.
    ADD CONSTRAINT mail_connection_look_back_whole CHECK (
        (look_back_days IS NULL AND look_back_asked_at IS NULL AND look_back_from IS NULL AND look_back_look IS NULL
            AND look_back_done_at IS NULL AND look_back_found = 0)
        OR (look_back_days IS NOT NULL AND look_back_asked_at IS NOT NULL AND look_back_from IS NOT NULL
            AND look_back_look IS NOT NULL AND look_back_from < look_back_asked_at
            AND (look_back_done_at IS NULL OR look_back_done_at >= look_back_asked_at)));

-- Mail that the old first look noted and nobody has read yet is let go of (D-009): on a server that was connected before
-- this, three months would otherwise still be read unasked. A message that was read keeps its row and its verdict:
-- nothing that came of it (a suggestion's source key) is touched, and it is not noted, and so not read, a second time.
-- The look back that a person asks for finds the others again.
DELETE FROM mail_message WHERE verdict IS NULL;
