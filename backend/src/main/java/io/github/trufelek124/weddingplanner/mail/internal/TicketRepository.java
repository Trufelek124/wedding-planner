package io.github.trufelek124.weddingplanner.mail.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The one-time tickets that start a connection. Only hashes are kept of what a browser and Google send back. */
@Repository
class TicketRepository {

    /** A ticket as stored. Nothing printed of it but whose it is. */
    record Ticket(UUID memberId, byte[] cookieHash, byte[] verifierSealed, Instant expiresAt, boolean used) {

        @Override
        public String toString() {
            return "Ticket[member=" + memberId + "]";
        }
    }

    private final JdbcClient jdbc;

    TicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Keeps a new ticket. Tickets that have run out go with it, and so do this member's earlier ones that were never
     * used: pressing "connect" again starts over, and the browser's cookie only ever fits the latest.
     */
    @Transactional
    void create(byte[] stateHash, UUID memberId, byte[] cookieHash, byte[] verifierSealed, Instant now, Instant expiresAt) {
        jdbc.sql("DELETE FROM mail_connect_ticket WHERE expires_at <= ?").param(at(now)).update();
        jdbc.sql("DELETE FROM mail_connect_ticket WHERE member_id = ? AND used_at IS NULL").param(memberId).update();
        jdbc.sql("""
                INSERT INTO mail_connect_ticket (state_hash, member_id, cookie_hash, verifier_sealed, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(stateHash, memberId, cookieHash, verifierSealed, at(now), at(expiresAt))
                .update();
    }

    Optional<Ticket> find(byte[] stateHash) {
        return jdbc.sql("""
                SELECT member_id, cookie_hash, verifier_sealed, expires_at, used_at
                FROM mail_connect_ticket WHERE state_hash = ?
                """)
                .param(stateHash)
                .query((rows, number) -> new Ticket(
                        rows.getObject("member_id", UUID.class),
                        rows.getBytes("cookie_hash"),
                        rows.getBytes("verifier_sealed"),
                        rows.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rows.getObject("used_at", OffsetDateTime.class) != null))
                .optional();
    }

    /**
     * Takes the ticket: one statement that changes a row only if the ticket is unused and has not run out, so that of
     * any number of returns at the same moment exactly one gets it.
     */
    boolean take(byte[] stateHash, Instant now) {
        return jdbc.sql("""
                UPDATE mail_connect_ticket SET used_at = ?
                WHERE state_hash = ? AND used_at IS NULL AND expires_at > ?
                """)
                        .params(at(now), stateHash, at(now))
                        .update()
                == 1;
    }

    void removeOf(UUID memberId) {
        jdbc.sql("DELETE FROM mail_connect_ticket WHERE member_id = ?").param(memberId).update();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
