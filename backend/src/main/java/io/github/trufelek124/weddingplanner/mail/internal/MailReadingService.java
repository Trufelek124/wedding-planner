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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
    private final Clock clock;

    MailReadingService(MailSetup setup, MailSource source, MessageRepository messages, Clock clock) {
        this.setup = setup;
        this.source = source;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    public List<MessageHandle> waiting(int most) {
        if (most <= 0) {
            throw new IllegalArgumentException("most");
        }
        if (setup.ready().isEmpty()) {
            return List.of();
        }
        return messages.waiting(most).stream().map(MailReadingService::handle).toList();
    }

    private static MessageHandle handle(Waiting waiting) {
        return new MessageHandle(waiting.weddingId(), waiting.memberId(), waiting.messageId(), waiting.threadId());
    }

    @Override
    public Content read(MessageHandle message) {
        try {
            Fetch fetched = source.letter(message.memberId(), message.messageId(), message.threadId());
            return switch (fetched) {
                case Found found -> letter(found);
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

    private static Letter letter(Found found) {
        MailSource.Letter letter = found.letter();
        return new Letter(
                person(letter.from()),
                letter.subject(),
                letter.arrived(),
                text(letter.text()),
                letter.earlier().stream().map(MailReadingService::earlier).toList());
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

    // ---------------------------------------------------------------- verdicts

    @Override
    public boolean mark(MessageHandle message, Verdict verdict) {
        MessageRepository.Verdict stored = switch (verdict) {
            case NotAboutTheWedding none -> new MessageRepository.Verdict("NOT_WEDDING", null, null);
            case AboutTheWedding about -> new MessageRepository.Verdict("WEDDING", null, about.proposals());
            case CouldNotBeRead failed -> new MessageRepository.Verdict("UNREADABLE", failed.reason().name(), null);
        };
        return messages.mark(message.weddingId(), message.memberId(), message.messageId(), stored, clock.instant());
    }

    @Override
    public Counts counts(UUID weddingId) {
        MessageRepository.Counts counts = messages.counts(weddingId);
        return new Counts(counts.waiting(), counts.read(), counts.aboutTheWedding(), counts.couldNotBeRead());
    }
}
