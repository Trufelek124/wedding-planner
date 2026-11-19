package io.github.trufelek124.weddingplanner.mail;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the module that reads the mail (S04) needs from the mail module, in its own words and with nothing of Gmail
 * in them: which noted messages wait to be read, what one of them says, a key to name what is proposed from it, a
 * verdict to mark it with, and how many are where.
 *
 * <p>A message is somebody else's words. Every record here that holds them hides them from {@code toString()}, and
 * nothing of a message is kept or logged by the mail module: a message is fetched again each time it is asked for.
 * Attachments are never fetched or read.
 */
public interface MailReading {

    /**
     * Whether the e-mail import is set up on this server (S03). While it is not, nothing is waiting, nothing can be
     * read and nothing is asked of Google: the reader then has nothing to do and says so.
     */
    boolean isSetUp();

    /**
     * Which message, in whose mailbox and in which wedding. What the two identifiers mean is not the reader's business:
     * it hands the whole record back.
     */
    record MessageHandle(UUID weddingId, UUID memberId, String messageId, String threadId) {

        @Override
        public String toString() {
            return "MessageHandle[member=" + memberId + "]";
        }
    }

    /**
     * The messages that have no verdict yet, at most {@code most}, the <b>newest first</b> across the mailboxes of
     * every wedding. Only mailboxes that are connected: one that has to be connected again has nothing to hand out.
     * A message that was marked is never in the list again.
     *
     * @throws IllegalArgumentException when {@code most} is not positive
     */
    List<MessageHandle> waiting(int most);

    /**
     * As {@link #waiting(int)}, leaving out the mailboxes of these members: the reader has paused them, and their
     * messages must not take the places of the others'.
     *
     * @throws IllegalArgumentException when {@code most} is not positive
     */
    List<MessageHandle> waiting(int most, Collection<UUID> leavingOut);

    /**
     * The address that opens this message in Gmail, in the mailbox it is in:
     * {@code https://mail.google.com/mail/?authuser=<the mailbox's address>#all/<the message>}. Made by the server from
     * what it keeps, never from anything a message says. Empty when the member has no mailbox connected (any more),
     * the import is off, or the message's identifier is not one Gmail gives out.
     *
     * <p>It contains the mailbox's address: it is never logged, and it is for that mailbox's owner.
     */
    Optional<String> linkTo(MessageHandle message);

    /**
     * What the message says, or why it cannot be had. A message is read from the mailbox on every call; nothing of it
     * is kept. This does not mark it.
     */
    Content read(MessageHandle message);

    /** A person who wrote a message. The name may be empty. */
    record Person(String name, String address) {

        @Override
        public String toString() {
            return "Person[]";
        }
    }

    /**
     * What a message's text is: {@code cut} says that it goes on beyond what is here (one message's text is cut at
     * {@link #MOST_CHARACTERS} characters). It can be empty only for an earlier message that has no text.
     */
    record Text(String text, boolean cut) {

        @Override
        public String toString() {
            return "Text[]";
        }
    }

    /** What one message's text is cut at, in characters. How much of it leaves the server is the reader's decision. */
    int MOST_CHARACTERS = 100_000;

    /** A message of the thread that arrived before the one that was asked for. */
    record EarlierMessage(Person from, Instant arrived, Text text) {

        @Override
        public String toString() {
            return "EarlierMessage[]";
        }
    }

    /** The answer to {@link #read}: the message with its thread, or why not. */
    sealed interface Content permits Letter, Unavailable {}

    /**
     * A message with the thread it belongs to.
     *
     * @param subject may be empty
     * @param arrived when it arrived, as Gmail says
     * @param earlier the messages of the thread that arrived before it, <b>oldest first</b>: the owner's own replies
     *     are among them; drafts, spam and the bin are not. A message that came later is not here
     * @param key what the letter is known by across mailboxes: a one-way hash of the wedding and the identifier its sender
     *     gave it ({@code Message-ID}, without the spaces and angle brackets around it; a different case is another
     *     identifier), written like the other keys ({@code l1-...}); empty when the message has no such header or it is
     *     blank. It says nothing a person could read, and is at most 100 characters. The identifier itself is never kept or logged
     */
    record Letter(Person from, String subject, Instant arrived, Text text, List<EarlierMessage> earlier, String key) implements Content {

        @Override
        public String toString() {
            return "Letter[]";
        }
    }

