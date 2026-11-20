package io.github.trufelek124.weddingplanner.planning;

import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.file;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Two uploads of one new key at the same moment: the unique index decides, and the loser is refused, not broken. */
class TaskFileRaceIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void theUploadThatLosesTheRaceForAKeyEndsInARefusalAndNothingOfItRemains() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TaskFileFixture files = new TaskFileFixture(wedding);
        UUID weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);

        try (Connection rival = dataSource.getConnection()) {
            rival.setAutoCommit(false);
            // The rival holds the key in a transaction that has not ended: the upload cannot see it, and meets it at the index.
            try (var insert = rival.prepareStatement("""
                    INSERT INTO entry (id, wedding_id, kind, title, status, priority_mode, version, created_at, updated_at, task_key)
                    VALUES (?, ?, 'TASK', 'The rival', 'NOT_STARTED', 'AUTO', 0, now(), now(), 'k')
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, weddingId);
                insert.executeUpdate();
            }
            CompletableFuture<HttpResponse<String>> upload = CompletableFuture.supplyAsync(() -> {
                try {
                    return files.apply(wedding.anna, file(row("other", "Made by the upload"), row("k", "Made by the upload too")));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            // The upload has made its first row and waits for the second at the index.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND state = 'active'",
                            Integer.class) == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(upload).as("the upload waits for the rival").isNotDone();
            rival.commit();

            HttpResponse<String> response = upload.get(20, TimeUnit.SECONDS);

            assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
            assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:task-file-refused");
            assertThat(TestHttp.fieldProblems(response)).containsExactly("tasks[2].key");
        }
        // Nothing of the loser remains: not the row that came before the one that met the index.
        assertThat(jdbc.queryForList("SELECT title FROM entry", String.class)).containsExactly("The rival");
    }
}
