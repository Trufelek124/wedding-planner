package io.github.trufelek124.weddingplanner.suggestions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How what reads the mail hands in what it proposes. There is no HTTP call that does this: a suggestion is
 * somebody else's words, and it comes into being only inside the server.
 */
public interface Suggestions {

    /**
     * Keeps the proposal as one that waits for an owner, or, when the import is sure enough (S05, D-008), adds it
     * at once. Only a task or a step of the plan can be added so, never anything that names an amount of money and
     * never a vendor; only when its confidence reaches the wedding's threshold and adding by itself is not switched
     * off; and only as the planner would take it as it is, in the name of the owner whose inbox it came from. A
     * proposal the planner would refuse waits, with nothing made. Handing in the same source key again keeps one
     * suggestion and answers with it, changing nothing.
     *
     * @return the id of the suggestion
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with BAD_REQUEST and a message per field when
     *     the proposal breaks a rule: no title, a text too long, a confidence outside 0 to 100, an inbox that is not
     *     a member of the wedding, or a link that is not an https address at mail.google.com
     */
    UUID handIn(Proposal proposal);

    /**
     * What a suggestion was handed in as: its key, and what is needed to recognise it again (its kind, its title, its first
     * day, the day it is due and its amount). It holds a part of someone's mail and prints nothing.
     *
     * @param startDate a plan item's first day, or null
     * @param dueDate the day it is due or ends, or null
     * @param amount in the smallest unit of the wedding's currency, or null
     */
    record Proposed(String sourceKey, Proposal.Kind kind, String title, LocalDate startDate, LocalDate dueDate, Long amount) {

        @Override
        public String toString() {
            return "Proposed[]";
        }
    }

    /**
     * What was handed in under keys that begin with this, whatever became of it since (waiting, accepted, rejected),
     * the newest first, at most {@code most}. The beginning is compared as it is, character by character: nothing in
     * it is a pattern.
     *
     * @throws IllegalArgumentException when the beginning is blank or {@code most} is not positive
     */
    List<Proposed> proposedUnder(UUID weddingId, String keyBeginning, int most);

    /**
     * What was handed in for this wedding at or after this moment, from whichever mail and whatever became of it since
     * (waiting, added by itself, accepted, rejected), the newest first, at most {@code most}.
     *
     * @throws IllegalArgumentException when {@code most} is not positive
     */
    List<Proposed> proposedSince(UUID weddingId, Instant since, int most);

    /** How many suggestions from this member's inbox came into being at or after this moment. */
    int madeSince(UUID weddingId, UUID inboxMemberId, Instant since);
}
