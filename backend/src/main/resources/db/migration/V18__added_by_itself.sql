-- Proposals the import is sure of are added by themselves (S05, D-008): a fourth state of a suggestion, the
-- entry's memory of which suggestion made it, and the wedding's setting for how sure the import has to be.

-- ---------------------------------------------------------------- an entry says which suggestion made it
-- No foreign key on purpose: planning knows a suggestion only as an identifier, and an entry outlives it.
ALTER TABLE entry ADD COLUMN from_suggestion_id uuid;

-- The entries that accepting already made learn where they come from. Their version stays as it is.
UPDATE entry e
SET from_suggestion_id = s.id
FROM suggestion s
WHERE s.wedding_id = e.wedding_id AND s.made_entry_id = e.id AND s.state = 'ACCEPTED';

CREATE INDEX entry_from_suggestion ON entry (from_suggestion_id) WHERE from_suggestion_id IS NOT NULL;

-- ---------------------------------------------------------------- a suggestion can be added by itself
ALTER TABLE suggestion DROP CONSTRAINT suggestion_state_check;
ALTER TABLE suggestion ADD CONSTRAINT suggestion_state_check
    CHECK (state IN ('WAITING', 'ADDED', 'ACCEPTED', 'REJECTED'));

-- Waiting and added are the two states nobody has decided: neither has a time or a person of decision.
ALTER TABLE suggestion DROP CONSTRAINT suggestion_decided_together;
ALTER TABLE suggestion ADD CONSTRAINT suggestion_decided_together
    CHECK ((state IN ('WAITING', 'ADDED')) = (decided_at IS NULL));
ALTER TABLE suggestion ADD CONSTRAINT suggestion_undecided_has_no_decider
    CHECK (state NOT IN ('WAITING', 'ADDED') OR decided_by IS NULL);

-- Something exists only for a suggestion that is accepted or added; undoing clears it.
ALTER TABLE suggestion DROP CONSTRAINT suggestion_made_only_when_accepted;
ALTER TABLE suggestion ADD CONSTRAINT suggestion_made_only_when_accepted
    CHECK (state IN ('ACCEPTED', 'ADDED') OR (made_entry_id IS NULL AND made_vendor_id IS NULL));

-- An added suggestion has its entry, and is never money or a vendor (D-008): a payment is a task with an amount.
ALTER TABLE suggestion ADD CONSTRAINT suggestion_added_has_its_entry
    CHECK (state <> 'ADDED' OR made_entry_id IS NOT NULL);
ALTER TABLE suggestion ADD CONSTRAINT suggestion_added_only_tasks_and_steps
    CHECK (state <> 'ADDED' OR (kind IN ('TASK', 'PLAN_ITEM') AND amount IS NULL));

-- ---------------------------------------------------------------- how sure the import has to be
-- One row per wedding, written when it is first changed: no row means "on, at 85". Switching it off keeps the number.
CREATE TABLE suggestion_setting (
    wedding_id     uuid        PRIMARY KEY REFERENCES wedding (id) ON DELETE CASCADE,
    adds_by_itself boolean     NOT NULL,
    threshold      smallint    NOT NULL CHECK (threshold BETWEEN 50 AND 100),
    updated_at     timestamptz NOT NULL
);
