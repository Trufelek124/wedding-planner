-- Which messages the sync has noted in a connected mailbox (S03 section 4), and how it is faring with Google.
-- Of a message only Google's two identifiers are kept: no sender, no subject, no text, no labels and no date.
-- Disconnecting removes the connection and with it this list.

CREATE TABLE mail_message (
    member_id      uuid        NOT NULL REFERENCES mail_connection (member_id) ON DELETE CASCADE,
    -- Google's identifier of the message, and of the thread it belongs to.
    message_id     text        NOT NULL CHECK (length(message_id) BETWEEN 1 AND 128),
    thread_id      text        NOT NULL CHECK (length(thread_id) BETWEEN 1 AND 128),
    -- When the sync noted it; not when the message was written.
    noted_at       timestamptz NOT NULL,
    -- Whether the import has read the message yet. The sync only notes: it is always false until the story that reads sets it.
    read_by_import boolean     NOT NULL DEFAULT false,
    -- A message is noted once per mailbox, however many looks name it.
    PRIMARY KEY (member_id, message_id)
);

-- What the reading import will ask for: the messages not read yet, oldest noted first.
CREATE INDEX mail_message_not_read ON mail_message (member_id, noted_at) WHERE NOT read_by_import;

-- Google refused or failed in a row this many times; until the time given the mailbox is left alone.
-- Both are kept here so that a restart of the server does not forget them. A complete look resets them.
ALTER TABLE mail_connection
    ADD COLUMN refusals      integer     NOT NULL DEFAULT 0 CHECK (refusals >= 0),
    ADD COLUMN put_off_until timestamptz;
