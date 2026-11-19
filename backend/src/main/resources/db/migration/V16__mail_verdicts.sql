-- What the import makes of a noted message: the order it was noted in, and the verdict once it has been read.
-- Still nothing of a message's content: no sender, no subject, no text, no date of the message. A verdict is a
-- word from a fixed set, a number of proposals and a time.
--
-- "Newest first" cannot come from the message's date, which is deliberately not kept. It comes from the order of
-- noting: a later look is newer than an earlier one, and within one look the order is the order Google listed
-- the messages in. The first look lists newest first and the later ones oldest first, so a row carries a number
-- that is larger the newer the message is, within its look.

CREATE SEQUENCE mail_look_seq;
CREATE SEQUENCE mail_noted_seq;

ALTER TABLE mail_message
    ADD COLUMN look          bigint,
    ADD COLUMN newness       bigint,
    ADD COLUMN verdict       text,
    ADD COLUMN unread_reason text,
    ADD COLUMN proposals     integer,
    ADD COLUMN read_at       timestamptz;

-- Nothing set read_by_import before this: it would be a verdict made up here.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM mail_message WHERE read_by_import) THEN
        RAISE EXCEPTION 'mail_message has messages marked as read, which nothing could have done';
    END IF;
END
$$;

-- Messages noted before this: one look of their own (older than every later one), the later noted the newer.
UPDATE mail_message m
SET look = 0, newness = ordered.position
FROM (
    SELECT member_id, message_id, row_number() OVER (ORDER BY noted_at, member_id, message_id) AS position
    FROM mail_message
) ordered
WHERE m.member_id = ordered.member_id AND m.message_id = ordered.message_id;

ALTER TABLE mail_message
    ALTER COLUMN look SET NOT NULL,
    ALTER COLUMN newness SET NOT NULL;

DROP INDEX mail_message_not_read;
ALTER TABLE mail_message DROP COLUMN read_by_import;

-- Whether a message has been read is whether it has a verdict: one truth, not two.
ALTER TABLE mail_message
    ADD CONSTRAINT mail_message_verdict_known CHECK (verdict IN ('NOT_WEDDING', 'WEDDING', 'UNREADABLE')),
    ADD CONSTRAINT mail_message_reason_known CHECK (
        unread_reason IN ('GONE', 'NO_TEXT', 'TOO_LARGE', 'DECLINED', 'UNUSABLE_ANSWER', 'TOO_MANY_PROPOSALS')),
    ADD CONSTRAINT mail_message_verdict_whole CHECK (
        (verdict IS NULL AND unread_reason IS NULL AND proposals IS NULL AND read_at IS NULL)
        OR (verdict IS NOT DISTINCT FROM 'NOT_WEDDING' AND unread_reason IS NULL AND proposals IS NULL AND read_at IS NOT NULL)
        OR (verdict IS NOT DISTINCT FROM 'WEDDING' AND unread_reason IS NULL AND proposals IS NOT NULL AND proposals >= 0 AND read_at IS NOT NULL)
        OR (verdict IS NOT DISTINCT FROM 'UNREADABLE' AND unread_reason IS NOT NULL AND proposals IS NULL AND read_at IS NOT NULL));

-- What the import asks for: the messages without a verdict, the newest first.
CREATE INDEX mail_message_not_read ON mail_message (look DESC, newness DESC) WHERE verdict IS NULL;
