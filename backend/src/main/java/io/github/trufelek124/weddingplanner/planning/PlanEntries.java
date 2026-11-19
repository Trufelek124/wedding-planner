package io.github.trufelek124.weddingplanner.planning;

import io.github.trufelek124.weddingplanner.identity.UserId;
import java.util.UUID;

/**
 * How another module adds an entry on a person's behalf: through the very code that adds one by hand, with the
 * same checks and the same messages next to the same fields.
 */
public interface PlanEntries {

    /**
     * Adds the entry to the caller's wedding, as not started and with the priority worked out.
     *
     * @return the id of the entry that was made
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with BAD_REQUEST and a message per field when
     *     what would be made is not valid; nothing is made then
     */
    UUID addEntry(UserId user, EntryToAdd entry);
}
