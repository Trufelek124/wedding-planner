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
 * V20 on a database where messages were read before a letter was known by a key: they keep their verdicts and have no key,
 * and the new verdict and the key can be stored. The database is one of the test's own.
 */
class MailLetterKeyMigrationIntegrationTest extends PostgresIntegrationTest {

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @Autowired
    JdbcTemplate server;

    @Test
    void messagesThatWereReadKeepTheirVerdictsAndHaveNoKeyAndTheNewVerdictCanBeStored() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("19").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        UUID anna = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 1, 'Anna')", anna, wedding);
        old.update("""
                INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at, sync_marker, last_look_at)
                VALUES (?, 'CONNECTED', ?, ?, now(), '7391000', now())
                """, anna, anna + "@planner.example", new byte[40]);
        note(old, anna, "a-not-wedding", 2, "NOT_WEDDING", null, null);
        note(old, anna, "a-wedding", 3, "WEDDING", null, 2);
        note(old, anna, "a-unreadable", 4, "UNREADABLE", "GONE", null);

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        List<Map<String, Object>> rows = old.queryForList("SELECT message_id, verdict, unread_reason, proposals, letter_key FROM mail_message ORDER BY message_id");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("message_id", "a-not-wedding").containsEntry("verdict", "NOT_WEDDING").containsEntry("letter_key", null);
        assertThat(rows.get(1)).containsEntry("message_id", "a-unreadable").containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "GONE")
                .containsEntry("letter_key", null);
        assertThat(rows.get(2)).containsEntry("message_id", "a-wedding").containsEntry("verdict", "WEDDING").containsEntry("proposals", 2)
                .containsEntry("letter_key", null);

        // The new truths hold on the migrated table.
        String key = "l1-" + "B".repeat(43);
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET verdict = 'COPY' WHERE message_id = 'a-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET letter_key = 'nonsense' WHERE message_id = 'a-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE mail_message SET verdict = 'MAYBE' WHERE message_id = 'a-wedding'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE mail_message SET letter_key = ? WHERE message_id = 'a-wedding'", key);
        old.update("INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness, verdict, documents, letter_key, read_at)"
                + " VALUES (?, 'a-copy', 'thread-a-copy', now(), 1, 9, 'COPY', 'DONE', ?, now())", anna, key);
        assertThat(old.queryForObject("SELECT count(*) FROM mail_message WHERE letter_key = ?", Integer.class, key)).isEqualTo(2);
    }

    private static void note(JdbcTemplate db, UUID member, String id, long newness, String verdict, String reason, Integer proposals) {
        db.update("""
                INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness, verdict, unread_reason, proposals, read_at)
                VALUES (?, ?, ?, now(), 1, ?, ?, ?, ?, ?)
                """, member, id, "thread-" + id, newness, verdict, reason, proposals,
                verdict == null ? null : new java.sql.Timestamp(System.currentTimeMillis()));
    }
}
