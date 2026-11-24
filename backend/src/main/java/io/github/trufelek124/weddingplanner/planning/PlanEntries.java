package io.github.trufelek124.weddingplanner.planning;

import io.github.trufelek124.weddingplanner.identity.UserId;
import java.util.UUID;

/**
 * How another module adds an entry on a person's behalf: through the very code that adds one by hand, with the
 * same checks and the same messages next to the same fields. And how it takes back an entry that it added and that
 * nobody has touched since.
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

    /**
     * The same, in the name of a member of the wedding, for a part of the server that works without a signed-in
     * person. A member who has never signed in is a member all the same: the entry is theirs as any other.
     *
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with BAD_REQUEST and a message per field when
     *     what would be made is not valid; nothing is made then; with NOT_FOUND when the member is not one of the
     *     wedding's
     */
    UUID addEntryFor(UUID weddingId, UUID memberId, EntryToAdd entry);

    /**
     * Removes an entry that was made for this suggestion, if it is still exactly as it was made: saved never since
     * (its version is the one it was made with, so nothing was edited, ticked off, converted or given to anybody),
     * not started, with no task on it, no dependency in either direction, and nothing of another module pointing to it.
     * Whatever else is true, nothing is removed.
     */
    Removal removeIfUntouched(UUID weddingId, UUID entryId, UUID suggestionId);

    /**
     * Whether the wedding has a payment of exactly this amount, due on exactly this day (null: no day, and an entry with no day
     * is the same day), as a task of any status, a dropped one too (D-014). The amount is in the smallest unit of the currency.
     */
    boolean hasPayment(UUID weddingId, long amount, java.time.LocalDate dueDate);

    enum Removal {
        /** It was untouched, and is gone now. */
        REMOVED,
        /** Somebody has made it theirs: it stays. */
        CHANGED,
        /** There is no such entry any more (or it was not made for this suggestion). */
        GONE
    }
}
