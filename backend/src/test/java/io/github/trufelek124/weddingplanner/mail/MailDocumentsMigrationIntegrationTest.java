package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V21 on a database where messages were read before documents could be: a message about the wedding has its documents still to
 * be looked at, every other verdict has them done with, and a message that waits will have them dealt with when it is read.
 * The database is one of the test's own.
 */
class MailDocumentsMigrationIntegrationTest extends PostgresIntegrationTest {

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @Autowired
    JdbcTemplate server;

    @Test
    void messagesReadBeforeAreToBeLookedAtOnceMoreOnlyWhenTheyWereAboutTheWedding() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("20").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        UUID anna = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 1, 'Anna')", anna, wedding);
        old.update("""
                INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at, sync_marker, last_look_at)
                VALUES (?, 'CONNECTED', ?, ?, now(), '7391000', now())
                """, anna, anna + "@planner.example", new byte[40]);
        String key = "l1-" + "C".repeat(43);
        note(old, anna, "a-waiting", 1, null, null, null, null);
        note(old, anna, "b-not-wedding", 2, "NOT_WEDDING", null, null, null);
        note(old, anna, "c-wedding", 3, "WEDDING", null, 2, null);
        note(old, anna, "d-unreadable", 4, "UNREADABLE", "GONE", null, null);
        note(old, anna, "e-copy", 5, "COPY", null, null, key);

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        List<Map<String, Object>> rows = old.queryForList("SELECT message_id, verdict, proposals, documents FROM mail_message ORDER BY message_id");
        assertThat(rows).extracting(row -> row.get("message_id") + ":" + row.get("documents")).containsExactly(
                "a-waiting:PENDING", "b-not-wedding:DONE", "c-wedding:PENDING", "d-unreadable:DONE", "e-copy:DONE");
        assertThat(rows.get(2)).containsEntry("verdict", "WEDDING").containsEntry("proposals", 2);

        // The new truths hold on the migrated table.
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET documents = 'MAYBE' WHERE message_id = 'c-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET documents = NULL WHERE message_id = 'c-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 'b-not-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE mail_message SET documents = 'BEGUN' WHERE message_id = 'c-wedding'");
        old.update("UPDATE mail_message SET documents = 'DONE' WHERE message_id = 'a-waiting'");
        // A message noted after the migration has its documents still to be looked at.
        old.update("INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness) VALUES (?, 'f-new', 'thread-f', now(), 2, 6)", anna);
        assertThat(old.queryForObject("SELECT documents FROM mail_message WHERE message_id = 'f-new'", String.class)).isEqualTo("PENDING");
    }

    private static void note(JdbcTemplate db, UUID member, String id, long newness, String verdict, String reason, Integer proposals, String key) {
        db.update("""
                INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness, verdict, unread_reason, proposals, letter_key, read_at)
                VALUES (?, ?, ?, now(), 1, ?, ?, ?, ?, ?, ?)
                """, member, id, "thread-" + id, newness, verdict, reason, proposals, key,
                verdict == null ? null : new java.sql.Timestamp(System.currentTimeMillis()));
    }
}
