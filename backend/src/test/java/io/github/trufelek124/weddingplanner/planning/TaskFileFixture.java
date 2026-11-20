package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the tests of the file of tasks share: building a file, calling both paths, reading the report. */
final class TaskFileFixture {

    static final Path FORMATS = Path.of("..", "web", "public", "formats");

    final TestWedding wedding;
    final TestHttp http;

    TaskFileFixture(TestWedding wedding) {
        this.wedding = wedding;
        this.http = wedding.http;
    }

    /** JSON written with single quotes, so that a test reads well. */
    static String j(String quoted) {
        return quoted.replace('\'', '"');
    }

    /** A file of tasks with these rows, each one JSON written with single quotes. */
    static String file(String... rows) {
        return j("{'format':'wedding-planner-tasks','version':1,'tasks':[" + String.join(",", rows) + "]}");
    }

    static String row(String key, String title, String... more) {
        return "{'key':'" + key + "','title':'" + title + "'" + (more.length == 0 ? "" : "," + String.join(",", more)) + "}";
    }

    static String read(String name) throws IOException {
        return Files.readString(FORMATS.resolve(name));
    }

    HttpResponse<String> check(String token, String body) throws Exception {
        return http.postJsonAs(token, "/api/tasks/file/check", body);
    }

    HttpResponse<String> apply(String token, String body) throws Exception {
        return http.postJsonAs(token, "/api/tasks/file", body);
    }

    Map<String, Object> checked(String body) throws Exception {
        HttpResponse<String> response = check(wedding.anna, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    Map<String, Object> applied(String body) throws Exception {
        HttpResponse<String> response = apply(wedding.anna, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("rows");
    }

    static Map<String, Object> rowOf(Map<String, Object> report, int number) {
        return rows(report).get(number - 1);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        return (List<String>) value;
    }

    /** "field: message" of every problem of a row. */
    static List<String> problemsOf(Map<String, Object> row) {
        List<String> said = new ArrayList<>();
        for (Map<String, Object> problem : list(row.get("problems"))) {
            said.add(problem.get("field") + ": " + problem.get("message"));
        }
        return said;
    }

    /** "field: from -> to" of every change of a row. */
    static List<String> changesOf(Map<String, Object> row) {
        List<String> said = new ArrayList<>();
        for (Map<String, Object> change : list(row.get("changes"))) {
            said.add(change.get("field") + ": " + change.get("from") + " -> " + change.get("to"));
        }
        return said;
    }

    static List<String> fileProblems(Map<String, Object> report) {
        return list(report.get("problems")).stream().map(problem -> (String) problem.get("message")).toList();
    }

    /** The tasks of the planner, by title. */
    Map<String, Map<String, Object>> tasks() throws Exception {
        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        for (Map<String, Object> task : TestHttp.jsonList(http.getAs(wedding.anna, "/api/entries?kind=TASK"))) {
            byTitle.put((String) task.get("title"), task);
        }
        return byTitle;
    }

    Map<String, Object> task(String title) throws Exception {
        Map<String, Object> task = tasks().get(title);
        assertThat(task).as("the task titled " + title).isNotNull();
        return task;
    }

    List<Map<String, Object>> categories() throws Exception {
        return TestHttp.jsonList(http.getAs(wedding.anna, "/api/categories"));
    }

    String categoryNamed(String name) throws Exception {
        return categories().stream().filter(c -> name.equals(c.get("name"))).map(c -> (String) c.get("id")).findFirst().orElse(null);
    }

    String vendor(String name) throws Exception {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", "");
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        HttpResponse<String> response = http.postJsonAs(wedding.anna, "/api/vendors", TestHttp.jsonOf(vendor));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) TestHttp.json(response).get("id");
    }
}
