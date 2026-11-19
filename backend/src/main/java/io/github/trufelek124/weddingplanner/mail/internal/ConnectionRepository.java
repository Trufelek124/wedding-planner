package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailTypes.ConnectionState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The connections of the owners to their mailboxes. */
@Repository
class ConnectionRepository {

    /**
     * A connection as stored. It holds an address and a sealed token: what gets printed is only whose it is.
     *
     * @param tokenSealed null exactly when the connection has to be made again
     */
    record Connection(
            UUID memberId,
            ConnectionState state,
            String address,
            byte[] tokenSealed,
            Instant connectedAt,
            String syncMarker,
            Instant lastLookAt,
            int refusals,
            Instant putOffUntil,
            LookBack lookBack) {

        @Override
        public String toString() {
            return "Connection[member=" + memberId + ", state=" + state + "]";
        }
    }

    /**
     * What the owner asked for with "Read earlier mail": how many days, when, from which moment on mail is wanted, the
     * look the ask notes its messages under, and whether and when it was done. Null in a connection where nothing was asked.
     */
    record LookBack(int days, Instant askedAt, Instant from, long look, Instant doneAt, int found) {

        boolean underWay() {
            return doneAt == null;
        }
    }

    /** What removing a connection took away. */
    record Removed(boolean existed, byte[] tokenSealed) {

        @Override
        public String toString() {
            return "Removed[existed=" + existed + "]";
        }
    }

    private static final String SELECT =
            "SELECT member_id, state, address, token_sealed, connected_at, sync_marker, last_look_at, refusals, put_off_until,"
                    + " look_back_days, look_back_asked_at, look_back_from, look_back_look, look_back_done_at, look_back_found"
                    + " FROM mail_connection";

    private final JdbcClient jdbc;

    ConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Connection> find(UUID memberId) {
        return jdbc.sql(SELECT + " WHERE member_id = ?").param(memberId).query(ConnectionRepository::connection).optional();
    }

