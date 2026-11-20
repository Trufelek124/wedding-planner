package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Earlier;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Fetch;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Found;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Gone;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.NoText;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.SourceFailure;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.TooLarge;
import io.github.trufelek124.weddingplanner.mail.internal.MessageRepository.Waiting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What the mail module offers the module that reads (S04). A message is read from the mailbox on every call and
 * nothing of it is kept: not in the database, not in a log line (only the member and the kind of outcome are said),
 * not in an exception.
 */
@Service
class MailReadingService implements MailReading {

    private static final Logger log = LoggerFactory.getLogger(MailReadingService.class);

    private final MailSetup setup;
    private final MailSource source;
    private final MessageRepository messages;
    private final ConnectionRepository connections;
    private final Clock clock;

    MailReadingService(
            MailSetup setup, MailSource source, MessageRepository messages, ConnectionRepository connections, Clock clock) {
        this.setup = setup;
        this.source = source;
        this.messages = messages;
        this.connections = connections;
        this.clock = clock;
    }

    @Override
    public boolean isSetUp() {
        return setup.ready().isPresent();
    }

    @Override
    public List<MessageHandle> waiting(int most) {
        return waiting(most, List.of());
    }

    @Override
    public List<MessageHandle> waiting(int most, Collection<UUID> leavingOut) {
        if (most <= 0) {
            throw new IllegalArgumentException("most");
        }
        if (setup.ready().isEmpty()) {
            return List.of();
        }
        return messages.waiting(most, leavingOut).stream().map(MailReadingService::handle).toList();
    }

    /** The identifiers Gmail gives out: short, and nothing in them that could change what the address means. */
    private static final Pattern GMAIL_IDENTIFIER = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    @Override
    public Optional<String> linkTo(MessageHandle message) {
        if (setup.ready().isEmpty() || message.messageId() == null || !GMAIL_IDENTIFIER.matcher(message.messageId()).matches()) {
            return Optional.empty();
        }
        return connections.find(message.memberId())
                .map(connection -> "https://mail.google.com/mail/?authuser="
                        + URLEncoder.encode(connection.address(), StandardCharsets.UTF_8) + "#all/" + message.messageId());
    }

    private static MessageHandle handle(Waiting waiting) {
        return new MessageHandle(waiting.weddingId(), waiting.memberId(), waiting.messageId(), waiting.threadId());
    }

    @Override
    public Content read(MessageHandle message) {
        try {
            Fetch fetched = source.letter(message.memberId(), message.messageId(), message.threadId());
            return switch (fetched) {
                case Found found -> letter(message.weddingId(), found);
                case Gone gone -> new Unavailable(Why.GONE);
                case NoText none -> new Unavailable(Why.NO_TEXT);
                case TooLarge large -> new Unavailable(Why.TOO_LARGE);
            };
        } catch (SourceFailure failure) {
            return new Unavailable(switch (failure.kind()) {
                case NOT_CONNECTED -> Why.NOT_CONNECTED;
                case NEEDS_RECONNECTING -> Why.NEEDS_RECONNECTING;
                case REFUSED_FOR_NOW -> Why.REFUSED_FOR_NOW;
                case INTERRUPTED, MARKER_TOO_OLD -> Why.INTERRUPTED;
            });
        } catch (RuntimeException unexpected) {
            // What the exception carries may be somebody's words: only its kind is said.
            log.warn("A message of member {} could not be read unexpectedly ({})", message.memberId(), unexpected.getClass().getSimpleName());
            return new Unavailable(Why.INTERRUPTED);
        }
    }

    private static Letter letter(UUID weddingId, Found found) {
        MailSource.Letter letter = found.letter();
        return new Letter(
                person(letter.from()),
                letter.subject(),
                letter.arrived(),
                text(letter.text()),
                letter.earlier().stream().map(MailReadingService::earlier).toList(),
                letterKey(weddingId, letter.identifier()),
                letter.documents().stream().map(pdf -> new Document(pdf.name(), pdf.size(), pdf.reference())).toList());
    }

    private static EarlierMessage earlier(Earlier earlier) {
        return new EarlierMessage(person(earlier.from()), earlier.arrived(), text(earlier.text()));
    }

    private static Person person(MailSource.Person person) {
        return new Person(person.name(), person.address());
    }

    private static Text text(MailSource.Text text) {
        return new Text(text.text(), text.cut());
    }

    // ---------------------------------------------------------------- documents

