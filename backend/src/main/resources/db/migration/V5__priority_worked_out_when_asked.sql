-- The priority of an entry left to the planner (AUTO) depends on today's date, so it is worked out
-- whenever it is asked for and no longer stored. The column keeps only what a person chose (MANUAL).

ALTER TABLE entry ALTER COLUMN priority DROP NOT NULL;

UPDATE entry SET priority = NULL WHERE priority_mode = 'AUTO';

ALTER TABLE entry
    ADD CONSTRAINT entry_only_a_chosen_priority_is_stored CHECK ((priority_mode = 'MANUAL') = (priority IS NOT NULL));
