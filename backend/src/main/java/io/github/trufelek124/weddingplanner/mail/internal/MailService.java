package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.mail.internal.ConnectionRepository.Connection;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Failure;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Granted;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.GoogleFailure;
import io.github.trufelek124.weddingplanner.mail.internal.MailDtos.MailStateResponse;
import io.github.trufelek124.weddingplanner.mail.internal.MailDtos.MyConnection;
import io.github.trufelek124.weddingplanner.mail.internal.MailDtos.PartnersConnection;
import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Outcome;
import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Ready;
import io.github.trufelek124.weddingplanner.mail.internal.MailTypes.ConnectionState;
import io.github.trufelek124.weddingplanner.mail.internal.TicketRepository.Ticket;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Connecting and disconnecting an owner's Gmail, and saying where each connection stands. Nothing in a log line here
 * but ids and the kind of thing that went wrong: never a code, a state, a token, a cookie or an address.
 */
@Service
class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** What every random value here looks like: 32 random bytes, base64url, without padding. */
    private static final Pattern RANDOM_VALUE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern WITHOUT_BLANKS = Pattern.compile("[^\\p{Cntrl}\\s]{1,2048}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What starting a connection gives: where Google is, and what the browser must keep for the return. */
    record Started(String authorizationUrl, String cookieValue) {

        @Override
        public String toString() {
            return "Started[]";
        }
    }

    private final MailSetup setup;
    private final Weddings weddings;
    private final ConnectionRepository connections;
    private final TicketRepository tickets;
    private final GoogleClient google;
    private final GmailAccess access;
    private final MessageRepository messages;
    private final Clock clock;

    MailService(
            MailSetup setup,
            Weddings weddings,
            ConnectionRepository connections,
            TicketRepository tickets,
            GoogleClient google,
            GmailAccess access,
            MessageRepository messages,
            Clock clock) {
        this.setup = setup;
        this.weddings = weddings;
        this.connections = connections;
        this.tickets = tickets;
        this.google = google;
        this.access = access;
        this.messages = messages;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the state

    MailStateResponse state(UserId user) {
        if (setup.ready().isEmpty()) {
            return new MailStateResponse(false, notConnected(), new PartnersConnection(false, ConnectionState.NOT_CONNECTED, null));
        }
        Membership mine = weddings.membershipOf(user);
        Optional<Connection> yours = connections.find(mine.memberId());
        PartnersConnection partner = mine.memberIds().stream()
                .filter(id -> !id.equals(mine.memberId()))
                .findFirst()
                .map(id -> connections.find(id)
                        .map(connection -> new PartnersConnection(true, connection.state(), connection.lastLookAt()))
                        .orElse(new PartnersConnection(mine.joinedMemberIds().contains(id), ConnectionState.NOT_CONNECTED, null)))
                .orElse(new PartnersConnection(false, ConnectionState.NOT_CONNECTED, null));
        return new MailStateResponse(true, yours.map(this::view).orElse(notConnected()), partner);
    }

    private MyConnection view(Connection connection) {
        return new MyConnection(connection.state(), connection.address(), connection.connectedAt(), connection.lastLookAt(),
                messages.count(connection.memberId()));
    }

    private static MyConnection notConnected() {
        return new MyConnection(ConnectionState.NOT_CONNECTED, null, null, null, 0);
    }

    // ---------------------------------------------------------------- connecting

    Started start(UserId user) {
        Ready ready = setup.require();
        Membership mine = weddings.membershipOf(user);
        String state = randomValue();
        String cookie = randomValue();
        String verifier = randomValue();
        byte[] stateHash = sha256(state);
        Instant now = clock.instant();
        tickets.create(
                stateHash,
                mine.memberId(),
                sha256(cookie),
                ready.vault().seal(verifier, stateHash),
                now,
                now.plus(ConnectCookie.LIFETIME));
        String address = google.authorizationUrl(state, challengeOf(verifier));
        log.info("Member {} started connecting a mailbox", mine.memberId());
        return new Started(address, cookie);
    }

    /**
     * Where Google sent the browser back to. Whatever comes in, this answers with one of three outcomes and stores
     * something only for a good ticket and Google's word that the permission was given.
     *
     * @param state what came back as {@code state}, null if none or several
     * @param cookie the browser's cookie, empty if none
     * @param code Google's code, null if none or several
     * @param error Google's {@code error}, null if none
     */
    Outcome complete(String state, Optional<String> cookie, String code, String error) {
        try {
            return finish(state, cookie, code, error);
        } catch (RuntimeException unexpected) {
            // Never the exception itself: what it carries may be what came in.
            log.warn("A return to the mail connection failed unexpectedly ({})", unexpected.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    private Outcome finish(String state, Optional<String> cookie, String code, String error) {
        Optional<Ready> ready = setup.ready();
        if (ready.isEmpty()) {
            return turnedAway("the import is off");
        }
        if (state == null || !RANDOM_VALUE.matcher(state).matches() || cookie.isEmpty() || !RANDOM_VALUE.matcher(cookie.get()).matches()) {
            return turnedAway("no ticket in the right shape");
        }
        byte[] stateHash = sha256(state);
        Optional<Ticket> found = tickets.find(stateHash);
        if (found.isEmpty()) {
            return turnedAway("unknown ticket");
        }
        Ticket ticket = found.get();
        if (!MessageDigest.isEqual(ticket.cookieHash(), sha256(cookie.get()))) {
            return turnedAway("not the browser it was made for");
        }
        // One statement decides who gets the ticket: unused, and not run out.
        if (!tickets.take(stateHash, clock.instant())) {
            return turnedAway(ticket.used() ? "ticket used before" : "ticket run out");
        }
        UUID memberId = ticket.memberId();
        if (error != null) {
            log.info("Member {} did not allow the mailbox", memberId);
            return "access_denied".equals(error) ? Outcome.REFUSED : Outcome.FAILED;
        }
        if (code == null || !WITHOUT_BLANKS.matcher(code).matches()) {
            return turnedAway("no code");
        }
        Optional<String> verifier = ready.get().vault().open(ticket.verifierSealed(), stateHash);
        if (verifier.isEmpty()) {
            return turnedAway("ticket cannot be read");
        }
        return connect(ready.get(), memberId, code, verifier.get());
    }

    private Outcome connect(Ready ready, UUID memberId, String code, String verifier) {
        Granted granted;
        try {
            granted = google.exchange(code, verifier);
        } catch (GoogleFailure failure) {
            log.info("Member {}: Google did not give tokens for the code ({})", memberId, failure.failure());
            return Outcome.FAILED;
        }
        // Read-only and nothing else, and a token to come back with: otherwise nothing is kept.
        if (granted.refreshToken() == null || !granted.scopes().equals(Set.of(GoogleClient.READ_ONLY))) {
            log.info("Member {}: Google's answer was not the read-only permission with a long-lived token", memberId);
            withdraw(granted.refreshToken() != null ? granted.refreshToken() : granted.accessToken());
            return Outcome.FAILED;
        }
        String address;
        try {
            address = mailboxAddress(granted.accessToken());
        } catch (GoogleFailure failure) {
            log.info("Member {}: Google did not name the mailbox ({})", memberId, failure.failure());
            withdraw(granted.refreshToken());
            return Outcome.FAILED;
        }
        byte[] sealed = ready.vault().seal(granted.refreshToken(), TokenVault.memberAad(memberId));
        Optional<Connection> before;
        try {
            before = connections.replace(memberId, address, sealed, clock.instant());
        } catch (DataAccessException notStored) {
            log.warn("Member {}: the connection could not be stored", memberId);
            withdraw(granted.refreshToken());
            return Outcome.FAILED;
        }
        access.forget(memberId);
        // The permission that was replaced is withdrawn at Google. Not when it is the same mailbox: Google may
        // count both tokens as one permission, and withdrawing the old one could then end the new one.
        before.filter(old -> old.tokenSealed() != null && !old.address().equals(address))
                .flatMap(old -> ready.vault().open(old.tokenSealed(), TokenVault.memberAad(memberId)))
                .ifPresent(google::revoke);
        log.info("Member {} connected a mailbox", memberId);
        return Outcome.YES;
    }

    private String mailboxAddress(String accessToken) throws GoogleFailure {
        String address = google.mailboxAddress(accessToken).strip().toLowerCase(Locale.ROOT);
        int at = address.indexOf('@');
        if (at < 1 || at == address.length() - 1 || address.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new GoogleFailure(Failure.UNUSABLE);
        }
        return address;
    }

    private void withdraw(String token) {
        if (token != null) {
            google.revoke(token);
        }
    }

    private static Outcome turnedAway(String why) {
        log.info("A return to the mail connection was turned away: {}", why);
        return Outcome.FAILED;
    }

    // ---------------------------------------------------------------- disconnecting

    /** Removes the caller's own connection; Google is asked to withdraw the permission, and not waited for in vain. */
    void disconnect(UserId user) {
        Ready ready = setup.require();
        Membership mine = weddings.membershipOf(user);
        UUID memberId = mine.memberId();
        // First what is ours, so that it happens whatever Google does.
        ConnectionRepository.Removed removed = connections.remove(memberId);
        tickets.removeOf(memberId);
        access.forget(memberId);
        if (removed.existed()) {
            log.info("Member {} disconnected the mailbox", memberId);
        }
        if (removed.tokenSealed() != null) {
            ready.vault().open(removed.tokenSealed(), TokenVault.memberAad(memberId)).ifPresent(google::revoke);
        }
    }

    // ---------------------------------------------------------------- what is random, and hashed

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always there");
        }
    }

    /** PKCE's S256: the verifier's hash, base64url. */
    static String challengeOf(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }
}
