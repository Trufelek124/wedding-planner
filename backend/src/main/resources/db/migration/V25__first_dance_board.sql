-- The list of boards a wedding is offered once gained "First dance" at its end (S09). A wedding that was already
-- offered its list gets that board here, once: at the end of its list, unless it has a board of that name already
-- (compared without regard to capital letters and to spaces around it). A wedding that was never offered its boards
-- gets nothing: it is given the whole list, First dance included, when it is first offered.
-- This does not go against "what was removed does not come back": it is one board that did not exist before, added
-- once by this migration; a wedding that removes it afterwards does not get it again, because this runs only once.
INSERT INTO board (id, wedding_id, name, position)
SELECT gen_random_uuid(),
       s.wedding_id,
       'First dance',
       (SELECT COALESCE(max(b.position), 0) + 1 FROM board b WHERE b.wedding_id = s.wedding_id)
FROM inspirations_setup s
WHERE NOT EXISTS (SELECT 1 FROM board b WHERE b.wedding_id = s.wedding_id AND lower(btrim(b.name)) = 'first dance');
