package io.github.trufelek124.weddingplanner.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Change;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.FileProblem;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Outcome;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Report;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.RowProblem;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.RowReport;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Fields;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Items;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.NotJson;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Value;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The web framework prints what an endpoint is called with and what it answers through toString() once its logging is
 * at TRACE. What holds the content of a file of tasks must never give it away that way.
 */
class TaskFileRecordsHideTheirContentTest {

    private static final String SECRET = "Quokka Marquee 31415.92";

    @Test
    void theAnswerAndEverythingInItHidesWhatTheFileSaid() {
        RowReport row = new RowReport(1, SECRET, SECRET, Outcome.CHANGED, List.of(new Change("title", SECRET, SECRET)),
                List.of(new RowProblem(SECRET, SECRET)));
        Report report = new Report(true, true, 1, 1, 1, 1, List.of(SECRET), List.of(new FileProblem(SECRET)), List.of(row));

        for (Object shown : List.of(report, row, new Change("title", SECRET, SECRET), new RowProblem(SECRET, SECRET), new FileProblem(SECRET))) {
            assertThat(shown.toString()).as(shown.getClass().getSimpleName()).doesNotContain("Quokka").doesNotContain("31415");
        }
    }

    @Test
    void theReadFileAndItsRowsHideWhatTheFileSaid() throws NotJson {
        String text = "{\"format\":\"wedding-planner-tasks\",\"version\":1,\"tasks\":[{\"key\":\"" + SECRET + "\",\"title\":\"" + SECRET
                + "\",\"notes\":\"" + SECRET + "\",\"amount\":{\"value\":31415.92,\"currency\":\"PLN\"}}]}";
        Value tree = TaskFileJson.read(text);
        var reading = new TaskFileReader(Validation.buildDefaultValidatorFactory().getValidator(), "PLN", 2).read(text);

        assertThat(tree).isInstanceOf(Fields.class);
        assertThat(tree.toString()).doesNotContain("Quokka").doesNotContain("31415");
        assertThat(((Fields) tree).fields().get("tasks")).isInstanceOf(Items.class);
        assertThat(((Fields) tree).fields().get("tasks").toString()).doesNotContain("Quokka");
        assertThat(reading.toString()).doesNotContain("Quokka");
        assertThat(reading.rows().get(0).toString()).doesNotContain("Quokka").doesNotContain("31415");
        assertThat(reading.rows().get(0).notes().toString()).doesNotContain("Quokka");
        assertThat(reading.rows().get(0).amount().toString()).doesNotContain("31415");
    }
}
