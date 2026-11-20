package io.github.trufelek124.weddingplanner.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.Keyed;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The server holds one wedding, so another wedding's keys cannot be tried through the endpoints: this holds the data
 * layer, which every call goes through, to the wedding it is asked for. The installation's own "one wedding" rule is
 * lifted for the length of the test and put back.
 */
class TaskFileKeysAreOfTheirWeddingIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntryRepository entries;

    private static final String INSERT = """
            INSERT INTO entry (id, wedding_id, kind, title, status, priority_mode, version, created_at, updated_at, task_key)
            VALUES (?, ?, 'TASK', ?, 'NOT_STARTED', 'AUTO', 0, now(), now(), ?)
            """;

    @Test
    void theSameKeyInAnotherWeddingIsAnotherTaskAndIsNeverFoundByTheFirst() throws Exception {
        TestWedding.fresh(jdbc, port);
        UUID ours = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        UUID other = UUID.randomUUID();
        jdbc.execute("ALTER TABLE wedding DROP CONSTRAINT wedding_only_one");
        try {
            jdbc.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", other);
            UUID theirEntry = UUID.randomUUID();
            UUID ourEntry = UUID.randomUUID();
            jdbc.update(INSERT, theirEntry, other, "Theirs", "k");
            // The same key in our wedding is allowed: the key is unique per wedding.
            jdbc.update(INSERT, ourEntry, ours, "Ours", "k");

            List<Keyed> found = entries.findByTaskKeys(ours, List.of("k"), false);

            assertThat(found).hasSize(1);
            assertThat(found.get(0).entry().id()).isEqualTo(ourEntry);
            assertThat(entries.findByTaskKeys(other, List.of("k"), true)).extracting(keyed -> keyed.entry().id()).containsExactly(theirEntry);
            UUID third = UUID.randomUUID();
            jdbc.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", third);
            assertThat(entries.findByTaskKeys(third, List.of("k"), false)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM wedding WHERE id <> ?", ours);
            jdbc.execute("ALTER TABLE wedding ADD CONSTRAINT wedding_only_one UNIQUE (only_one)");
        }
    }
}
