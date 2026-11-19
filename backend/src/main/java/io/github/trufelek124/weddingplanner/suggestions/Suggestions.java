package io.github.trufelek124.weddingplanner.suggestions;

import java.util.UUID;

/**
 * How what reads the mail hands in what it proposes. There is no HTTP call that does this: a suggestion is
 * somebody else's words, and it comes into being only inside the server.
 */
public interface Suggestions {

    /**
     * Keeps the proposal as one that waits for an owner. Handing in the same source key again keeps one suggestion
     * and answers with it, changing nothing.
     *
     * @return the id of the suggestion
     * @throws io.github.trufelek124.weddingplanner.api.ApiException with BAD_REQUEST and a message per field when
     *     the proposal breaks a rule: no title, a text too long, a confidence outside 0 to 100, an inbox that is not
     *     a member of the wedding, or a link that is not an https address at mail.google.com
     */
    UUID handIn(Proposal proposal);
}
