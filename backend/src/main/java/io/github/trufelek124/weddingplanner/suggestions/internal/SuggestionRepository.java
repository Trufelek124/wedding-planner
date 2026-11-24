package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.suggestions.Proposal;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Which;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SuggestionRepository {

    /**
     * A suggestion as stored. It holds parts of someone's mail, so what gets printed is only which one it is.
     */
    record SuggestionRow(
            UUID id,
            String sourceKey,
            Kind kind,
            State state,
            String title,
            String notes,
            LocalDate startDate,
            LocalDate dueDate,
            Long amount,
            PaymentKind paymentKind,
            UUID categoryId,
            UUID vendorId,
            String vendorName,
            String vendorEmail,
            String vendorPhone,
            String vendorWebsite,
            String senderName,
            String senderAddress,
            String subject,
            Instant receivedAt,
            UUID inboxMemberId,
            String mailLink,
            String passage,
            int confidence,
            UUID decidedBy,
            Instant decidedAt,
            UUID madeEntryId,
            UUID madeVendorId,
            Instant createdAt,
            boolean fromDocument) {

        /** The same suggestion with another link to its message. */
        SuggestionRow withMailLink(String link) {
            return new SuggestionRow(
                    id, sourceKey, kind, state, title, notes, startDate, dueDate, amount, paymentKind, categoryId, vendorId,
                    vendorName, vendorEmail, vendorPhone, vendorWebsite, senderName, senderAddress, subject, receivedAt,
                    inboxMemberId, link, passage, confidence, decidedBy, decidedAt, madeEntryId, madeVendorId, createdAt, fromDocument);
        }

        /** The same suggestion as it stands in this state, decided by this member (or by nobody) at this moment. */
        SuggestionRow decided(State to, UUID by, Instant at) {
            return new SuggestionRow(
                    id, sourceKey, kind, to, title, notes, startDate, dueDate, amount, paymentKind, categoryId, vendorId,
                    vendorName, vendorEmail, vendorPhone, vendorWebsite, senderName, senderAddress, subject, receivedAt,
                    inboxMemberId, mailLink, passage, confidence, by, at, madeEntryId, madeVendorId, createdAt, fromDocument);
        }

        @Override
        public String toString() {
            return "SuggestionRow[id=" + id + ", kind=" + kind + ", state=" + state + "]";
        }
    }

    private static final String SELECT = """
            SELECT id, source_key, kind, state, title, notes, start_date, due_date, amount, payment_kind, category_id,
                   vendor_id, vendor_name, vendor_email, vendor_phone, vendor_website, sender_name, sender_address,
                   subject, received_at, inbox_member_id, mail_link, passage, confidence, decided_by, decided_at,
                   made_entry_id, made_vendor_id, created_at, from_document
            FROM suggestion
            """;

    private final JdbcClient jdbc;

    SuggestionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SuggestionRow> find(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE wedding_id = ? AND id = ?").params(weddingId, id).query(SuggestionRepository::row).optional();
    }

    /**
     * Finds it and holds its row until the transaction ends: whoever decides the same suggestion at the same
     * moment waits here, and then sees what was decided.
     */
    Optional<SuggestionRow> findForDecision(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE wedding_id = ? AND id = ? FOR UPDATE").params(weddingId, id)
                .query(SuggestionRepository::row)
                .optional();
    }

    Optional<UUID> idOfSource(UUID weddingId, String sourceKey) {
        return jdbc.sql("SELECT id FROM suggestion WHERE wedding_id = ? AND source_key = ?")
                .params(weddingId, sourceKey)
                .query(UUID.class)
                .optional();
    }

    /**
     * The newest message first. A suggestion that was added by itself and came into being at or before {@code keptBefore}
     * counts as accepted (S05): it is told apart here, in the question, and nowhere is it written down.
     */
    List<SuggestionRow> list(UUID weddingId, Which which, Instant keptBefore) {
        String where = switch (which) {
            case ALL -> "";
            case WAITING -> " AND state = 'WAITING'";
            case REJECTED -> " AND state = 'REJECTED'";
            case ADDED -> " AND state = 'ADDED' AND created_at > :keptBefore";
            case ACCEPTED -> " AND (state = 'ACCEPTED' OR (state = 'ADDED' AND created_at <= :keptBefore))";
        };
        return jdbc.sql(SELECT + " WHERE wedding_id = :wedding" + where + " ORDER BY received_at DESC, id")
                .param("wedding", weddingId)
                .param("keptBefore", at(keptBefore))
                .query(SuggestionRepository::row)
                .list();
    }

    /** The newest first. The beginning is compared character by character: it is no pattern. */
    List<Suggestions.Proposed> proposedUnder(UUID weddingId, String keyBeginning, int most) {
        return jdbc.sql("""
                SELECT source_key, kind, title, start_date, due_date, amount FROM suggestion
                WHERE wedding_id = ? AND left(source_key, length(?)) = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """)
                .params(weddingId, keyBeginning, keyBeginning, most)
                .query(SuggestionRepository::proposed)
                .list();
    }

    /** The newest first, whatever became of them. */
    List<Suggestions.Proposed> proposedSince(UUID weddingId, Instant since, int most) {
        return jdbc.sql("""
                SELECT source_key, kind, title, start_date, due_date, amount FROM suggestion
                WHERE wedding_id = ? AND created_at >= ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """)
                .params(weddingId, at(since), most)
                .query(SuggestionRepository::proposed)
                .list();
    }

    private static Suggestions.Proposed proposed(ResultSet row, int number) throws SQLException {
        return new Suggestions.Proposed(
                row.getString("source_key"),
                Proposal.Kind.valueOf(row.getString("kind")),
                row.getString("title"),
                row.getObject("start_date", LocalDate.class),
                row.getObject("due_date", LocalDate.class),
                row.getObject("amount", Long.class));
    }

    int madeSince(UUID weddingId, UUID inboxMemberId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM suggestion WHERE wedding_id = ? AND inbox_member_id = ? AND created_at >= ?")
                .params(weddingId, inboxMemberId, at(since))
                .query(Integer.class)
                .single();
    }

    /** @return false when the wedding already has a suggestion with this source key */
    boolean insert(UUID weddingId, SuggestionRow s) {
        return jdbc.sql("""
                INSERT INTO suggestion (id, wedding_id, source_key, kind, state, title, notes, start_date, due_date, amount,
                                        payment_kind, category_id, vendor_id, vendor_name, vendor_email, vendor_phone,
                                        vendor_website, sender_name, sender_address, subject, received_at, inbox_member_id,
                                        mail_link, passage, confidence, created_at, from_document)
                VALUES (:id, :wedding, :sourceKey, :kind, 'WAITING', :title, :notes, :startDate, :dueDate, :amount,
                        :paymentKind, :category, :vendor, :vendorName, :vendorEmail, :vendorPhone, :vendorWebsite,
                        :senderName, :senderAddress, :subject, :receivedAt, :inbox, :mailLink, :passage, :confidence,
                        :createdAt, :fromDocument)
                ON CONFLICT (wedding_id, source_key) DO NOTHING
                """)
                        .param("id", s.id())
                        .param("wedding", weddingId)
                        .param("sourceKey", s.sourceKey())
                        .param("kind", s.kind().name())
                        .param("title", s.title())
                        .param("notes", s.notes())
                        .param("startDate", s.startDate())
                        .param("dueDate", s.dueDate())
                        .param("amount", s.amount())
                        .param("paymentKind", s.paymentKind() == null ? null : s.paymentKind().name())
                        .param("category", s.categoryId())
                        .param("vendor", s.vendorId())
                        .param("vendorName", s.vendorName())
                        .param("vendorEmail", s.vendorEmail())
                        .param("vendorPhone", s.vendorPhone())
                        .param("vendorWebsite", s.vendorWebsite())
                        .param("senderName", s.senderName())
                        .param("senderAddress", s.senderAddress())
                        .param("subject", s.subject())
                        .param("receivedAt", at(s.receivedAt()))
                        .param("inbox", s.inboxMemberId())
                        .param("mailLink", s.mailLink())
                        .param("passage", s.passage())
                        .param("confidence", s.confidence())
                        .param("createdAt", at(s.createdAt()))
                        .param("fromDocument", s.fromDocument())
                        .update()
                == 1;
    }

    /**
     * Whether another suggestion of the wedding proposes, or once proposed, a payment of this amount due on this day (D-014):
     * one that waits, one that was added, and also one that a person accepted or rejected - what somebody said no to is not
     * put into the plan behind their back. Both without a day count as the same day. The suggestion {@code except} is not
     * counted: it is the one asking.
     */
    boolean otherPaymentProposed(UUID weddingId, UUID except, long amount, LocalDate dueDate) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM suggestion
                    WHERE wedding_id = ? AND id <> ? AND kind = 'PAYMENT'
                      AND amount = ? AND due_date IS NOT DISTINCT FROM CAST(? AS date))
                """)
                .params(weddingId, except, amount, dueDate)
                .query(Boolean.class)
                .single();
    }

    /** Records the decision; only a suggestion that still waits can have one. */
    void decide(UUID weddingId, UUID id, State state, UUID memberId, Instant now, UUID madeEntryId, UUID madeVendorId) {
        int changed = jdbc.sql("""
                UPDATE suggestion
                SET state = :state, decided_by = :member, decided_at = :now, made_entry_id = :entry, made_vendor_id = :vendor
                WHERE wedding_id = :wedding AND id = :id AND state = 'WAITING'
                """)
                .param("state", state.name())
                .param("member", memberId)
                .param("now", at(now))
                .param("entry", madeEntryId)
                .param("vendor", madeVendorId)
                .param("wedding", weddingId)
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("A suggestion that was not waiting was decided");
        }
    }

    /** Says that the entry was made: only a suggestion that still waits (and was just handed in) can be added. */
    void markAdded(UUID weddingId, UUID id, UUID entryId) {
        int changed = jdbc.sql("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE wedding_id = ? AND id = ? AND state = 'WAITING'")
                .params(entryId, weddingId, id)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("A suggestion that was not waiting was added");
        }
    }

    /**
     * Keeps (ACCEPTED, the entry stays named) or undoes (REJECTED, no entry) a suggestion that was added by itself.
     * Only one that is still added can be settled.
     */
    void settleAdded(UUID weddingId, UUID id, State state, UUID memberId, Instant now, UUID madeEntryId) {
        int changed = jdbc.sql("""
                UPDATE suggestion
                SET state = :state, decided_by = :member, decided_at = :now, made_entry_id = :entry
                WHERE wedding_id = :wedding AND id = :id AND state = 'ADDED'
                """)
                .param("state", state.name())
                .param("member", memberId)
                .param("now", at(now))
                .param("entry", madeEntryId)
                .param("wedding", weddingId)
                .param("id", id)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("A suggestion that was not added was settled");
        }
    }

    private static SuggestionRow row(ResultSet row, int number) throws SQLException {
        String paymentKind = row.getString("payment_kind");
        return new SuggestionRow(
                row.getObject("id", UUID.class),
                row.getString("source_key"),
                Kind.valueOf(row.getString("kind")),
                State.valueOf(row.getString("state")),
                row.getString("title"),
                row.getString("notes"),
                row.getObject("start_date", LocalDate.class),
                row.getObject("due_date", LocalDate.class),
                row.getObject("amount", Long.class),
                paymentKind == null ? null : PaymentKind.valueOf(paymentKind),
                row.getObject("category_id", UUID.class),
                row.getObject("vendor_id", UUID.class),
                row.getString("vendor_name"),
                row.getString("vendor_email"),
                row.getString("vendor_phone"),
                row.getString("vendor_website"),
                row.getString("sender_name"),
                row.getString("sender_address"),
                row.getString("subject"),
                instant(row, "received_at"),
                row.getObject("inbox_member_id", UUID.class),
                row.getString("mail_link"),
                row.getString("passage"),
                row.getInt("confidence"),
                row.getObject("decided_by", UUID.class),
                instant(row, "decided_at"),
                row.getObject("made_entry_id", UUID.class),
                row.getObject("made_vendor_id", UUID.class),
                instant(row, "created_at"),
                row.getBoolean("from_document"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
