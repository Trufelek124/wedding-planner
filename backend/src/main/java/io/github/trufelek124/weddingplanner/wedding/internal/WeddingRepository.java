package io.github.trufelek124.weddingplanner.wedding.internal;

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
class WeddingRepository {

    record WeddingRow(UUID id, LocalDate weddingDate, String currency, String timeZone) {}

    record MemberRow(UUID id, UUID weddingId, int position, String displayName, UUID userId) {}

    private final JdbcClient jdbc;

    WeddingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<WeddingRow> find() {
        return jdbc.sql("SELECT id, wedding_date, currency, time_zone FROM wedding").query(WeddingRow.class).optional();
    }

    /** The same, holding the row until the transaction ends, so that two requests cannot both take the free place. */
    Optional<WeddingRow> findForUpdate() {
        return jdbc.sql("SELECT id, wedding_date, currency, time_zone FROM wedding FOR UPDATE")
                .query(WeddingRow.class)
                .optional();
    }

    /** @return false when a wedding exists already */
    boolean insert(WeddingRow wedding, Instant now) {
        return jdbc.sql("""
                INSERT INTO wedding (id, wedding_date, currency, time_zone, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (only_one) DO NOTHING
                """)
                        .params(wedding.id(), wedding.weddingDate(), wedding.currency(), wedding.timeZone(), at(now), at(now))
                        .update()
                == 1;
    }

    void update(WeddingRow wedding, Instant now) {
        jdbc.sql("UPDATE wedding SET wedding_date = ?, currency = ?, time_zone = ?, updated_at = ? WHERE id = ?")
                .params(wedding.weddingDate(), wedding.currency(), wedding.timeZone(), at(now), wedding.id())
                .update();
    }

    List<MemberRow> members(UUID weddingId) {
        return jdbc.sql("""
                SELECT id, wedding_id, position, display_name, user_id
                FROM wedding_member WHERE wedding_id = ? ORDER BY position
                """)
                .param(weddingId)
                .query(MemberRow.class)
                .list();
    }

    void insertMember(MemberRow member) {
        jdbc.sql("INSERT INTO wedding_member (id, wedding_id, position, display_name, user_id) VALUES (?, ?, ?, ?, ?)")
                .params(member.id(), member.weddingId(), member.position(), member.displayName(), member.userId())
                .update();
    }

    void linkUser(UUID memberId, UUID userId) {
        jdbc.sql("UPDATE wedding_member SET user_id = ? WHERE id = ? AND user_id IS NULL").params(userId, memberId).update();
    }

    /** @return false when the wedding has no such member */
    boolean rename(UUID weddingId, UUID memberId, String displayName) {
        return jdbc.sql("UPDATE wedding_member SET display_name = ? WHERE id = ? AND wedding_id = ?")
                        .params(displayName, memberId, weddingId)
                        .update()
                == 1;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
