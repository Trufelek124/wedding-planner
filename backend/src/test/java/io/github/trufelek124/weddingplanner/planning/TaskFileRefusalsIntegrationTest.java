package io.github.trufelek124.weddingplanner.planning;

import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.file;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.fileProblems;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.j;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.problemsOf;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.row;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.rowOf;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.rows;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** What a file of tasks is refused for (S06 section 5), and what refusing leaves behind: nothing. */
class TaskFileRefusalsIntegrationTest extends PostgresIntegrationTest {

    private static final String NOT_THIS_FORMAT = "This is not a file of tasks in this format. ";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TaskFileFixture files;
    TestHttp http;
    String anna;

    @BeforeEach
    void aFreshWedding() throws Exception {
        files = new TaskFileFixture(TestWedding.fresh(jdbc, port));
        http = files.http;
        anna = files.wedding.anna;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ---------------------------------------------------------------- the file as a whole

    static Stream<Arguments> filesThatAreNotThisFormat() {
        return Stream.of(
                Arguments.of("not JSON at all", NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8."),
                Arguments.of("", NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8."),
                Arguments.of("{'format':'wedding-planner-tasks'", NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8."),
                Arguments.of("{} {}", NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8."),
                Arguments.of("[{'key':'a','title':'A'}]",
                        NOT_THIS_FORMAT + "The file has to be one JSON object with \"format\", \"version\" and \"tasks\"."),
                Arguments.of("'text'", NOT_THIS_FORMAT + "The file has to be one JSON object with \"format\", \"version\" and \"tasks\"."),
                Arguments.of("null", NOT_THIS_FORMAT + "The file has to be one JSON object with \"format\", \"version\" and \"tasks\"."),
                Arguments.of("{'format':'something-else','version':1,'tasks':[{'key':'a','title':'A'}]}",
                        NOT_THIS_FORMAT + "\"format\" has to be \"wedding-planner-tasks\"."),
                Arguments.of("{'version':1,'tasks':[{'key':'a','title':'A'}]}", NOT_THIS_FORMAT + "\"format\" has to be \"wedding-planner-tasks\"."),
                Arguments.of("{'format':'wedding-planner-tasks','version':2,'tasks':[{'key':'a','title':'A'}]}",
                        "This planner reads version 1 of the format only. \"version\" has to be 1."),
                Arguments.of("{'format':'wedding-planner-tasks','version':'1','tasks':[{'key':'a','title':'A'}]}",
                        "This planner reads version 1 of the format only. \"version\" has to be 1."),
                Arguments.of("{'format':'wedding-planner-tasks','version':1}", "The file has no list of tasks. \"tasks\" has to be a list."),
                Arguments.of("{'format':'wedding-planner-tasks','version':1,'tasks':{'key':'a'}}",
                        "The file has no list of tasks. \"tasks\" has to be a list."),
                Arguments.of("{'format':'wedding-planner-tasks','version':1,'tasks':[]}", "The file has no tasks. Add at least one."));
    }

    @ParameterizedTest
    @MethodSource("filesThatAreNotThisFormat")
    void aFileThatIsNotThisFormatHasNoRowsAndSaysWhyInWords(String body, String said) throws Exception {
        Map<String, Object> report = files.checked(j(body));

        assertThat(report).containsEntry("canBeApplied", false).containsEntry("applied", false).containsEntry("added", 0)
                .containsEntry("refused", 0);
        assertThat(rows(report)).isEmpty();
        assertThat(fileProblems(report)).containsExactly(said);
        // Applying it is the refusal, with nothing changed.
        HttpResponse<String> refused = files.apply(anna, j(body));
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(count("entry")).isZero();
    }

    @Test
    void aFileWithMoreThanAThousandRowsIsRefusedAsAWholeAndAThousandIsTaken() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 1_001; i++) {
            many.add(row("k" + i, "Task " + i));
        }

        Map<String, Object> tooMany = files.checked(file(many.toArray(String[]::new)));
        assertThat(fileProblems(tooMany)).containsExactly("The file has more than 1,000 tasks. Split it into several files.");
        assertThat(rows(tooMany)).isEmpty();

        Map<String, Object> thousand = files.applied(file(many.subList(0, 1_000).toArray(String[]::new)));
        assertThat(thousand).containsEntry("added", 1_000);
        assertThat(count("entry")).isEqualTo(1_000);
    }

    @Test
    void aFieldAtTheTopOfTheFileThatTheFormatDoesNotHaveIsRefusedByName() throws Exception {
        Map<String, Object> report = files.checked(j(
                "{'format':'wedding-planner-tasks','version':1,'author':'me','tasks':[{'key':'a','title':'A'}]}"));

        assertThat(fileProblems(report)).containsExactly(
                "The file has no field called \"author\". The format has \"format\", \"version\" and \"tasks\".");
        assertThat(report).containsEntry("canBeApplied", false).containsEntry("added", 1);
        assertThat(files.apply(anna, j("{'format':'wedding-planner-tasks','version':1,'author':'me','tasks':[{'key':'a','title':'A'}]}"))
                        .statusCode())
                .isEqualTo(422);
        assertThat(count("entry")).isZero();
    }

    @Test
    void theSameNameTwiceInOneObjectIsRefusedAtTheTopAndInARow() throws Exception {
        Map<String, Object> top = files.checked(j(
                "{'format':'wedding-planner-tasks','version':1,'tasks':[{'key':'a','title':'A'}],'tasks':[]}"));
        assertThat(fileProblems(top)).containsExactly("The name \"tasks\" is used twice at the top of the file.");

        Map<String, Object> inARow = files.checked(j(
                "{'format':'wedding-planner-tasks','version':1,'tasks':[{'key':'a','title':'A','title':'B'}]}"));
        assertThat(problemsOf(rowOf(inARow, 1))).containsExactly("title: The name \"title\" is used twice in this row.");
        assertThat(inARow).containsEntry("canBeApplied", false);
    }

    // ---------------------------------------------------------------- the size

    private HttpResponse<String> post(String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + anna)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A file of exactly this many bytes that is well formed apart from its size. */
    private static byte[] fileOfSize(int bytes) {
        String head = j("{'format':'wedding-planner-tasks','version':1,'tasks':[{'key':'a','title':'A','notes':'");
        String tail = j("'}]}");
        int padding = bytes - head.length() - tail.length();
        return (head + "n".repeat(padding) + tail).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aBodyJustOverOneMegabyteIsTooLargeOnBothPathsAndOneJustUnderIsRead() throws Exception {
        for (String path : List.of("/api/tasks/file/check", "/api/tasks/file")) {
            HttpResponse<String> tooLarge = post(path, fileOfSize(1_048_577));
            assertThat(tooLarge.statusCode()).as(path).isEqualTo(413);
            assertThat(TestHttp.json(tooLarge)).containsEntry("type", "urn:wedding-planner:problem:upload-too-large");
        }
        assertThat(count("entry")).isZero();

        // Exactly the limit is taken as far as size goes; the notes are too long for a task, which is said in words.
        HttpResponse<String> atTheLimit = post("/api/tasks/file/check", fileOfSize(1_048_576));
        assertThat(atTheLimit.statusCode()).as(atTheLimit.body()).isEqualTo(200);
        Map<String, Object> report = TestHttp.json(atTheLimit);
        assertThat(problemsOf(rowOf(report, 1))).containsExactly("notes: Use at most 10,000 characters.");
        assertThat(post("/api/tasks/file/check", fileOfSize(1_048_000)).statusCode()).isEqualTo(200);
    }

    @Test
    void aBodyThatIsNotUtf8IsNotAFileOfTasks() throws Exception {
        byte[] broken = {'{', '"', (byte) 0xff, (byte) 0xfe, '"', '}'};

        HttpResponse<String> response = post("/api/tasks/file/check", broken);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fileProblems(TestHttp.json(response))).containsExactly(NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8.");
    }

    @Test
    void aFileThatStartsWithAByteOrderMarkIsRead() throws Exception {
        byte[] body = ("﻿" + file(row("a", "A"))).getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = post("/api/tasks/file/check", body);

        assertThat(TestHttp.json(response)).containsEntry("canBeApplied", true);
    }

    // ---------------------------------------------------------------- a row (S06 section 5)

    static Stream<Arguments> rowsThatAreRefused() {
        return Stream.of(
                Arguments.of("{'title':'A'}", "key: Give the task a key: any text that is yours, 1 to 100 characters."),
                Arguments.of("{'key':'a'}", "title: Enter a title."),
                Arguments.of("{'key':'a','title':''}", "title: Enter a title."),
                Arguments.of("{'key':'a','title':5}", "title: The title has to be text."),
                Arguments.of("{'key':5,'title':'A'}", "key: The key has to be text."),
                Arguments.of("{'key':'','title':'A'}", "key: Use 1 to 100 characters for the key, with no line break and no space at its beginning or end."),
                Arguments.of("{'key':' a','title':'A'}", "key: Use 1 to 100 characters for the key, with no line break and no space at its beginning or end."),
                Arguments.of("{'key':'a ','title':'A'}", "key: Use 1 to 100 characters for the key, with no line break and no space at its beginning or end."),
                Arguments.of("{'key':'a\\nb','title':'A'}", "key: Use 1 to 100 characters for the key, with no line break and no space at its beginning or end."),
                Arguments.of("{'key':'" + "k".repeat(101) + "','title':'A'}",
                        "key: Use 1 to 100 characters for the key, with no line break and no space at its beginning or end."),
                Arguments.of("{'key':'a','title':'A','duedate':'2027-01-01'}",
                        "duedate: The format has no field called \"duedate\". The fields are key, title, notes, dueDate, status, priority, category, vendor, amount, paymentKind, paidOn."),
                Arguments.of("{'key':'a','title':'A','notes':null}", "notes: The notes have to be text. Use \"\" to empty them."),
                Arguments.of("{'key':'a','title':'A','notes':7}", "notes: The notes have to be text. Use \"\" to empty them."),
                Arguments.of("{'key':'a','title':'A','dueDate':'2027-02-30'}",
                        "dueDate: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none."),
                Arguments.of("{'key':'a','title':'A','dueDate':'1 March 2027'}",
                        "dueDate: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none."),
                Arguments.of("{'key':'a','title':'A','dueDate':20270301}",
                        "dueDate: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none."),
                Arguments.of("{'key':'a','title':'A','paidOn':'tomorrow'}",
                        "paidOn: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none."),
                Arguments.of("{'key':'a','title':'A','status':'FINISHED'}", "status: Use one of NOT_STARTED, IN_PROGRESS, DONE or DROPPED."),
                Arguments.of("{'key':'a','title':'A','status':null}", "status: Use one of NOT_STARTED, IN_PROGRESS, DONE or DROPPED."),
                Arguments.of("{'key':'a','title':'A','status':'done'}", "status: Use one of NOT_STARTED, IN_PROGRESS, DONE or DROPPED."),
                Arguments.of("{'key':'a','title':'A','priority':'URGENT'}",
                        "priority: Use one of CRITICAL, HIGH, NORMAL or LOW, or null to let the planner set it."),
                Arguments.of("{'key':'a','title':'A','paymentKind':'ADVANCE'}",
                        "paymentKind: Use one of DEPOSIT, INSTALMENT or FINAL, or null for none."),
                Arguments.of("{'key':'a','title':'A','category':7}", "category: The category has to be the name of a category, or null for none."),
                Arguments.of("{'key':'a','title':'A','vendor':7}", "vendor: The vendor has to be the name of a vendor the planner has, or null for none."),
                Arguments.of("{'key':'a','title':'A','vendor':''}", "vendor: The vendor has to be the name of a vendor the planner has, or null for none."),
                Arguments.of("{'key':'a','title':'A','vendor':'" + "v".repeat(201) + "'}", "vendor: Use at most 200 characters."),
                Arguments.of("\"just a text\"", "null: This row has to be an object with at least a key and a title."),
                Arguments.of("[1,2]", "null: This row has to be an object with at least a key and a title."));
    }

    @ParameterizedTest
    @MethodSource("rowsThatAreRefused")
    void aRowThatBreaksTheFormatIsRefusedWithItsNumberItsFieldAndWhatIsExpected(String bad, String said) throws Exception {
        Map<String, Object> report = files.checked(j("{'format':'wedding-planner-tasks','version':1,'tasks':["
                + "{'key':'good','title':'Good'}," + bad + "]}"));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "NEW");
        Map<String, Object> refused = rowOf(report, 2);
        assertThat(refused).containsEntry("outcome", "REFUSED").containsEntry("row", 2);
        assertThat(problemsOf(refused)).containsExactly(said);
        assertThat(report).containsEntry("refused", 1).containsEntry("added", 1).containsEntry("canBeApplied", false);

        // Applying: the good row waits too. Nothing is made.
        HttpResponse<String> applied = files.apply(anna, j("{'format':'wedding-planner-tasks','version':1,'tasks':["
                + "{'key':'good','title':'Good'}," + bad + "]}"));
        assertThat(applied.statusCode()).isEqualTo(422);
        assertThat(count("entry")).isZero();
    }

    @Test
    void aKeyUsedTwiceIsAProblemOfEachRowThatUsesIt() throws Exception {
        Map<String, Object> report = files.checked(file(row("same", "First"), row("other", "Second"), row("same", "Third")));

        String said = "key: Another row of the file has this key too. Every task needs its own key.";
        assertThat(problemsOf(rowOf(report, 1))).containsExactly(said);
        assertThat(rowOf(report, 2)).containsEntry("outcome", "NEW");
        assertThat(problemsOf(rowOf(report, 3))).containsExactly(said);
        assertThat(report).containsEntry("refused", 2);
    }

    @Test
    void theKeyAndTheTitleOfARowThatCouldNotBeReadAreNull() throws Exception {
        Map<String, Object> report = files.checked(j(
                "{'format':'wedding-planner-tasks','version':1,'tasks':[{'key':5,'title':{'a':1}},{'key':'k'}]}"));

        assertThat(rowOf(report, 1)).containsEntry("key", null).containsEntry("title", null);
        assertThat(rowOf(report, 2)).containsEntry("key", "k").containsEntry("title", null);
    }

    @Test
    void severalWrongRowsAreAllListedAtOnceWithSeveralProblemsInAFieldOrder() throws Exception {
        Map<String, Object> report = files.checked(file(
                row("a", "A", "'dueDate':'nope'", "'status':'X'"),
                row("b", "B"),
                row("c", "C", "'amount':{'value':'1','currency':'EUR'}", "'category':'" + "c".repeat(61) + "'"),
                "{'title':'No key','vendor':'Nobody'}"));

        assertThat(problemsOf(rowOf(report, 1))).containsExactly(
                "dueDate: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none.",
                "status: Use one of NOT_STARTED, IN_PROGRESS, DONE or DROPPED.");
        assertThat(rowOf(report, 2)).containsEntry("outcome", "NEW");
        assertThat(problemsOf(rowOf(report, 3))).containsExactly(
                "category: Use at most 60 characters.",
                "amount: The wedding's currency is PLN. An amount in another currency is refused: nothing is converted.");
        assertThat(problemsOf(rowOf(report, 4))).hasSize(2);
        assertThat(problemsOf(rowOf(report, 4)).get(0)).startsWith("key: Give the task a key");
        assertThat(problemsOf(rowOf(report, 4)).get(1)).startsWith("vendor: The planner does not have exactly one vendor");
        assertThat(report).containsEntry("refused", 3).containsEntry("added", 1);
    }

    // ---------------------------------------------------------------- applying a file with a problem

    @SuppressWarnings("unchecked")
    @Test
    void applyingAFileWithAProblemIsTheTaskFileRefusedProblemWithTheFieldsAndChangesNothing() throws Exception {
        files.categories();
        files.applied(file(row("old", "Old task")));
        int categories = files.categories().size();

        HttpResponse<String> response = files.apply(anna, file(
                row("a", "Good one", "'category':'A brand new category'"),
                row("old", "Old task, renamed"),
                row("b", "B", "'dueDate':'nope'"),
                row("c", "C", "'paymentKind':'FINAL'")));

        assertThat(response.statusCode()).isEqualTo(422);
        Map<String, Object> problem = TestHttp.json(response);
        assertThat(problem).containsEntry("type", "urn:wedding-planner:problem:task-file-refused")
                .containsEntry("status", 422);
        assertThat(TestHttp.fieldMessages(response)).containsExactly(
                "tasks[3].dueDate: Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none.",
                "tasks[4].paymentKind: The kind of payment needs an amount. Enter one, or leave the kind empty.");
        assertThat(problem.get("detail")).isEqualTo(
                "The file cannot be taken as it is, so nothing was changed. Check it again to see what is wrong.");
        // Nothing: no task, no change, no category.
        assertThat(files.tasks()).containsOnlyKeys("Old task");
        assertThat(files.task("Old task")).containsEntry("version", 0);
        assertThat(files.categories()).hasSize(categories);
        assertThat(files.categoryNamed("A brand new category")).isNull();
    }

    @Test
    void oneWrongRowAmongGoodOnesWritesNoTaskAndNoCategoryAtAll() throws Exception {
        assertThat(count("category")).isZero();

        HttpResponse<String> response = files.apply(anna, file(
                row("a", "A", "'category':'Brand new'"), row("b", "B", "'category':'Venue'"), row("c", "C", "'priority':'URGENT'")));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(count("entry")).isZero();
        // Not even the starter list that a category lookup would have started.
        assertThat(count("category")).isZero();
        assertThat(count("planning_setup")).isZero();
    }

    @Test
    void theRefusalNamesAtMostAHundredProblems() throws Exception {
        List<String> bad = new ArrayList<>();
        for (int i = 1; i <= 150; i++) {
            bad.add(row("k" + i, "T", "'status':'X'"));
        }

        HttpResponse<String> response = files.apply(anna, file(bad.toArray(String[]::new)));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(TestHttp.fieldProblems(response)).hasSize(100).first().isEqualTo("tasks[1].status");
        assertThat(files.checked(file(bad.toArray(String[]::new)))).containsEntry("refused", 150);
    }

    @Test
    void aFileThatIsNotThisFormatIsRefusedWithTheFileAsItsField() throws Exception {
        HttpResponse<String> response = files.apply(anna, "nonsense");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(TestHttp.fieldProblems(response)).containsExactly("file");
    }

    @Test
    void aBodyThatIsNotJsonAtAllEndsInWordsNotInTheFrameworksRefusal() throws Exception {
        HttpResponse<String> response = files.check(anna, "<html>");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fileProblems(TestHttp.json(response))).containsExactly(NOT_THIS_FORMAT + "The file has to be JSON text in UTF-8.");
    }

    @Test
    void aCheckAlwaysAnswers200EvenForARowThatCannotBeTakenAndNeverChangesAnything() throws Exception {
        files.categories();
        int categories = files.categories().size();

        HttpResponse<String> response = files.check(anna, file(row("a", "A", "'category':'Brand new'"), row("b", "B", "'status':'X'")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(count("entry")).isZero();
        assertThat(files.categories()).hasSize(categories);
    }

    @Test
    void neitherPathAnswersAnyoneWithoutASignIn() throws Exception {
        for (String path : List.of("/api/tasks/file/check", "/api/tasks/file")) {
            HttpResponse<String> response = http.postJson(path, file(row("a", "A")));
            assertThat(response.statusCode()).as(path).isEqualTo(401);
        }
        assertThat(count("entry")).isZero();
    }
}