    @Override
    public Fetched document(MessageHandle message, Document document) {
        if (document.size() > MOST_DOCUMENT_BYTES) {
            return new NotFetched(DocumentWhy.TOO_LARGE);
        }
        if (setup.ready().isEmpty()) {
            return new NotFetched(DocumentWhy.NOT_CONNECTED);
        }
        try {
            return switch (source.document(message.memberId(), message.messageId(), document.reference())) {
                case MailSource.Downloaded downloaded -> new Pdf(downloaded.bytes());
                case MailSource.DocumentGone gone -> new NotFetched(DocumentWhy.GONE);
                case MailSource.DocumentTooLarge large -> new NotFetched(DocumentWhy.TOO_LARGE);
                case MailSource.NotAPdf notAPdf -> new NotFetched(DocumentWhy.NOT_A_DOCUMENT);
            };
        } catch (SourceFailure failure) {
            return new NotFetched(switch (failure.kind()) {
                case NOT_CONNECTED -> DocumentWhy.NOT_CONNECTED;
                case NEEDS_RECONNECTING -> DocumentWhy.NEEDS_RECONNECTING;
                case REFUSED_FOR_NOW -> DocumentWhy.REFUSED_FOR_NOW;
                case INTERRUPTED, MARKER_TOO_OLD -> DocumentWhy.INTERRUPTED;
            });
        } catch (RuntimeException unexpected) {
            // Only the kind: what the exception carries may be a document's.
            log.warn("A document of a message of member {} could not be fetched unexpectedly ({})", message.memberId(),
                    unexpected.getClass().getSimpleName());
            return new NotFetched(DocumentWhy.INTERRUPTED);
        }
    }

    @Override
    public boolean beginDocuments(MessageHandle message) {
        return messages.beginDocuments(message.weddingId(), message.memberId(), message.messageId());
    }

    @Override
    public List<MessageHandle> waitingForDocuments(int most, Collection<UUID> leavingOut) {
        if (most <= 0) {
            throw new IllegalArgumentException("most");
        }
        if (setup.ready().isEmpty()) {
            return List.of();
        }
        return messages.waitingForDocuments(most, leavingOut).stream().map(MailReadingService::handle).toList();
    }

    @Override
    public boolean endDocuments(MessageHandle message) {
        return messages.endDocuments(message.weddingId(), message.memberId(), message.messageId());
    }

    @Override
    public boolean documentsOfACopyWereLookedAt(MessageHandle message, String letterKey) {
        if (letterKey == null || letterKey.isEmpty()) {
            return false;
        }
        return messages.documentsOfACopyWereLookedAt(message.weddingId(), message.memberId(), message.messageId(), letterKey);
    }

    @Override
    public boolean documentsDone(MessageHandle message, int moreProposals, String letterKey) {
        if (moreProposals < 0) {
            throw new IllegalArgumentException("moreProposals");
        }
        if (letterKey == null || (!letterKey.isEmpty() && !LETTER_KEY.matcher(letterKey).matches())) {
            throw new IllegalArgumentException("letterKey");
        }
        return messages.documentsDone(message.weddingId(), message.memberId(), message.messageId(), moreProposals,
                letterKey.isEmpty() ? null : letterKey);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public Keys keysOf(MessageHandle message) {
        return new Keys(key("m1", message.memberId(), message.messageId()), key("t1", message.memberId(), message.threadId()));
    }

    /**
     * A hash of what makes the message (or the thread) this one: the member's mailbox and Google's identifier. The
     * same for ever, different for another, and nothing in it a person could read or take an identifier from.
     */
    private static String key(String kind, UUID member, String identifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("wedding-planner/mail/" + kind + "\0" + member + "\0" + identifier).getBytes(StandardCharsets.UTF_8));
            return kind + "-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * What a letter is known by: a hash of the wedding and the identifier its sender gave it, so that the same letter in
     * both mailboxes has one key, another wedding's letter with the same identifier has another, and nothing in it is the
     * identifier. Empty when the message gave none.
     */
    private static String letterKey(UUID weddingId, String identifier) {
        return identifier.isEmpty() ? "" : key("l1", weddingId, identifier);
    }

    private static final Pattern LETTER_KEY = Pattern.compile("l1-[A-Za-z0-9_-]{43}");

    @Override
    public boolean copyWasRead(MessageHandle message, String letterKey) {
        if (letterKey == null || letterKey.isEmpty()) {
            return false;
        }
        return messages.copyWasRead(message.weddingId(), message.memberId(), message.messageId(), letterKey);
    }

    // ---------------------------------------------------------------- verdicts

    @Override
    public boolean mark(MessageHandle message, Verdict verdict, String letterKey, boolean documentsStillToLookAt) {
        if (letterKey == null || (!letterKey.isEmpty() && !LETTER_KEY.matcher(letterKey).matches())) {
            throw new IllegalArgumentException("letterKey");
        }
        if (verdict instanceof CopyOfOneRead && letterKey.isEmpty()) {
            throw new IllegalArgumentException("letterKey");
        }
        MessageRepository.Verdict stored = switch (verdict) {
            case NotAboutTheWedding none -> new MessageRepository.Verdict("NOT_WEDDING", null, null);
            case AboutTheWedding about -> new MessageRepository.Verdict("WEDDING", null, about.proposals());
            case CouldNotBeRead failed -> new MessageRepository.Verdict("UNREADABLE", failed.reason().name(), null);
            case CopyOfOneRead copy -> new MessageRepository.Verdict("COPY", null, null);
        };
        return messages.mark(message.weddingId(), message.memberId(), message.messageId(), stored,
                letterKey.isEmpty() ? null : letterKey, clock.instant(), documentsStillToLookAt && verdict instanceof AboutTheWedding);
    }

    @Override
    public Counts counts(UUID weddingId) {
        MessageRepository.Counts counts = messages.counts(weddingId);
        return new Counts(
                counts.waiting(), counts.read(), counts.aboutTheWedding(), counts.couldNotBeRead(), counts.proposals(), counts.copies());
    }
}
