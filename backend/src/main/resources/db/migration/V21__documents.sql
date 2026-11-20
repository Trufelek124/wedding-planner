-- The PDF documents that come with a wedding mail are read too (S04 sections 3 and 5, D-011). What the table keeps for it
-- is a state and nothing of a document: not its name, its size, its text.
--
--   PENDING  the message's documents are still to be looked at (the first look, or for a message that was read before
--            documents could be, the second look)
--   BEGUN    being fetched and read now, or the server stopped while it was. Written before anything is fetched, so that a
--            message whose documents stopped the server is not opened a second time: that is the one try. When the text is
--            out and the server still runs, the reading takes the mark back (BEGUN to PENDING): what comes after (the AI
--            service, the planner) is not part of the try
--   DONE     dealt with: read, or not to be read, or nothing to read
--
-- A message that has no verdict yet is PENDING: its documents are dealt with when it is read, and marking it with its
-- verdict ends it as DONE (or leaves it PENDING where the reading could not read the documents just then). A message
-- that was read before this and was about the wedding is PENDING too, and is read once more for its documents. Every
-- other verdict (not about the wedding, could not be read, a copy of a letter that was read) needs no second look and
-- is DONE from the start.

ALTER TABLE mail_message ADD COLUMN documents text NOT NULL DEFAULT 'PENDING';

UPDATE mail_message SET documents = 'DONE' WHERE verdict IS NOT NULL AND verdict <> 'WEDDING';

ALTER TABLE mail_message
    ADD CONSTRAINT mail_message_documents_known CHECK (documents IN ('PENDING', 'BEGUN', 'DONE')),
    -- Only a message that is not read yet, or one that was about the wedding, can still have documents to look at.
    ADD CONSTRAINT mail_message_documents_pending_only_where_they_can_be
        CHECK (documents <> 'PENDING' OR verdict IS NULL OR verdict = 'WEDDING');

-- What the second look asks: the messages about the wedding whose documents were never looked at, the newest first.
CREATE INDEX mail_message_documents_to_look_at ON mail_message (look DESC, newness DESC)
    WHERE verdict = 'WEDDING' AND documents = 'PENDING';
