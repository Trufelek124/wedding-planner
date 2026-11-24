-- A payment that a document names is added by itself (S05, D-014). A suggestion remembers whether its passage stands in
-- the text of a document that was read with the message: the server's own finding, never the model's word.
ALTER TABLE suggestion ADD COLUMN from_document boolean NOT NULL DEFAULT false;

-- What was handed in before this came from the message's words as far as anybody knows: false, as the default says.
-- The default stays so that nothing that writes a row without saying becomes "from a document".

-- What may be ADDED: a task or a step without an amount (D-008), or a payment with an amount whose passage stands in a
-- document (D-014). Never a vendor, never a payment that only the mail's words name.
ALTER TABLE suggestion DROP CONSTRAINT suggestion_added_only_tasks_and_steps;
ALTER TABLE suggestion ADD CONSTRAINT suggestion_added_only_what_may_be_added
    CHECK (state <> 'ADDED'
           OR (kind IN ('TASK', 'PLAN_ITEM') AND amount IS NULL)
           OR (kind = 'PAYMENT' AND amount IS NOT NULL AND from_document));

COMMENT ON COLUMN suggestion.from_document IS
    'Whether the passage stands in the text of a PDF document that came with the message (D-014); found by the server.';
