-- Every idea has a place on the one canvas of the wedding (S08): the upper left corner of its card, in the canvas's own
-- units, and the moment it was put there. A move is not a change of the idea, so none of this touches version or updated_at.
ALTER TABLE inspiration
    ADD COLUMN x         integer,
    ADD COLUMN y         integer,
    ADD COLUMN placed_at timestamptz;

-- The ideas that exist are laid out once, per wedding, oldest first: rows of six, 220 units apart, 260 between rows,
-- from 0, 0. They were "put there" when they were saved.
UPDATE inspiration i
SET x = (n.position % 6) * 220,
    y = (n.position / 6) * 260,
    placed_at = i.created_at
FROM (SELECT id, row_number() OVER (PARTITION BY wedding_id ORDER BY created_at, id) - 1 AS position
      FROM inspiration) n
WHERE n.id = i.id;

ALTER TABLE inspiration
    ALTER COLUMN x SET NOT NULL,
    ALTER COLUMN y SET NOT NULL,
    ALTER COLUMN placed_at SET NOT NULL,
    ADD CONSTRAINT inspiration_x_within_canvas CHECK (x BETWEEN -100000 AND 100000),
    ADD CONSTRAINT inspiration_y_within_canvas CHECK (y BETWEEN -100000 AND 100000);