    /** Why a message cannot be had. Not a failure of the reader: it decides what each comes to. */
    enum Why {
        /** Gmail no longer has the message: deleted, in the bin, spam, or its thread is gone. A verdict of {@link Reason#GONE}. */
        GONE,
        /** It has no text: no plain part and no HTML part, or only attachments. A verdict of {@link Reason#NO_TEXT}. */
        NO_TEXT,
        /** Its thread is larger than what is fetched. A verdict of {@link Reason#TOO_LARGE}. */
        TOO_LARGE,
        /** The owner's mailbox is not connected (any more), or the import is off. Leave the message as it is. */
        NOT_CONNECTED,
        /** The permission is gone: the owner has to connect again. Leave the message as it is. */
        NEEDS_RECONNECTING,
        /** Google refused or failed for now (too many requests, its own trouble). Try again later; leave the message as it is. */
        REFUSED_FOR_NOW,
        /** Cut off, too slow, not understood. Try again later; leave the message as it is. */
        INTERRUPTED
    }

    record Unavailable(Why why) implements Content {}

    /**
     * What the same message and the same thread are called in what is proposed from them: a stable key per message
     * and one per thread, for ever and across restarts and reconnecting the same mailbox. They say nothing a person
     * could read. Two messages have two keys; two messages of one thread have one thread key. Each is at most 100
     * characters and fit for S02's source key (a proposal's own number can be added to {@link #message}).
     */
    record Keys(String message, String thread) {

        @Override
        public String toString() {
            return "Keys[]";
        }
    }

    Keys keysOf(MessageHandle message);

    /**
     * Whether a copy of this letter was already read: another noted message of the same wedding, in either mailbox, that
     * has a verdict and the same {@link Letter#key}. A message is not its own copy. False for an empty key.
     */
    boolean copyWasRead(MessageHandle message, String letterKey);

    /** Why a message could not be read. Stored with the verdict. */
    enum Reason {
        GONE,
        NO_TEXT,
        TOO_LARGE,
        /** The model declined it. */
        DECLINED,
        /** The answer could not be used. */
        UNUSABLE_ANSWER,
        /** It made too many proposals. */
        TOO_MANY_PROPOSALS
    }

    /** What came of a message: it is not about the wedding, it is (and how many proposals it made), or it could not be read. */
    sealed interface Verdict permits NotAboutTheWedding, AboutTheWedding, CouldNotBeRead, CopyOfOneRead {}

    record NotAboutTheWedding() implements Verdict {}

    /**
     * The same letter had already been read through another message (S04 section 2): this one was sent nowhere and proposes
     * nothing. Needs the letter's key to be marked with ({@link #mark(MessageHandle, Verdict, String)}).
     */
    record CopyOfOneRead() implements Verdict {}

    /** @param proposals how many proposals it made, 0 or more */
    record AboutTheWedding(int proposals) implements Verdict {

        public AboutTheWedding {
            if (proposals < 0) {
                throw new IllegalArgumentException("proposals");
            }
        }
    }

    record CouldNotBeRead(Reason reason) implements Verdict {

        public CouldNotBeRead {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    /**
     * Gives the message its verdict: it is then read, and never handed out again. The first verdict stands: marking
     * a message that has one changes nothing. A message whose mailbox was disconnected meanwhile is gone with its
     * note: marking it is not an error.
     *
     * @return whether this call gave the verdict; false when it already had one or the message is not there
     */
    default boolean mark(MessageHandle message, Verdict verdict) {
        return mark(message, verdict, "");
    }

    /**
     * As {@link #mark(MessageHandle, Verdict)}, keeping the letter's key ({@link Letter#key}) with the verdict, so that a
     * later copy of the letter can be recognised. Give none (empty) where no letter was had: gone, no text, too large.
     *
     * @throws IllegalArgumentException when the key is not empty and not one of this module's keys, or when {@link CopyOfOneRead}
     *     is given with no key
     */
    boolean mark(MessageHandle message, Verdict verdict, String letterKey);

    /**
     * How many messages of this wedding's mailboxes are in each state. Counts every noted message, whether or not its
     * mailbox is connected at the moment.
     *
     * @param waiting without a verdict
     * @param read with a verdict, of any kind
     * @param aboutTheWedding of those read, the ones that concerned the wedding
     * @param couldNotBeRead of those read, the ones that could not be read
     * @param proposals the sum of the proposals of the messages that were about the wedding
     * @param copies of those read, the ones that were a copy of a letter read through another message: counted here and in
     *     neither {@code aboutTheWedding} nor {@code couldNotBeRead}
     */
    record Counts(int waiting, int read, int aboutTheWedding, int couldNotBeRead, int proposals, int copies) {}

    Counts counts(UUID weddingId);
}
