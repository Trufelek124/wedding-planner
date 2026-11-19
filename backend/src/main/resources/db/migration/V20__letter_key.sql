-- One letter that went to both owners is two messages for Google and one letter (S04 sections 2 and 6): the sender gave
-- both copies the same identifier (the Message-ID header). What is kept to recognise a copy is a key worked out from that
-- identifier and the wedding: a one-way hash that says nothing and cannot be turned back. The identifier itself is not
-- kept, and nothing of the letter's content.
--
-- The key is kept with the verdict of a message that was read as a letter. A message that was read before this has no key,
-- and one that was marked without a letter having been had (gone, no text, too large) never has one.

ALTER TABLE mail_message ADD COLUMN letter_key text;

-- A new verdict: this message is a copy of a letter that another noted message of the wedding had read.
ALTER TABLE mail_message
    DROP CONSTRAINT mail_message_verdict_known,
    DROP CONSTRAINT mail_message_verdict_whole;

ALTER TABLE mail_message
    ADD CONSTRAINT mail_message_verdict_known CHECK (verdict IN ('NOT_WEDDING', 'WEDDING', 'UNREADABLE', 'COPY')),
    ADD CONSTRAINT mail_message_verdict_whole CHECK (
        (verdict IS NULL AND unread_reason IS NULL AND proposals IS NULL AND read_at IS NULL)
        OR (verdict IS NOT DISTINCT FROM 'NOT_WEDDING' AND unread_reason IS NULL AND proposals IS NULL AND read_at IS NOT NULL)
        OR (verdict IS NOT DISTINCT FROM 'WEDDING' AND unread_reason IS NULL AND proposals IS NOT NULL AND proposals >= 0 AND read_at IS NOT NULL)
        OR (verdict IS NOT DISTINCT FROM 'UNREADABLE' AND unread_reason IS NOT NULL AND proposals IS NULL AND read_at IS NOT NULL)
        OR (verdict IS NOT DISTINCT FROM 'COPY' AND unread_reason IS NULL AND proposals IS NULL AND read_at IS NOT NULL)),
    -- "l1-" and a SHA-256 in base64url without padding: the same form as the other keys, nothing a person could read.
    ADD CONSTRAINT mail_message_letter_key_form CHECK (letter_key IS NULL OR letter_key ~ '^l1-[A-Za-z0-9_-]{43}$'),
    -- A key is kept only with a verdict, and a copy is a copy of something: it has the key that says of what.
    ADD CONSTRAINT mail_message_letter_key_with_verdict CHECK (letter_key IS NULL OR verdict IS NOT NULL),
    ADD CONSTRAINT mail_message_copy_has_key CHECK (verdict IS DISTINCT FROM 'COPY' OR letter_key IS NOT NULL);

-- What is asked when a letter is reached: was another message with this key read? (A key holds the wedding, too.)
CREATE INDEX mail_message_letter_key ON mail_message (letter_key) WHERE letter_key IS NOT NULL;
