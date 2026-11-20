package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Noted;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
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
    record Counts(int waiting, int read, int aboutTheWedding, int couldNotBeRead, int proposals, int copies) {}

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

    /**
     * Notes what a look back found, as part of the ask {@code lookId} of this connection: the messages are noted under
     * the ask's own look, the number of the new ones is added to what the ask has found, and with {@code last} the ask is
     * done. All in one transaction, so a look back is done only with the last page, and a page stored twice (a run that
     * is done again after it was cut off) counts each message once. Nothing is stored when the connection was replaced
     * or removed, or the owner asked again, since the look began.
     *
     * @return how many messages were new, or empty when this is no longer the ask that is under way
     */
    @Transactional
    Optional<Integer> noteLookBack(UUID memberId, Instant connectedAt, long lookId, List<Noted> messages, Instant now, boolean last) {
        boolean still = !jdbc.sql("""
                SELECT 1 FROM mail_connection
                WHERE member_id = ? AND connected_at = ? AND state = 'CONNECTED'
                  AND look_back_look = ? AND look_back_done_at IS NULL
                FOR UPDATE
                """)
                .params(memberId, at(connectedAt), lookId)
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
                    VALUES (?, ?, ?, ?, ?, -1 * nextval('mail_noted_seq'))
                    ON CONFLICT (member_id, message_id) DO NOTHING
                    """)
                    .params(memberId, message.messageId(), message.threadId(), at(now), lookId)
                    .update();
        }
        jdbc.sql("""
                UPDATE mail_connection
                SET look_back_found = look_back_found + ?, look_back_done_at = CASE WHEN ? THEN CAST(? AS timestamptz) END
                WHERE member_id = ?
                """)
                .params(added, last, at(now), memberId)
                .update();
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
        return waiting(most, List.of());
    }

    /** As {@link #waiting(int)}, without the mailboxes of these members. */
    List<Waiting> waiting(int most, Collection<UUID> leavingOut) {
        String leaving = leavingOut.isEmpty() ? "" : " AND m.member_id NOT IN (:leavingOut)";
        return jdbc.sql("""
                SELECT w.wedding_id, m.member_id, m.message_id, m.thread_id
                FROM mail_message m
                JOIN mail_connection c ON c.member_id = m.member_id AND c.state = 'CONNECTED'
                JOIN wedding_member w ON w.id = m.member_id
                WHERE m.verdict IS NULL""" + leaving + """

                ORDER BY m.look DESC, m.newness DESC
                LIMIT :most
                """)
                .param("most", most)
                .params(leavingOut.isEmpty() ? Map.<String, Object>of() : Map.<String, Object>of("leavingOut", List.copyOf(leavingOut)))
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
     * @param letterKey what the letter is known by (a hash), or null where no letter was had
     * @return whether this call gave the verdict; false when it had one, or the message is not there (any more)
     */
    boolean mark(UUID weddingId, UUID memberId, String messageId, Verdict verdict, String letterKey, Instant now, boolean documentsStillToLookAt) {
        return jdbc.sql("""
                UPDATE mail_message SET verdict = ?, unread_reason = ?, proposals = ?, letter_key = ?, read_at = ?,
                    documents = CASE WHEN ? THEN 'PENDING' ELSE 'DONE' END
                WHERE member_id = ? AND message_id = ? AND verdict IS NULL
                  AND member_id IN (SELECT id FROM wedding_member WHERE wedding_id = ?)
                """)
                .params(verdict.word(), verdict.reason(), verdict.proposals(), letterKey, at(now), documentsStillToLookAt, memberId, messageId, weddingId)
                .update() == 1;
    }

    /**
     * Writes that the documents of this message are about to be read, in a transaction of its own and at once: before
     * anything is fetched, so that a message whose documents stopped the server is not tried a second time (S04 section 5).
     * It counts only for a message of this wedding's member whose documents were still to be looked at.
     *
     * @return whether this call was the first: true once for a message, false for every call after it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean beginDocuments(UUID weddingId, UUID memberId, String messageId) {
        return jdbc.sql("""
                UPDATE mail_message SET documents = 'BEGUN'
                WHERE member_id = ? AND message_id = ? AND documents = 'PENDING'
                  AND member_id IN (SELECT id FROM wedding_member WHERE wedding_id = ?)
                """)
                .params(memberId, messageId, weddingId)
                .update() == 1;
    }

    /** The mark of {@link #beginDocuments} is taken back (BEGUN to PENDING), in a transaction of its own. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean endDocuments(UUID weddingId, UUID memberId, String messageId) {
        return jdbc.sql("""
                UPDATE mail_message SET documents = 'PENDING'
                WHERE member_id = ? AND message_id = ? AND documents = 'BEGUN'
                  AND member_id IN (SELECT id FROM wedding_member WHERE wedding_id = ?)
                """)
                .params(memberId, messageId, weddingId)
                .update() == 1;
    }

    /**
     * Whether another noted message of this wedding has this key, the verdict WEDDING and its documents done with. Served by the
     * index on {@code letter_key}.
     */
    boolean documentsOfACopyWereLookedAt(UUID weddingId, UUID memberId, String messageId, String letterKey) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM mail_message m JOIN wedding_member w ON w.id = m.member_id
                    WHERE m.letter_key = ? AND m.verdict = 'WEDDING' AND m.documents = 'DONE' AND w.wedding_id = ?
                      AND NOT (m.member_id = ? AND m.message_id = ?))
                """)
                .params(letterKey, weddingId, memberId, messageId)
                .query(Boolean.class)
                .single();
    }

    /**
     * The messages about the wedding whose documents were never looked at, the newest first, of the mailboxes that are
     * connected, without the mailboxes of these members. A message whose documents were begun is not among them.
     */
    List<Waiting> waitingForDocuments(int most, Collection<UUID> leavingOut) {
        String leaving = leavingOut.isEmpty() ? "" : " AND m.member_id NOT IN (:leavingOut)";
        return jdbc.sql("""
                SELECT w.wedding_id, m.member_id, m.message_id, m.thread_id
                FROM mail_message m
                JOIN mail_connection c ON c.member_id = m.member_id AND c.state = 'CONNECTED'
                JOIN wedding_member w ON w.id = m.member_id
                WHERE m.verdict = 'WEDDING' AND m.documents = 'PENDING'""" + leaving + """

                ORDER BY m.look DESC, m.newness DESC
                LIMIT :most
                """)
                .param("most", most)
                .params(leavingOut.isEmpty() ? Map.<String, Object>of() : Map.<String, Object>of("leavingOut", List.copyOf(leavingOut)))
                .query((rows, number) -> new Waiting(
                        rows.getObject("wedding_id", UUID.class),
                        rows.getObject("member_id", UUID.class),
                        rows.getString("message_id"),
                        rows.getString("thread_id")))
                .list();
    }

    /**
     * The documents of a message about the wedding are dealt with, and the proposals they brought are added to the number
     * kept with the message, in one statement. Only a message about the wedding whose documents were not yet done with.
     *
     * @return whether this call did it
     */
    boolean documentsDone(UUID weddingId, UUID memberId, String messageId, int moreProposals, String letterKey) {
        return jdbc.sql("""
                UPDATE mail_message SET documents = 'DONE', proposals = proposals + ?, letter_key = COALESCE(letter_key, ?)
                WHERE member_id = ? AND message_id = ? AND verdict = 'WEDDING' AND documents <> 'DONE'
                  AND member_id IN (SELECT id FROM wedding_member WHERE wedding_id = ?)
                """)
                .params(moreProposals, letterKey, memberId, messageId, weddingId)
                .update() == 1;
    }

    /**
     * Whether another noted message of this wedding (in either mailbox) has a verdict and this key. The message itself is
     * not its own copy.
     */
    boolean copyWasRead(UUID weddingId, UUID memberId, String messageId, String letterKey) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM mail_message m JOIN wedding_member w ON w.id = m.member_id
                    WHERE m.letter_key = ? AND m.verdict IS NOT NULL AND w.wedding_id = ?
                      AND NOT (m.member_id = ? AND m.message_id = ?))
                """)
                .params(letterKey, weddingId, memberId, messageId)
                .query(Boolean.class)
                .single();
    }

    Counts counts(UUID weddingId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE m.verdict IS NULL) AS waiting,
                       count(*) FILTER (WHERE m.verdict IS NOT NULL) AS read,
                       count(*) FILTER (WHERE m.verdict = 'WEDDING') AS about,
                       count(*) FILTER (WHERE m.verdict = 'UNREADABLE') AS unreadable,
                       count(*) FILTER (WHERE m.verdict = 'COPY') AS copies,
                       COALESCE(sum(m.proposals) FILTER (WHERE m.verdict = 'WEDDING'), 0) AS proposals
                FROM mail_message m JOIN wedding_member w ON w.id = m.member_id
                WHERE w.wedding_id = ?
                """)
                .param(weddingId)
                .query((rows, number) -> new Counts(rows.getInt("waiting"), rows.getInt("read"), rows.getInt("about"),
                        rows.getInt("unreadable"), rows.getInt("proposals"), rows.getInt("copies")))
                .single();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
