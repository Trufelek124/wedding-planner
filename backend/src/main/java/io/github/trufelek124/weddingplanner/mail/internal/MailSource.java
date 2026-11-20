package io.github.trufelek124.weddingplanner.mail.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the sync needs from a mailbox, in the sync's own words: which messages are there, since when, page by page,
 * and where to go on from. Nothing here knows which provider stands behind it ({@link GmailSource} is the first;
 * another can take its place without the sync noticing). A mailbox is named by the owner it belongs to.
 *
 * <p>It also gives one message with the thread it belongs to (S03 section 7, "give me this message"). That is
 * somebody else's words: what carries them hides them from {@code toString()}, and nothing here keeps or logs them.
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

    /** A person who wrote a message. The name may be empty (the address alone was given); both are somebody's own words. */
    record Person(String name, String address) {

        @Override
        public String toString() {
            return "Person[]";
        }
    }

    /**
     * What a message says, cut where it is too long.
     *
     * @param cut whether the text goes on beyond {@link #MOST_CHARACTERS}: what is here is the beginning
     */
    record Text(String text, boolean cut) {

        @Override
        public String toString() {
            return "Text[]";
        }
    }

    /** What one message's text is cut at, in characters. */
    int MOST_CHARACTERS = 100_000;

    /** A message of the thread that arrived before the one asked for. The text may be empty (an attachment alone). */
    record Earlier(Person from, Instant arrived, Text text) {

        @Override
        public String toString() {
            return "Earlier[]";
        }
    }

    /**
     * A PDF document that came with a message, as the letter lists it: what it is called and how large Gmail says it is
     * (0 when it does not say), and what is needed to fetch it later ({@code reference}: Gmail's identifier of the
     * attachment, which only {@link #document} understands). Nothing of what is inside. The name is somebody's own word: it is
     * never logged or printed.
     */
    record Pdf(String name, long size, String reference) {

        @Override
        public String toString() {
            return "Pdf[]";
        }
    }

    /**
     * A message with the thread it belongs to. {@code subject} may be empty. {@code earlier} are the messages of the
     * thread that arrived before this one, the oldest first; the owner's own replies are among them, drafts, spam and the bin are not.
     * {@code identifier} is what the sender gave the letter ({@code Message-ID}), without the spaces and the angle brackets
     * around it; empty when the message has none. It is only for working a key out: never kept, logged or printed.
     * {@code documents} are the PDF documents that came with the message itself (not with the earlier ones), in the order of
     * the mail; listing them fetches nothing.
     */
    record Letter(Person from, String subject, Instant arrived, Text text, List<Earlier> earlier, String identifier, List<Pdf> documents) {

        @Override
        public String toString() {
            return "Letter[]";
        }
    }

    /** What asking for a message came to, when the mailbox itself was in order. */
    sealed interface Fetch permits Found, Gone, NoText, TooLarge {}

    record Found(Letter letter) implements Fetch {

        @Override
        public String toString() {
            return "Found[]";
        }
    }

    /** The message is no longer in the mailbox: deleted, in the bin or marked as spam. */
    record Gone() implements Fetch {}

    /** The message has no text: no plain part and no HTML part, or only attachments. */
    record NoText() implements Fetch {}

    /** The thread is larger than anything the source is willing to fetch. */
    record TooLarge() implements Fetch {}

    /**
     * The message {@code messageId} of the thread {@code threadId}, with the earlier messages of the thread. Attachments
     * are never fetched or read.
     */
    Fetch letter(UUID owner, String messageId, String threadId) throws SourceFailure;

    /** What one document is cut at, in bytes: a larger one is never taken. */
    int MOST_DOCUMENT_BYTES = 10 * 1024 * 1024;

    /** What asking for a document came to, when the mailbox itself was in order. */
    sealed interface Download permits Downloaded, DocumentGone, DocumentTooLarge, NotAPdf {}

    /** The document's bytes, which begin like a PDF. Nothing of them is printed. */
    record Downloaded(byte[] bytes) implements Download {

        @Override
        public String toString() {
            return "Downloaded[]";
        }
    }

    /** Gmail no longer has the message or the attachment. */
    record DocumentGone() implements Download {}

    /** The document is larger than {@link #MOST_DOCUMENT_BYTES}. */
    record DocumentTooLarge() implements Download {}

    /** What came back does not begin like a PDF. */
    record NotAPdf() implements Download {}

    /**
     * One document of a message: Gmail's {@code users.messages.attachments.get}, and nothing else is asked. Only ever called
     * for what a {@link Letter} listed, and only when the reader asks for it.
     */
    Download document(UUID owner, String messageId, String reference) throws SourceFailure;

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
