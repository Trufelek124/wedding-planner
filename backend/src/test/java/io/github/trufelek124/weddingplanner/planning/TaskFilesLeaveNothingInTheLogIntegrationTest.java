package io.github.trufelek124.weddingplanner.planning;

import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.file;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * What a file of tasks says is the owners' own: keys, titles, notes, names of vendors and categories, amounts. With the
 * most talkative logging, checking it, applying it, and being refused must leave none of that in the log; only that a
 * file was checked or applied, by which member, and the four numbers.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.web=TRACE",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class TaskFilesLeaveNothingInTheLogIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void nothingOfAFileReachesTheLogOnlyThatItWasCheckedOrApplied(CapturedOutput output) throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TaskFileFixture files = new TaskFileFixture(wedding);
        files.vendor("Zephyr Marquee Hire");
        String good = file(
                row("zq-key-7781", "Reserve the zephyr marquee", "'notes':'Ask about quokka-proof flooring'",
                        "'category':'Quirky Extras'", "'vendor':'zephyr marquee hire'",
                        "'amount':{'value':'31415.92','currency':'PLN'}", "'paymentKind':'DEPOSIT'"),
                row("zq-key-7782", "Second row about walruses"));
        String broken = file(row("zq-key-7783", "Walrus title", "'dueDate':'not-a-day-walrus'", "'vendor':'Nobody Walrus Ltd'"));

        assertThat(files.check(wedding.anna, good).statusCode()).isEqualTo(200);
        HttpResponse<String> applied = files.apply(wedding.anna, good);
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        assertThat(files.apply(wedding.piotr, good).statusCode()).isEqualTo(200);
        assertThat(files.check(wedding.anna, broken).statusCode()).isEqualTo(200);
        assertThat(files.apply(wedding.anna, broken).statusCode()).isEqualTo(422);
        assertThat(files.check(wedding.anna, "this is not JSON, said the quokka").statusCode()).isEqualTo(200);
        assertThat(files.apply(wedding.anna, "this is not JSON, said the quokka").statusCode()).isEqualTo(422);

        String log = output.getAll();
        assertThat(log).as("the log was captured, with what the endpoints are called with")
                .contains("/api/tasks/file")
                .contains("A file of tasks was checked by member " + wedding.annaId + ": 2 new, 0 changed, 0 unchanged, 0 refused")
                .contains("A file of tasks was applied by member " + wedding.annaId + ": 2 new, 0 changed, 0 unchanged, 0 refused")
                .contains("A file of tasks was applied by member " + wedding.piotrId + ": 0 new, 0 changed, 2 unchanged, 0 refused")
                .contains("A file of tasks was checked by member " + wedding.annaId + ": 0 new, 0 changed, 0 unchanged, 1 refused");
        assertThat(log)
                .doesNotContain("zq-key")
                .doesNotContain("marquee")
                .doesNotContain("Marquee")
                .doesNotContain("quokka")
                .doesNotContain("Quirky")
                .doesNotContain("walrus")
                .doesNotContain("Walrus")
                .doesNotContain("31415")
                .doesNotContain("3141592");
    }
}
