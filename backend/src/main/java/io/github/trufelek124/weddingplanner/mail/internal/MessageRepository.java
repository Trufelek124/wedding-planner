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
                    INSERT INTO mail_message (member_id, message_id, thread_id, noted_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (member_id, message_id) DO NOTHING
                    """)
                    .params(memberId, message.messageId(), message.threadId(), at(now))
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

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