    /** The connections that exist among these members (a wedding has two). */
    List<Connection> find(Collection<UUID> memberIds) {
        return memberIds.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    /**
     * Makes this the member's one connection, replacing any other. Where the sync stood is kept only when the
     * mailbox is the same one: another mailbox starts from the beginning.
     *
     * @return what was there before, if anything
     */
    @Transactional
    Optional<Connection> replace(UUID memberId, String address, byte[] tokenSealed, Instant now) {
        Optional<Connection> before = jdbc.sql(SELECT + " WHERE member_id = ? FOR UPDATE")
                .param(memberId)
                .query(ConnectionRepository::connection)
                .optional();
        jdbc.sql("""
                INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at)
                VALUES (?, 'CONNECTED', ?, ?, ?)
                ON CONFLICT (member_id) DO UPDATE SET
                    state = 'CONNECTED',
                    address = EXCLUDED.address,
                    token_sealed = EXCLUDED.token_sealed,
                    connected_at = EXCLUDED.connected_at,
                    sync_marker = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.sync_marker END,
                    last_look_at = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.last_look_at END,
                    refusals = 0,
                    put_off_until = NULL,
                    look_back_days = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_days END,
                    look_back_asked_at = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_asked_at END,
                    look_back_from = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_from END,
                    look_back_look = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_look END,
                    look_back_done_at = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_done_at END,
                    look_back_found = CASE WHEN mail_connection.address = EXCLUDED.address THEN mail_connection.look_back_found ELSE 0 END
                """)
                .params(memberId, address, tokenSealed, at(now))
                .update();
        if (before.isPresent() && !before.get().address().equals(address)) {
            // Another mailbox: what was noted of the old one is no longer true of this one.
            jdbc.sql("DELETE FROM mail_message WHERE member_id = ?").param(memberId).update();
        }
        return before;
    }

    /** Removes only this member's connection, and says what token it held. Removing nothing is not an error. */
    Removed remove(UUID memberId) {
        List<Removed> removed = jdbc.sql("DELETE FROM mail_connection WHERE member_id = ? RETURNING token_sealed")
                .param(memberId)
                .query((rows, number) -> new Removed(true, rows.getBytes("token_sealed")))
                .list();
        return removed.isEmpty() ? new Removed(false, null) : removed.get(0);
    }

    /**
     * Marks the connection as needing to be made again and drops the token, which is no good. Only when the token
     * is still the one that was found wanting: a connection made again in the meantime stays.
     *
     * @return whether it was changed
     */
    boolean markNeedsReconnecting(UUID memberId, byte[] tokenSealedThatFailed) {
        return jdbc.sql("""
                UPDATE mail_connection SET state = 'NEEDS_RECONNECTING', token_sealed = NULL
                WHERE member_id = ? AND state = 'CONNECTED' AND token_sealed = ?
                """)
                        .params(memberId, tokenSealedThatFailed)
                        .update()
                == 1;
    }

    /**
     * Records that the owner asked to look back {@code days} days, replacing any earlier ask: what was found by it is
     * counted from nothing, and the moment mail is wanted from is fixed now. Only for a connection that is connected.
     *
     * @return whether it was recorded; false when the member has no connection, or one that has to be made again
     */
    boolean askLookBack(UUID memberId, int days, Instant now) {
        return jdbc.sql("""
                UPDATE mail_connection
                SET look_back_days = ?,
                    look_back_asked_at = ?,
                    look_back_from = ?,
                    look_back_look = nextval('mail_look_seq'),
                    look_back_done_at = NULL,
                    look_back_found = 0
                WHERE member_id = ? AND state = 'CONNECTED'
                """)
                .params(days, at(now), at(now.minus(java.time.Duration.ofDays(days))), memberId)
                .update()
                == 1;
    }

    /** The connections the sync may look at now: connected, and not put off for a later time. Oldest connection first. */
    List<Connection> dueForLook(Instant now) {
        return jdbc.sql(SELECT + " WHERE state = 'CONNECTED' AND (put_off_until IS NULL OR put_off_until <= ?) ORDER BY connected_at, member_id")
                .param(at(now))
                .query(ConnectionRepository::connection)
                .list();
    }

    /**
     * Google refused or failed: leave this mailbox until a later time that grows with every refusal in a row (5, 10,
     * 20, 40 minutes, then an hour at most). Only for the connection the look began with.
     *
     * @return until when, or empty when that connection is gone or was replaced
     */
    Optional<Instant> putOff(UUID memberId, Instant connectedAt, Instant now) {
        return jdbc.sql("""
                UPDATE mail_connection
                SET refusals = refusals + 1,
                    put_off_until = ? + interval '1 minute' * LEAST(5 * power(2, LEAST(refusals, 10)), 60)
                WHERE member_id = ? AND connected_at = ?
                RETURNING put_off_until
                """)
                .params(at(now), memberId, at(connectedAt))
                .query((rows, number) -> rows.getObject("put_off_until", OffsetDateTime.class).toInstant())
                .optional();
    }

    private static Connection connection(java.sql.ResultSet rows, int number) throws java.sql.SQLException {
        return new Connection(
                rows.getObject("member_id", UUID.class),
                ConnectionState.valueOf(rows.getString("state")),
                rows.getString("address"),
                rows.getBytes("token_sealed"),
                rows.getObject("connected_at", OffsetDateTime.class).toInstant(),
                rows.getString("sync_marker"),
                rows.getObject("last_look_at", OffsetDateTime.class) == null
                        ? null
                        : rows.getObject("last_look_at", OffsetDateTime.class).toInstant(),
                rows.getInt("refusals"),
                rows.getObject("put_off_until", OffsetDateTime.class) == null
                        ? null
                        : rows.getObject("put_off_until", OffsetDateTime.class).toInstant(),
                lookBackOf(rows));
    }

    private static LookBack lookBackOf(java.sql.ResultSet rows) throws java.sql.SQLException {
        int days = rows.getInt("look_back_days");
        if (rows.wasNull()) {
            return null;
        }
        OffsetDateTime done = rows.getObject("look_back_done_at", OffsetDateTime.class);
        return new LookBack(
                days,
                rows.getObject("look_back_asked_at", OffsetDateTime.class).toInstant(),
                rows.getObject("look_back_from", OffsetDateTime.class).toInstant(),
                rows.getLong("look_back_look"),
                done == null ? null : done.toInstant(),
                rows.getInt("look_back_found"));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
