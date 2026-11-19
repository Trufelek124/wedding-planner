package io.github.trufelek124.weddingplanner.mail.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the sync needs from a mailbox, in the sync's own words: which messages are there, since when, page by page,
 * and where to go on from. Nothing here knows which provider stands behind it ({@link GmailSource} is the first;
 * another can take its place without the sync noticing). A mailbox is named by the owner it belongs to.
 *
 * <p>This is deliberately only "which messages": identifiers, no content. What will read a message (S03 section 7,
 * "give me this message") is the next story's and is added here when it is built, not before.
 */
interface MailSource {

    /** A message that is in the mailbox, and the thread it belongs to. Identifiers only; they are never printed. */
    record Noted(String messageId, String threadId) {

        @Override
        public String toString() {
            return "Noted[]";
        }
    }

    /**
     * One page of what was found.
     *
     * @param messages received messages only: what the owner sent, drafts, spam, the bin and chats are left out
     * @param continuation what to ask for the next page with; null on the last page
     * @param marker only from {@link #addedSince}, and only meant on the last page: where the mailbox stands, to go on
     *     from next time. Null where the source has nothing newer to say
     */
    record Page(List<Noted> messages, String continuation, String marker) {

        @Override
        public String toString() {
            return "Page[" + messages.size() + " messages]";
        }
    }

    /** Why the source could not give an answer. Nothing but the kind: never what was said. */
    enum Kind {
        /** This owner has no connection (any more): nothing to look at. */
        NOT_CONNECTED,
        /** The permission is gone. The connection says so; the sync leaves it alone until it is made again. */
        NEEDS_RECONNECTING,
        /** The marker has grown too old: the source no longer knows what happened since. Look by date instead. */
        MARKER_TOO_OLD,
        /** The provider refused (too many requests) or failed on its own: leave the mailbox for a growing time. */
        REFUSED_FOR_NOW,
        /** Cut off, too slow, not understood: nothing is wrong with the mailbox; the look is simply done again. */
        INTERRUPTED
    }

    final class SourceFailure extends Exception {

        private final Kind kind;

        SourceFailure(Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }

        Kind kind() {
            return kind;
        }
    }

    /**
     * Where the mailbox stands now. Asked <em>before</em> a first look, so that what arrives while the look is under
     * way is found by the next one.
     */
    String standingNow(UUID owner) throws SourceFailure;

    /** The received messages that arrived at or after {@code from}, page by page ({@code continuation} null for the first). */
    Page receivedSince(UUID owner, Instant from, String continuation) throws SourceFailure;

    /**
     * The received messages that were added to the mailbox since {@code marker}, page by page. Throws
     * {@link Kind#MARKER_TOO_OLD} when the source no longer knows that far back.
     */
    Page addedSince(UUID owner, String marker, String continuation) throws SourceFailure;
}
