package io.github.trufelek124.weddingplanner.inspirations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
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

/** V24 on a database where ideas were saved before the canvas: they are laid out, oldest first, in rows of six. */
class InspirationPlaceMigrationIntegrationTest extends PostgresIntegrationTest {

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @Autowired
    JdbcTemplate server;

    @Test
    void fourteenIdeasSavedAtDifferentMomentsEndInRowsOfSixInTheOrderTheyWereSaved() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("23").load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID wedding = UUID.randomUUID();
        old.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", wedding);
        // Saved out of order on purpose: the order of saving is the order of created_at, not of the rows' insertion.
        Instant start = Instant.parse("2026-10-01T10:00:00Z");
        int[] saving = {7, 3, 12, 0, 9, 1, 13, 5, 2, 11, 4, 8, 10, 6};
        for (int number : saving) {
            old.update("""
                    INSERT INTO inspiration (id, wedding_id, url, source, title, created_at, updated_at)
                    VALUES (?, ?, ?, 'WEB', ?, ?, ?)
                    """, UUID.randomUUID(), wedding, "https://example.com/" + number, "idea-" + number,
                    Timestamp.from(start.plusSeconds(number * 3600L)), Timestamp.from(start.plusSeconds(number * 3600L + 60)));
        }

        Flyway.configure().dataSource(url, "postgres", "").load().migrate();

        List<Map<String, Object>> rows = old.queryForList("SELECT title, x, y, created_at, placed_at FROM inspiration ORDER BY created_at");
        assertThat(rows).hasSize(14);
        for (int number = 0; number < 14; number++) {
            Map<String, Object> row = rows.get(number);
            assertThat(row).as("idea %d", number)
                    .containsEntry("title", "idea-" + number)
                    .containsEntry("x", (number % 6) * 220)
                    .containsEntry("y", (number / 6) * 260);
            assertThat(row.get("placed_at")).as("put there when it was saved").isEqualTo(row.get("created_at"));
        }

        // The table now refuses places beyond the canvas.
        assertThatThrownBy(() -> old.update("UPDATE inspiration SET x = 100001")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE inspiration SET y = -100001")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> old.update("UPDATE inspiration SET x = NULL")).isInstanceOf(DataIntegrityViolationException.class);
        old.update("UPDATE inspiration SET x = -100000, y = 100000 WHERE title = 'idea-0'");
    }
}
