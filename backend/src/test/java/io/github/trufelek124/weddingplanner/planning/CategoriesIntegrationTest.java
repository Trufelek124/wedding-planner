package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

class CategoriesIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    @Test
    void aNewWeddingStartsWithTheUsualCategoriesOnce() throws Exception {
        List<String> first = names(http.getAs(anna, "/api/categories"));
        List<String> second = names(http.getAs(piotr, "/api/categories"));

        assertThat(first).startsWith("Venue", "Food and drink", "Attire").endsWith("Other").hasSize(12);
        assertThat(second).as("asking again adds nothing").isEqualTo(first);
    }

    @Test
    void categoriesThatWereDeletedDoNotComeBack() throws Exception {
        for (Map<String, Object> category : TestHttp.jsonList(http.getAs(anna, "/api/categories"))) {
            assertThat(http.deleteAs(anna, "/api/categories/" + category.get("id")).statusCode()).isEqualTo(204);
        }

        assertThat(names(http.getAs(piotr, "/api/categories"))).isEmpty();
    }

    @Test
    void aCategoryCanBeAddedRenamedAndDeleted() throws Exception {
        http.getAs(anna, "/api/categories");

        HttpResponse<String> added = http.postJsonAs(anna, "/api/categories", "{\"name\":\"  Accommodation \"}");
        assertThat(added.statusCode()).isEqualTo(201);
        String id = (String) TestHttp.json(added).get("id");
        assertThat(names(http.getAs(piotr, "/api/categories"))).as("new ones go to the end").endsWith("Other", "Accommodation");

        HttpResponse<String> renamed = http.putJsonAs(piotr, "/api/categories/" + id, "{\"name\":\"Hotels\"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(names(http.getAs(anna, "/api/categories"))).contains("Hotels").doesNotContain("Accommodation");

        assertThat(http.deleteAs(anna, "/api/categories/" + id).statusCode()).isEqualTo(204);
        assertThat(names(http.getAs(anna, "/api/categories"))).doesNotContain("Hotels").hasSize(12);
    }

    @Test
    void twoCategoriesCannotHaveTheSameNameHoweverCapitalised() throws Exception {
        List<Map<String, Object>> categories = TestHttp.jsonList(http.getAs(anna, "/api/categories"));
        String attire = (String) categories.stream().filter(c -> "Attire".equals(c.get("name"))).findFirst().orElseThrow().get("id");

        HttpResponse<String> duplicate = http.postJsonAs(anna, "/api/categories", "{\"name\":\"VENUE\"}");
        HttpResponse<String> renameOntoAnother = http.putJsonAs(anna, "/api/categories/" + attire, "{\"name\":\"venue\"}");

        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldProblems(duplicate)).containsExactly("name");
        assertThat(renameOntoAnother.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldProblems(renameOntoAnother)).containsExactly("name");
        assertThat(names(http.getAs(anna, "/api/categories"))).hasSize(12).contains("Attire");
        // Changing only the capitals of its own name is not a clash.
        assertThat(http.putJsonAs(anna, "/api/categories/" + attire, "{\"name\":\"ATTIRE\"}").statusCode()).isEqualTo(200);
    }

    @Test
    void aNameIsNeeded() throws Exception {
        assertThat(TestHttp.fieldProblems(http.postJsonAs(anna, "/api/categories", "{\"name\":\" \"}"))).containsExactly("name");
        assertThat(TestHttp.fieldProblems(http.postJsonAs(anna, "/api/categories", "{\"name\":\"" + "x".repeat(61) + "\"}")))
                .containsExactly("name");
    }

    @Test
    void anEntryOutlivesItsCategory() throws Exception {
        String id = (String) TestHttp.json(http.postJsonAs(anna, "/api/categories", "{\"name\":\"Fireworks\"}")).get("id");
        String entry = "{\"kind\":\"TASK\",\"title\":\"Ask about fireworks\",\"notes\":\"\",\"categoryId\":\"" + id + "\","
                + "\"startDate\":null,\"dueDate\":null,\"status\":\"NOT_STARTED\",\"assigneeIds\":[],\"priorityMode\":\"AUTO\","
                + "\"priority\":null,\"parentId\":null}";
        String entryId = (String) TestHttp.json(http.postJsonAs(anna, "/api/entries", entry)).get("id");

        assertThat(http.deleteAs(anna, "/api/categories/" + id).statusCode()).isEqualTo(204);

        assertThat(TestHttp.json(http.getAs(anna, "/api/entries/" + entryId)))
                .containsEntry("title", "Ask about fireworks")
                .containsEntry("categoryId", null);
    }

    @Test
    void aCategoryThatDoesNotExistIsNotFound() throws Exception {
        String nowhere = "/api/categories/" + UUID.randomUUID();

        assertThat(http.putJsonAs(anna, nowhere, "{\"name\":\"X\"}").statusCode()).isEqualTo(404);
        assertThat(http.deleteAs(anna, nowhere).statusCode()).isEqualTo(404);
    }

    private static List<String> names(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(category -> (String) category.get("name")).toList();
    }
}
