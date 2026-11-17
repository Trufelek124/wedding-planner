-- Everything there is to do for the wedding: plan items (steps that take time) and tasks
-- (single actions) are one kind of record, so one can be turned into the other.

CREATE TABLE category (
    id         uuid    PRIMARY KEY,
    wedding_id uuid    NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    name       text    NOT NULL CHECK (length(btrim(name)) > 0),
    position   integer NOT NULL
);

CREATE UNIQUE INDEX category_name_unique ON category (wedding_id, lower(name));

-- The starter categories are offered once; deleting them all must not bring them back.
CREATE TABLE planning_setup (
    wedding_id            uuid        PRIMARY KEY REFERENCES wedding (id) ON DELETE CASCADE,
    categories_offered_at timestamptz NOT NULL
);

CREATE TABLE entry (
    id            uuid        PRIMARY KEY,
    wedding_id    uuid        NOT NULL REFERENCES wedding (id) ON DELETE CASCADE,
    kind          text        NOT NULL CHECK (kind IN ('PLAN_ITEM', 'TASK')),
    title         text        NOT NULL CHECK (length(btrim(title)) > 0),
    notes         text        NOT NULL DEFAULT '',
    category_id   uuid        REFERENCES category (id) ON DELETE SET NULL,
    -- A plan item runs from start_date to due_date; a task only has a due date.
    start_date    date,
    due_date      date,
    status        text        NOT NULL CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'DROPPED')),
    completed_at  timestamptz,
    -- AUTO: priority is worked out by the rule. MANUAL: priority is what a person chose.
    priority_mode text        NOT NULL CHECK (priority_mode IN ('AUTO', 'MANUAL')),
    priority      text        NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL', 'LOW')),
    -- The plan item a task belongs to.
    parent_id     uuid        REFERENCES entry (id) ON DELETE SET NULL,
    -- Counts the saves, so that a save based on an outdated copy can be refused.
    version       integer     NOT NULL DEFAULT 0,
    created_by    uuid        REFERENCES wedding_member (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT entry_task_has_no_start CHECK (kind = 'PLAN_ITEM' OR start_date IS NULL),
    CONSTRAINT entry_plan_item_has_no_parent CHECK (kind = 'TASK' OR parent_id IS NULL),
    CONSTRAINT entry_start_needs_an_end CHECK (start_date IS NULL OR due_date IS NOT NULL),
    CONSTRAINT entry_start_not_after_end CHECK (start_date IS NULL OR start_date <= due_date),
    CONSTRAINT entry_done_has_a_time CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);

CREATE INDEX entry_wedding ON entry (wedding_id);
CREATE INDEX entry_parent ON entry (parent_id);

-- Who does it: no row means nobody yet, two rows mean both.
CREATE TABLE entry_assignee (
    entry_id  uuid NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    member_id uuid NOT NULL REFERENCES wedding_member (id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, member_id)
);
