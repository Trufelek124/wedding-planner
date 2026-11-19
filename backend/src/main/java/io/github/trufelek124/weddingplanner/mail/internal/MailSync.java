package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.ConnectionRepository.Connection;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Kind;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Page;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.SourceFailure;
import io.github.trufelek124.weddingplanner.mail.internal.MessageRepository.Completion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps up with the connected mailboxes (S03 section 5): notes which received messages are there, and nothing of them
 * but their identifiers. Every few minutes the server looks at each mailbox in turn.
 *
 * <ul>
 *   <li>The first look notes the last 90 days; a later one asks what was added since the marker; a marker that has
 *       grown too old is replaced by a look by date from the day of the last complete look, less one.
 *   <li>A look counts only when it is complete: what it finds is stored as it comes (storing twice is harmless), but
 *       the marker and the time of the last look move in the transaction that stores the last of it. A look that is
 *       cut off leaves them where they were and is simply done again.
 *   <li>When Google refuses or fails, the mailbox is left for a time that grows with every refusal in a row; the
 *       permission being gone ends the sync of that mailbox until it is connected again.
 * </ul>
 *
 * <p>The log says that a look happened, for which member, how many messages it noted and how long it took: never an
 * address, a token, a marker or an identifier of a message.
 */
@Component
@EnableScheduling
class MailSync {

    private static final Logger log = LoggerFactory.getLogger(MailSync.class);

    /** How far back the first look goes. */
    static final Duration FIRST_LOOK = Duration.ofDays(90);

    private final MailSetup setup;
    private final ConnectionRepository connections;
    private final MessageRepository messages;
    private final MailSource source;
    private final Clock clock;
    private final boolean automatic;
    private final AtomicBoolean running = new AtomicBoolean();

    MailSync(
            MailSetup setup,
            ConnectionRepository connections,
            MessageRepository messages,
            MailSource source,
            Clock clock,
            @Value("${app.mail.sync-automatic}") boolean automatic) {
        this.setup = setup;
        this.connections = connections;
        this.messages = messages;
        this.source = source;
        this.clock = clock;
        this.automatic = automatic;
    }

    /** The server's own clock for the sync: every {@code app.mail.sync-interval}, once the previous look has ended. */
    @Scheduled(fixedDelayString = "${app.mail.sync-interval}", initialDelayString = "${app.mail.sync-interval}")
    void poll() {
        if (automatic) {
            lookAtAll();
        }
    }

    /**
     * Looks at every connected mailbox that is not put off, one after the other.
     *
     * @return how many mailboxes were looked at; 0 also when the import is not set up or another look is under way
     */
    int lookAtAll() {
        if (setup.ready().isEmpty() || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int looked = 0;
            for (Connection connection : connections.dueForLook(clock.instant())) {
                look(connection);
                looked++;
            }
            return looked;
        } catch (RuntimeException unexpected) {
            log.warn("The mailbox sync stopped unexpectedly ({})", unexpected.getClass().getSimpleName());
            return 0;
        } finally {
            running.set(false);
        }
    }

    private void look(Connection connection) {
        long started = System.nanoTime();
        try {
            Optional<Integer> noted = lookAt(connection);
            if (noted.isPresent()) {
                log.info("Looked at the mailbox of member {}: {} new messages noted, {} ms",
                        connection.memberId(), noted.get(), Duration.ofNanos(System.nanoTime() - started).toMillis());
            } else {
                log.info("The mailbox of member {} was connected anew during the look; it is looked at again later", connection.memberId());
            }
        } catch (SourceFailure failure) {
            ended(connection, failure.kind(), Duration.ofNanos(System.nanoTime() - started).toMillis());
        } catch (RuntimeException unexpected) {
            // One mailbox's trouble does not stop the other. What the exception carries may be personal: only its kind is said.
            log.warn("The look at the mailbox of member {} failed unexpectedly ({})", connection.memberId(), unexpected.getClass().getSimpleName());
        }
    }

    /** The look itself. Empty when the connection was replaced or removed while it ran: nothing of it counts. */
    private Optional<Integer> lookAt(Connection connection) throws SourceFailure {
        Instant now = clock.instant();
        if (connection.syncMarker() == null) {
            return byDate(connection, now.minus(FIRST_LOOK));
        }
        try {
            return since(connection);
        } catch (SourceFailure failure) {
            if (failure.kind() != Kind.MARKER_TOO_OLD) {
                throw failure;
            }
        }
        log.info("The marker of member {} has grown too old: looking through the days since the last complete look", connection.memberId());
        Instant lastComplete = connection.lastLookAt();
        Instant from = lastComplete == null
                ? now.minus(FIRST_LOOK)
                : lastComplete.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return byDate(connection, from);
    }

    /** The marker is taken first: what arrives while the pages are read is then found by the next look. */
    private Optional<Integer> byDate(Connection connection, Instant from) throws SourceFailure {
        String standing = source.standingNow(connection.memberId());
        int added = 0;
        String continuation = null;
        while (true) {
            Page page = source.receivedSince(connection.memberId(), from, continuation);
            boolean last = page.continuation() == null;
            Optional<Integer> stored = store(connection, page, last ? new Completion(standing, clock.instant()) : null);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            added += stored.get();
            if (last) {
                return Optional.of(added);
            }
            continuation = onward(continuation, page);
        }
    }

    private Optional<Integer> since(Connection connection) throws SourceFailure {
        int added = 0;
        String continuation = null;
        while (true) {
            Page page = source.addedSince(connection.memberId(), connection.syncMarker(), continuation);
            boolean last = page.continuation() == null;
            Completion completion = last
                    ? new Completion(page.marker() != null ? page.marker() : connection.syncMarker(), clock.instant())
                    : null;
            Optional<Integer> stored = store(connection, page, completion);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            added += stored.get();
            if (last) {
                return Optional.of(added);
            }
            continuation = onward(continuation, page);
        }
    }

    private Optional<Integer> store(Connection connection, Page page, Completion completion) {
        return messages.note(connection.memberId(), connection.connectedAt(), page.messages(), clock.instant(), completion);
    }

    /** A source that answers with the page it was just asked for would keep a look going for ever. */
    private static String onward(String asked, Page page) throws SourceFailure {
        if (Objects.equals(asked, page.continuation())) {
            throw new SourceFailure(Kind.INTERRUPTED);
        }
        return page.continuation();
    }

    private void ended(Connection connection, Kind kind, long millis) {
        switch (kind) {
            case NEEDS_RECONNECTING -> log.info("The mailbox of member {} has to be connected again; it is left alone", connection.memberId());
            case NOT_CONNECTED -> log.info("The mailbox of member {} is no longer connected", connection.memberId());
            case REFUSED_FOR_NOW -> {
                Optional<Instant> until = connections.putOff(connection.memberId(), connection.connectedAt(), clock.instant());
                log.info("Google refused or failed for the mailbox of member {} after {} ms; it is put off until {}",
                        connection.memberId(), millis, until.map(Instant::toString).orElse("(the connection is gone)"));
            }
            case INTERRUPTED, MARKER_TOO_OLD ->
                    log.info("The look at the mailbox of member {} was cut off after {} ms; it is done again next time", connection.memberId(), millis);
        }
    }
}
