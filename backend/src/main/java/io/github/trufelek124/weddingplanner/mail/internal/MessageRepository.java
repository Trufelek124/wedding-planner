package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Noted;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The messages the sync has noted in the owners' mailboxes: two identifiers each, nothing of the message itself. */
@Repository
class MessageRepository {

    /** What makes a look count: where the mailbox stands, and when this look ended. Moved only with the last of what it found. */
    record Completion(String marker, Instant at) {

        @Override
        public String toString() {
            return "Completion[]";
        }
    }

    /**
     * One look at a mailbox, as far as the order of noting goes: a later look is newer than an earlier one, and within
     * a look the messages are as new as the order they came in says.
     *
     * @param newestListedFirst whether the source lists the newest message first (a look by date) or last (the
     *     history since a marker)
     */
    record Look(long id, boolean newestListedFirst) {}

    /** The place of a noted message that has no verdict: whose it is, and which. Identifiers only. */
    record Waiting(UUID weddingId, UUID memberId, String messageId, String threadId) {

        @Override
        public String toString() {
            return "Waiting[member=" + memberId + "]";
        }
    }

    /** The verdict as stored: the word, and what goes with it. */
    record Verdict(String word, String reason, Integer proposals) {}

    /** How many messages of a wedding's mailboxes are in each state. */
    record Counts(int waiting, int read, int aboutTheWedding, int couldNotBeRead) {}

    private final JdbcClient jdbc;

    MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Notes these messages for this owner's connection, and, in the same transaction, makes the look count when
     * {@code completion} is given: the marker and the time of the last look move, and the refusals in a row are
     * forgotten. A message that is already noted is left as it is. Nothing is stored for a connection that has been
     * replaced or removed since the look began (it was another mailbox, or none).
     *
     * @return how many messages were new, or empty when the connection is not the one the look began with
     */
    @Transactional
    Optional<Integer> note(UUID memberId, Instant connectedAt, List<Noted> messages, Instant now, Completion completion) {
        return note(memberId, connectedAt, beginLook(true), messages, now, completion);
    }

    /** A look of its own: newer than every look begun before it. */
    Look beginLook(boolean newestListedFirst) {
        long id = jdbc.sql("SELECT nextval('mail_look_seq')").query(Long.class).single();
        return new Look(id, newestListedFirst);
    }

    /** As {@link #note(UUID, Instant, List, Instant, Completion)}, as part of this look. */
    @Transactional
    Optional<Integer> note(UUID memberId, Instant connectedAt, Look look, List<Noted> messages, Instant now, Completion completion) {
        // The row is held until the end: connecting another mailbox waits for this transaction, or comes before it.
        boolean still = !jdbc.sql("""
                SELECT 1 FROM mail_connection WHERE member_id = ? AND connected_at = ? AND state = 'CONNECTED' FOR UPDATE
                """)
                .params(memberId, at(connectedAt))
                .query((rows, number) -> 1)
                .list()
                .isEmpty();
        if (!still) {
            return Optional.empty();
        }
        int added = 0;
        for (Noted message : messages) {
            added += jdbc.sql("""
                    INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness)
                    VALUES (?, ?, ?, ?, ?, ? * nextval('mail_noted_seq'))
                    ON CONFLICT (member_id, message_id) DO NOTHING
                    """)
                    .params(memberId, message.messageId(), message.threadId(), at(now), look.id(), look.newestListedFirst() ? -1 : 1)
                    .update();
        }
        if (completion != null) {
            jdbc.sql("""
                    UPDATE mail_connection SET sync_marker = ?, last_look_at = ?, refusals = 0, put_off_until = NULL
                    WHERE member_id = ?
                    """)
                    .params(completion.marker(), at(completion.at()), memberId)
                    .update();
        }
        return Optional.of(added);
    }

    int count(UUID memberId) {
        return jdbc.sql("SELECT count(*) FROM mail_message WHERE member_id = ?").param(memberId).query(Integer.class).single();
    }

    /**
     * The messages without a verdict, the newest first, of the mailboxes that are connected (one that has to be
     * connected again cannot be read, and would only fill the list). Both owners' mailboxes of every wedding are
     * in one list.
     */
    List<Waiting> waiting(int most) {
        return jdbc.sql("""
                SELECT w.wedding_id, m.member_id, m.message_id, m.thread_id
                FROM mail_message m
                JOIN mail_connection c ON c.member_id = m.member_id AND c.state = 'CONNECTED'
                JOIN wedding_member w ON w.id = m.member_id
                WHERE m.verdict IS NULL
                ORDER BY m.look DESC, m.newness DESC
                LIMIT ?
                """)
                .param(most)
                .query((rows, number) -> new Waiting(
                        rows.getObject("wedding_id", UUID.class),
                        rows.getObject("member_id", UUID.class),
                        rows.getString("message_id"),
                        rows.getString("thread_id")))
                .list();
    }

    /**
     * Gives the message its verdict, if it has none yet and belongs to this wedding's member. A message that has one
     * keeps it: the first verdict stands.
     *
     * @return whether this call gave the verdict; false when it had one, or the message is not there (any more)
     */
    boolean mark(UUID weddingId, UUID memberId, String messageId, Verdict verdict, Instant now) {
        return jdbc.sql("""
                UPDATE mail_message SET verdict = ?, unread_reason = ?, proposals = ?, read_at = ?
                WHERE member_id = ? AND message_id = ? AND verdict IS NULL
                  AND member_id IN (SELECT id FROM wedding_member WHERE wedding_id = ?)
                """)
                .params(verdict.word(), verdict.reason(), verdict.proposals(), at(now), memberId, messageId, weddingId)
                .update() == 1;
    }

    Counts counts(UUID weddingId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE m.verdict IS NULL) AS waiting,
                       count(*) FILTER (WHERE m.verdict IS NOT NULL) AS read,
                       count(*) FILTER (WHERE m.verdict = 'WEDDING') AS about,
                       count(*) FILTER (WHERE m.verdict = 'UNREADABLE') AS unreadable
                FROM mail_message m JOIN wedding_member w ON w.id = m.member_id
                WHERE w.wedding_id = ?
                """)
                .param(weddingId)
                .query((rows, number) -> new Counts(rows.getInt("waiting"), rows.getInt("read"), rows.getInt("about"), rows.getInt("unreadable")))
                .single();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
