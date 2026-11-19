package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.ConnectionRepository.Connection;
import io.github.trufelek124.weddingplanner.mail.internal.ConnectionRepository.LookBack;
import io.github.trufelek124.weddingplanner.mail.internal.MailTypes.ConnectionState;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Kind;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Page;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.SourceFailure;
import io.github.trufelek124.weddingplanner.mail.internal.MessageRepository.Completion;
import io.github.trufelek124.weddingplanner.mail.internal.MessageRepository.Look;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps up with the connected mailboxes (S03 section 5): notes which received messages are there, and nothing of them
 * but their identifiers. Every few minutes the server looks at each mailbox in turn.
 *
 * <ul>
 *   <li>The first look notes where the mailbox stands and nothing of what is in it (D-009); a later one asks what was
 *       added since the marker; a marker that has grown too old is replaced by a look by date from the day of the last
 *       complete look, less one.
 *   <li>After a mailbox's ordinary look, a look back that its owner asked for and that is not done yet is carried out:
 *       the received messages since the moment fixed when it was asked, newest first, stored as they come and done
 *       only with the last page. A look back that was cut off is done again from its beginning.
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

    private final MailSetup setup;
    private final ConnectionRepository connections;
    private final MessageRepository messages;
    private final MailSource source;
    private final Clock clock;
    private final boolean automatic;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    /** Somebody asked for a round while one was running: it is run again once that one has ended. */
    private final AtomicBoolean wanted = new AtomicBoolean();

    MailSync(
            MailSetup setup,
            ConnectionRepository connections,
            MessageRepository messages,
            MailSource source,
            Clock clock,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Value("${app.mail.sync-automatic}") boolean automatic) {
        this.setup = setup;
        this.connections = connections;
        this.messages = messages;
        this.source = source;
        this.clock = clock;
        this.executor = executor;
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
     * A look back was just asked for: a round starts in the background, once what was asked is committed, so that it
     * begins within moments and not at the next tick. A round that is already running is not started twice: it goes
     * round once more when it has ended. Does nothing where the sync does not run by itself.
     */
    void wake() {
        if (!automatic) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakeNow();
                }
            });
        } else {
            wakeNow();
        }
    }

    private void wakeNow() {
        wanted.set(true);
        executor.execute(this::lookAtAll);
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
        int looked = 0;
        try {
            do {
                wanted.set(false);
                for (Connection connection : connections.dueForLook(clock.instant())) {
                    look(connection);
                    looked++;
                }
            } while (wanted.get());
        } catch (RuntimeException unexpected) {
            log.warn("The mailbox sync stopped unexpectedly ({})", unexpected.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
        // Asked for in the moment between the last check and letting go.
        return wanted.get() ? looked + lookAtAll() : looked;
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
                return;
            }
            lookBack(connection.memberId(), connection.connectedAt());
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
            return firstLook(connection);
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
                ? now
                : lastComplete.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return byDate(connection, from);
    }

    /** Where the mailbox stands is noted, and nothing of what is in it: that is only looked at when its owner asks. */
    private Optional<Integer> firstLook(Connection connection) throws SourceFailure {
        String standing = source.standingNow(connection.memberId());
        return store(connection, messages.beginLook(true), new Page(java.util.List.of(), null, null), new Completion(standing, clock.instant()));
    }

    /** The marker is taken first: what arrives while the pages are read is then found by the next look. */
    private Optional<Integer> byDate(Connection connection, Instant from) throws SourceFailure {
        String standing = source.standingNow(connection.memberId());
        // A look by date lists the newest message first.
        Look look = messages.beginLook(true);
        int added = 0;
        String continuation = null;
        while (true) {
            Page page = source.receivedSince(connection.memberId(), from, continuation);
            boolean last = page.continuation() == null;
            Optional<Integer> stored = store(connection, look, page, last ? new Completion(standing, clock.instant()) : null);
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
        // The history lists what was added in the order it happened: the newest message last.
        Look look = messages.beginLook(false);
        int added = 0;
        String continuation = null;
        while (true) {
            Page page = source.addedSince(connection.memberId(), connection.syncMarker(), continuation);
            boolean last = page.continuation() == null;
            Completion completion = last
                    ? new Completion(page.marker() != null ? page.marker() : connection.syncMarker(), clock.instant())
                    : null;
            Optional<Integer> stored = store(connection, look, page, completion);
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

    /**
     * Carries out the look back this mailbox's owner asked for, if there is one that is not done. It is read again here,
     * not taken from the round's list: it may have been asked for while the ordinary look was under way.
     */
    private void lookBack(UUID memberId, Instant connectedAt) throws SourceFailure {
        Optional<LookBack> asked = connections.find(memberId)
                .filter(c -> c.state() == ConnectionState.CONNECTED && c.connectedAt().equals(connectedAt))
                .map(Connection::lookBack)
                .filter(LookBack::underWay);
        if (asked.isEmpty()) {
            return;
        }
        LookBack lookBack = asked.get();
        long started = System.nanoTime();
        int found = 0;
        String continuation = null;
        while (true) {
            Page page = source.receivedSince(memberId, lookBack.from(), continuation);
            boolean last = page.continuation() == null;
            Optional<Integer> stored = messages.noteLookBack(memberId, connectedAt, lookBack.look(), page.messages(), clock.instant(), last);
            if (stored.isEmpty()) {
                log.info("The look back of member {} was replaced during the look; it is carried out again later", memberId);
                return;
            }
            found += stored.get();
            if (last) {
                log.info("The look back of member {} over {} days is done: {} messages noted in this run, {} ms",
                        memberId, lookBack.days(), found, Duration.ofNanos(System.nanoTime() - started).toMillis());
                return;
            }
            continuation = onward(continuation, page);
        }
    }

    private Optional<Integer> store(Connection connection, Look look, Page page, Completion completion) {
        return messages.note(connection.memberId(), connection.connectedAt(), look, page.messages(), clock.instant(), completion);
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
