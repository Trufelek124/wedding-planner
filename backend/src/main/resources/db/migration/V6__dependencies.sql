-- "This plan item can only start when that one is finished."

CREATE TABLE entry_dependency (
    entry_id      uuid NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    depends_on_id uuid NOT NULL REFERENCES entry (id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, depends_on_id),
    CONSTRAINT entry_dependency_not_on_itself CHECK (entry_id <> depends_on_id)
);

CREATE INDEX entry_dependency_depends_on ON entry_dependency (depends_on_id);
