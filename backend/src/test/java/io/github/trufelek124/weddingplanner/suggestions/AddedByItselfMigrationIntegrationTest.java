package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V18 on a database that already holds suggestions the way V17 left them: the entry that an accepted suggestion made
 * learns where it comes from, and nothing else about it moves. The database is one of the test's own.
 */
class AddedByItselfMigrationIntegrationTest extends PostgresIntegrationTest {

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @Autowired
    JdbcTemplate server;

    @Test
    void anEntryThatAnAcceptedSuggestionMadeLearnsWhichSuggestionMadeIt() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("17").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID made = UUID.randomUUID();
        UUID byHand = UUID.randomUUID();
        UUID acceptedSuggestion = UUID.randomUUID();
        UUID rejectedSuggestion = UUID.randomUUID();
        UUID waitingSuggestion = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 1, 'Anna')", member, wedding);
        for (UUID entry : new UUID[] {made, byHand}) {
            old.update("""
                    INSERT INTO entry (id, wedding_id, kind, title, status, priority_mode, priority, version, created_at, updated_at)
                    VALUES (?, ?, 'TASK', 'An entry', 'NOT_STARTED', 'AUTO', NULL, 3, now(), now())
                    """, entry, wedding);
        }
        insertSuggestion(old, wedding, member, acceptedSuggestion, "k1", "ACCEPTED", made);
        insertSuggestion(old, wedding, member, rejectedSuggestion, "k2", "REJECTED", null);
        insertSuggestion(old, wedding, member, waitingSuggestion, "k3", "WAITING", null);

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        assertThat(old.queryForObject("SELECT from_suggestion_id FROM entry WHERE id = ?", UUID.class, made)).isEqualTo(acceptedSuggestion);
        assertThat(old.queryForObject("SELECT from_suggestion_id FROM entry WHERE id = ?", UUID.class, byHand)).isNull();
        assertThat(old.queryForObject("SELECT version FROM entry WHERE id = ?", Integer.class, made)).as("saving is not what happened").isEqualTo(3);
        assertThat(old.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, acceptedSuggestion)).isEqualTo("ACCEPTED");
        assertThat(old.queryForObject("SELECT count(*) FROM suggestion_setting", Integer.class)).as("no row: on, at 85").isZero();
        // The new truths hold here too.
        assertThatThrownBy(() -> old.update("UPDATE suggestion SET state = 'ADDED' WHERE id = ?", waitingSuggestion))
                .isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", byHand, waitingSuggestion);
        assertThat(old.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, waitingSuggestion)).isEqualTo("ADDED");
    }

    private static void insertSuggestion(JdbcTemplate db, UUID wedding, UUID member, UUID id, String key, String state, UUID entry) {
        db.update("""
                INSERT INTO suggestion (id, wedding_id, source_key, kind, state, title, received_at, inbox_member_id, mail_link,
                                        confidence, decided_by, decided_at, made_entry_id, created_at)
                VALUES (?, ?, ?, 'TASK', ?, 'A made-up task', now(), ?, 'https://mail.google.com/mail/u/0/#inbox/1', 90,
                        ?, ?, ?, now())
                """, id, wedding, key, state, member, "WAITING".equals(state) ? null : member,
                "WAITING".equals(state) ? null : new java.sql.Timestamp(System.currentTimeMillis()), entry);
    }

    @Test
    void v23GivesEveryOldSuggestionFromDocumentFalseAndLetsOnlyAPaymentFromADocumentBeAdded() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("22").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        old.update("INSERT INTO wedding_member (id, wedding_id, position, display_name) VALUES (?, ?, 1, 'Anna')", member, wedding);
        old.update("""
                INSERT INTO entry (id, wedding_id, kind, title, status, priority_mode, priority, version, created_at, updated_at)
                VALUES (?, ?, 'TASK', 'An entry', 'NOT_STARTED', 'AUTO', NULL, 0, now(), now())
                """, entry, wedding);
        // Suggestions in every state, as V22 left them: a task that was added by itself, one accepted, one rejected, two waiting.
        UUID added = UUID.randomUUID();
        UUID accepted = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        UUID waitingPayment = UUID.randomUUID();
        UUID waitingVendor = UUID.randomUUID();
        insert(old, wedding, member, added, "k1", "TASK", "ADDED", entry, null);
        insert(old, wedding, member, accepted, "k2", "TASK", "ACCEPTED", entry, 5000L);
        insert(old, wedding, member, rejected, "k3", "PAYMENT", "REJECTED", null, 5000L);
        insert(old, wedding, member, waitingPayment, "k4", "PAYMENT", "WAITING", null, 7000L);
        insert(old, wedding, member, waitingVendor, "k5", "VENDOR", "WAITING", null, null);

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        assertThat(old.queryForList("SELECT from_document FROM suggestion", Boolean.class)).hasSize(5).containsOnly(false);
        assertThat(old.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, added)).as("the old rows fit the new check").isEqualTo("ADDED");
        // A payment that only the mail names, a payment without an amount and a vendor cannot be ADDED, whatever else is true.
        for (UUID refused : new UUID[] {waitingPayment, waitingVendor}) {
            assertThatThrownBy(() -> old.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", entry, refused))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        old.update("UPDATE suggestion SET from_document = true WHERE id IN (?, ?)", waitingPayment, waitingVendor);
        assertThatThrownBy(() -> old.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", entry, waitingVendor))
                .as("not even a vendor from a document").isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", entry, waitingPayment);
        assertThat(old.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, waitingPayment)).isEqualTo("ADDED");
    }

    private static void insert(JdbcTemplate db, UUID wedding, UUID member, UUID id, String key, String kind, String state, UUID entry, Long amount) {
        boolean decided = "ACCEPTED".equals(state) || "REJECTED".equals(state);
        db.update("""
                INSERT INTO suggestion (id, wedding_id, source_key, kind, state, title, amount, received_at, inbox_member_id, mail_link,
                                        confidence, decided_by, decided_at, made_entry_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'A made-up proposal', ?, now(), ?, 'https://mail.google.com/mail/u/0/#inbox/1', 90, ?, ?, ?, now())
                """, id, wedding, key, kind, state, amount, member, decided ? member : null,
                decided ? new java.sql.Timestamp(System.currentTimeMillis()) : null, entry != null && !"REJECTED".equals(state) && !"WAITING".equals(state) ? entry : null);
    }
}
