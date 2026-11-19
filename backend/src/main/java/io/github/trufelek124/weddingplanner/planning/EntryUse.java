package io.github.trufelek124.weddingplanner.planning;

import java.util.UUID;

/**
 * What hangs on an entry. A module whose records can point to a plan item or a task implements this, so that the
 * planning module can tell an entry nobody has built on from one somebody has, without knowing those modules.
 */
public interface EntryUse {

    /** Whether anything of this module points to the entry. */
    boolean pointsTo(UUID weddingId, UUID entryId);
}
