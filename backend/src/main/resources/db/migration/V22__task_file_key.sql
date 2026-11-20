-- A task that came from a file of tasks (S06, D-013) remembers the key its row had, so that a later file knows the row
-- again. The file itself is not kept. Nothing of the key is part of any answer of the API.
ALTER TABLE entry ADD COLUMN task_key text;

-- What the author of the file chose: 1 to 100 characters (the format says more, and the server holds to it).
ALTER TABLE entry ADD CONSTRAINT entry_task_key_length
    CHECK (task_key IS NULL OR char_length(task_key) BETWEEN 1 AND 100);

-- One key, one entry, per wedding; capital and small letters are different keys. Entries made any other way have none.
-- The last word when two uploads of the same new key arrive at the same moment.
CREATE UNIQUE INDEX entry_task_key ON entry (wedding_id, task_key) WHERE task_key IS NOT NULL;

COMMENT ON COLUMN entry.task_key IS
    'The key of the row of an uploaded file of tasks that made this entry (S06); null for an entry made any other way. Unique per wedding.';
