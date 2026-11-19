package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V19 on a database where the old first look had noted mail (D-009): the messages nobody has read are let go of, the
 * ones that were read keep their rows and their verdicts, and the connection itself is untouched and has asked for
 * nothing. The database is one of the test's own.
 */
class MailLookingBackMigrationIntegrationTest extends PostgresIntegrationTest {

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @Autowired
    JdbcTemplate server;

    @Test
    void theNotesNobodyReadAreGoneTheOnesThatWereReadStayWithTheirVerdicts() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("18").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        UUID anna = UUID.randomUUID();
        UUID piotr = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 1, 'Anna')", anna, wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 2, 'Piotr')", piotr, wedding);
        for (UUID member : new UUID[] {anna, piotr}) {
            old.update("""
                    INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at, sync_marker, last_look_at)
                    VALUES (?, 'CONNECTED', ?, ?, now(), '7391000', now())
                    """, member, member + "@planner.example", new byte[40]);
        }
        note(old, anna, "a-waiting-1", 1, null, null, null);
        note(old, anna, "a-waiting-2", 2, null, null, null);
        note(old, anna, "a-not-wedding", 3, "NOT_WEDDING", null, null);
        note(old, anna, "a-wedding", 4, "WEDDING", null, 2);
        note(old, anna, "a-unreadable", 5, "UNREADABLE", "GONE", null);
        note(old, piotr, "p-waiting", 6, null, null, null);
        note(old, piotr, "p-not-wedding", 7, "NOT_WEDDING", null, null);

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        List<String> kept = old.queryForList("SELECT message_id FROM mail_message ORDER BY message_id", String.class);
        assertThat(kept).containsExactly("a-not-wedding", "a-unreadable", "a-wedding", "p-not-wedding");
        assertThat(old.queryForObject("SELECT verdict FROM mail_message WHERE message_id = 'a-wedding'", String.class)).isEqualTo("WEDDING");
        assertThat(old.queryForObject("SELECT proposals FROM mail_message WHERE message_id = 'a-wedding'", Integer.class)).isEqualTo(2);
        assertThat(old.queryForObject("SELECT unread_reason FROM mail_message WHERE message_id = 'a-unreadable'", String.class)).isEqualTo("GONE");
        assertThat(old.queryForObject("SELECT verdict FROM mail_message WHERE message_id = 'p-not-wedding'", String.class)).isEqualTo("NOT_WEDDING");
        // The connections are as they were, and nobody has asked for anything.
        assertThat(old.queryForObject("SELECT count(*) FROM mail_connection WHERE sync_marker = '7391000' AND last_look_at IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(old.queryForObject(
                "SELECT count(*) FROM mail_connection WHERE look_back_days IS NULL AND look_back_asked_at IS NULL AND look_back_from IS NULL"
                        + " AND look_back_look IS NULL AND look_back_done_at IS NULL AND look_back_found = 0", Integer.class)).isEqualTo(2);
        // The new truths hold on the migrated table.
        assertThatThrownBy(() -> old.update("UPDATE mail_connection SET look_back_days = 6, look_back_asked_at = now(),"
                + " look_back_from = now() - interval '6 days', look_back_look = 1 WHERE member_id = ?", anna))
                .isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE mail_connection SET look_back_days = 30, look_back_asked_at = now(),"
                + " look_back_from = now() - interval '30 days', look_back_look = 1 WHERE member_id = ?", anna);
    }

    private static void note(JdbcTemplate db, UUID member, String id, long newness, String verdict, String reason, Integer proposals) {
        db.update("""
                INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness, verdict, unread_reason, proposals, read_at)
                VALUES (?, ?, ?, now(), 1, ?, ?, ?, ?, ?)
                """, member, id, "thread-" + id, newness, verdict, reason, proposals,
                verdict == null ? null : new java.sql.Timestamp(System.currentTimeMillis()));
    }
}
