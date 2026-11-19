package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.ConnectionRepository.Connection;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Fresh;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.GoogleFailure;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Refreshed;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Withdrawn;
import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Ready;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the mailbox sync calls to read a mailbox: {@link #accessTokenFor}. It asks Google for an access token with the
 * owner's refresh token when one is needed, keeps it in memory only, and only for as long as Google says it lives.
 * When Google no longer lets the token in, or the token cannot be read with the key the server has, the connection is
 * marked as needing to be connected again and the sync leaves it alone.
 */
@Component
class GmailAccess {

    private static final Logger log = LoggerFactory.getLogger(GmailAccess.class);

    /** A token is let go this long before Google says it ends, so that none runs out while it is being used. */
    static final Duration MARGIN = Duration.ofSeconds(60);

    /** What the sync gets. */
    sealed interface Access permits Granted, NotConnected, NeedsReconnecting, Unavailable {}

    /** A token to read with. It is never printed. */
    record Granted(String accessToken) implements Access {

        @Override
        public String toString() {
            return "Granted[]";
        }
    }

    /** This person has not connected a mailbox (or the import is off): nothing to look at. */
    record NotConnected() implements Access {}

    /** Google no longer lets the server in. The connection says so; the person has to connect again. */
    record NeedsReconnecting() implements Access {}

    /** Google could not be asked, or did not answer well. Nothing is wrong with the connection: try again later. */
    record Unavailable() implements Access {}

    private record Cached(String accessToken, Instant until) {

        @Override
        public String toString() {
            return "Cached[]";
        }
    }

    private final MailSetup setup;
    private final ConnectionRepository connections;
    private final GoogleClient google;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Cached> kept = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    GmailAccess(MailSetup setup, ConnectionRepository connections, GoogleClient google, Clock clock) {
        this.setup = setup;
        this.connections = connections;
        this.google = google;
        this.clock = clock;
    }

    /** An access token for this owner's mailbox, from memory while it lives and from Google when it does not. */
    Access accessTokenFor(UUID memberId) {
        Optional<Ready> ready = setup.ready();
        if (ready.isEmpty()) {
            return new NotConnected();
        }
        synchronized (locks.computeIfAbsent(memberId, id -> new Object())) {
            Cached cached = kept.get(memberId);
            if (cached != null && clock.instant().isBefore(cached.until())) {
                // The connection may have gone since: look, which is cheap and keeps a disconnected mailbox shut.
                Optional<Connection> still = connections.find(memberId);
                if (still.isPresent() && still.get().tokenSealed() != null) {
                    return new Granted(cached.accessToken());
                }
            }
            kept.remove(memberId);
            Optional<Connection> found = connections.find(memberId);
            if (found.isEmpty()) {
                return new NotConnected();
            }
            Connection connection = found.get();
            if (connection.tokenSealed() == null) {
                return new NeedsReconnecting();
            }
            Optional<String> refreshToken = ready.get().vault().open(connection.tokenSealed(), TokenVault.memberAad(memberId));
            if (refreshToken.isEmpty()) {
                // The key was changed (or the row was tampered with): the token is lost, the person connects again.
                needsReconnecting(connection);
                return new NeedsReconnecting();
            }
            try {
                Refreshed refreshed = google.refresh(refreshToken.get());
                return switch (refreshed) {
                    case Fresh fresh -> {
                        Instant until = clock.instant().plusSeconds(fresh.expiresInSeconds()).minus(MARGIN);
                        if (until.isAfter(clock.instant())) {
                            kept.put(memberId, new Cached(fresh.accessToken(), until));
                        }
                        yield new Granted(fresh.accessToken());
                    }
                    case Withdrawn withdrawn -> {
                        needsReconnecting(connection);
                        yield new NeedsReconnecting();
                    }
                    case GoogleClient.WrongScope wrong -> {
                        needsReconnecting(connection);
                        yield new NeedsReconnecting();
                    }
                };
            } catch (GoogleFailure failure) {
                log.info("An access token could not be had from Google ({})", failure.failure());
                return new Unavailable();
            }
        }
    }

    /** Forgets the access token held for this owner: the connection was replaced or removed. */
    void forget(UUID memberId) {
        kept.remove(memberId);
    }

    private void needsReconnecting(Connection connection) {
        if (connections.markNeedsReconnecting(connection.memberId(), connection.tokenSealed())) {
            log.info("The mailbox connection of member {} has to be made again", connection.memberId());
        }
        kept.remove(connection.memberId());
    }
}
