package io.github.trufelek124.weddingplanner.inspirations;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The canvas (S08): where an idea arrives, and moving it. */
class CanvasIntegrationTest extends PostgresIntegrationTest {

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

    // ------------------------------------------------------------ arriving

    @Test
    void theFirstIdeaLiesAtTheOriginAndTheNextOnesFollowInARowOfSix() throws Exception {
        List<String> places = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            places.add(place(added(link("idea-" + i, null))));
        }

        assertThat(places).containsExactly("0,0", "220,0", "440,0", "660,0", "880,0", "1100,0", "0,260", "220,260");
    }

    @Test
    void anIdeaWithAnIdOfItsOwnChoosingArrivesTheSameWay() throws Exception {
        added(link("first", null));
        String id = UUID.randomUUID().toString();

        Map<String, Object> shared = added(link("shared", id));

        assertThat(shared).containsEntry("id", id);
        assertThat(place(shared)).isEqualTo("220,0");
        assertThat(shared.get("placedAt")).isEqualTo(shared.get("createdAt"));
    }

    @Test
    void theSameRequestSentAgainIsAnsweredWithWhatIsThereAndMovesNothing() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> request = link("twice", id);
        Map<String, Object> first = added(request);
        added(link("another", null));
        assertThat(move(piotr, id, 7_000, 8_000).statusCode()).isEqualTo(200);

        HttpResponse<String> again = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));

        assertThat(again.statusCode()).as(again.body()).isIn(200, 201);
        assertThat(place(TestHttp.json(again))).isEqualTo("7000,8000");
        assertThat(first).containsEntry("x", 0);
        assertThat(count()).isEqualTo(2);
        assertThat(place(read(id))).as("still where it was moved to").isEqualTo("7000,8000");
    }

    @Test
    void aNewOneArrivesBesideTheLowestAfterCardsWereMovedFarAwayAndNeverOnAnother() throws Exception {
        Map<String, Object> a = added(link("a", null));
        Map<String, Object> b = added(link("b", null));
        // b is dragged far below: it is now the lowest.
        move(anna, id(b), 300, 5_000);
        assertThat(place(added(link("c", null)))).as("beside the lowest").isEqualTo("520,5000");

        // a is dragged below everything: it is the lowest now, and the next one lies beside it.
        move(anna, id(a), 740, 5_100);
        assertThat(place(added(link("d", null)))).isEqualTo("960,5100");
    }

    @Test
    void aNewRowBeginsWhereTheSlotBesideTheLowestIsTaken() throws Exception {
        Map<String, Object> a = added(link("a", null));
        Map<String, Object> b = added(link("b", null));
        Map<String, Object> c = added(link("c", null));
        // The lowest is c at (880, 400). Beside it would be (1100, 400): a card moved there blocks it.
        move(anna, id(c), 880, 400);
        move(anna, id(a), 1_100, 300);
        move(anna, id(b), 0, 0);

        // a is now the lowest? No: c (y 400) is lower. Beside c: 1100, 400 lies within a slot of a (1100, 300).
        assertThat(place(added(link("d", null)))).isEqualTo("0,660");
    }

    @Test
    void twoIdeasThatArriveAtTheSameMomentDoNotGetTheSamePlace() throws Exception {
        int arrivals = 8;
        ExecutorService pool = Executors.newFixedThreadPool(arrivals);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Map<String, Object>>> results = new ArrayList<>();
        for (int i = 0; i < arrivals; i++) {
            Map<String, Object> request = link("together-" + i, null);
            Callable<Map<String, Object>> add = () -> {
                go.await();
                return added(request);
            };
            results.add(pool.submit(add));
        }
        go.countDown();
        Set<String> places = new HashSet<>();
        for (Future<Map<String, Object>> result : results) {
            places.add(place(result.get()));
        }
        pool.shutdown();

        assertThat(places).hasSize(arrivals);
        assertThat(places).contains("0,0");
    }

    @Test
    void removingAnIdeaFreesItsPlace() throws Exception {
        added(link("a", null));
        Map<String, Object> b = added(link("b", null));
        assertThat(http.deleteAs(anna, "/api/inspirations/" + id(b)).statusCode()).isEqualTo(204);

        assertThat(place(added(link("c", null)))).isEqualTo("220,0");
    }

    @Test
    void changingAnIdeaThroughItsFormKeepsItsPlace() throws Exception {
        Map<String, Object> first = added(link("form", null));
        String id = id(first);
        move(piotr, id, -400, 1_234);
        Map<String, Object> current = read(id);

        Map<String, Object> change = new HashMap<>(link("renamed", null));
        change.remove("id");
        change.put("version", current.get("version"));
        HttpResponse<String> changed = http.putJsonAs(anna, "/api/inspirations/" + id, TestHttp.jsonOf(change));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(TestHttp.json(changed)).containsEntry("version", 1).containsEntry("x", -400).containsEntry("y", 1_234);
        assertThat(TestHttp.json(changed).get("placedAt")).isEqualTo(current.get("placedAt"));
    }

    @Test
    void aPlaceCannotBeChosenWhenAdding() throws Exception {
        Map<String, Object> request = link("sneaky", null);
        request.put("x", 5_000);
        request.put("y", 5_000);

        assertThat(place(added(request))).as("the server places it; what was sent is ignored").isEqualTo("0,0");
    }

    // ------------------------------------------------------------ moving

    @Test
    void eitherOwnerMovesACardAndTheOtherReadsTheNewPlace() throws Exception {
        String id = id(added(link("move me", null)));
        TestClock.advance(Duration.ofMinutes(5));

        HttpResponse<String> moved = move(piotr, id, -250, 640);

        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        assertThat(TestHttp.json(moved)).containsEntry("id", id).containsEntry("x", -250).containsEntry("y", 640);
        assertThat(place(read(id))).isEqualTo("-250,640");
        HttpResponse<String> back = move(anna, id, 10, 20);
        assertThat(back.statusCode()).isEqualTo(200);
        HttpResponse<String> piotrReads = http.getAs(piotr, "/api/inspirations/" + id);
        assertThat(place(TestHttp.json(piotrReads))).isEqualTo("10,20");
    }

    @Test
    void movingChangesNeitherTheVersionNorWhenItWasChangedButMovesTheMomentItWasPutThere() throws Exception {
        String id = id(added(link("quiet", null)));
        Map<String, Object> before = read(id);
        TestClock.advance(Duration.ofMinutes(5));

        Map<String, Object> after = TestHttp.json(move(piotr, id, 30, 40));

        assertThat(after).containsEntry("version", before.get("version")).containsEntry("updatedAt", before.get("updatedAt"));
        assertThat(after.get("placedAt")).isNotEqualTo(before.get("placedAt"));
        assertThat(Instant.parse((String) after.get("placedAt")))
                .isAfter(Instant.parse((String) before.get("placedAt")));
    }

    @Test
    void theLaterMoveWinsWithNoQuestionOfAVersion() throws Exception {
        String id = id(added(link("contested", null)));

        assertThat(move(anna, id, 1, 1).statusCode()).isEqualTo(200);
        assertThat(move(piotr, id, 2, 2).statusCode()).isEqualTo(200);
        assertThat(move(anna, id, 3, 3).statusCode()).isEqualTo(200);

        assertThat(place(read(id))).isEqualTo("3,3");
    }

    @Test
    void theLimitsAreAllowedAndAnythingBeyondThemIsRefusedWithWordsBesideTheField() throws Exception {
        String id = id(added(link("edge", null)));

        assertThat(move(anna, id, -100_000, 100_000).statusCode()).isEqualTo(200);
        assertThat(place(read(id))).isEqualTo("-100000,100000");

        HttpResponse<String> beyond = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{\"x\":100001,\"y\":-100001}");
        assertThat(beyond.statusCode()).isEqualTo(400);
        assertThat(fieldProblems(beyond)).containsExactlyInAnyOrder(
                "x: Use a number from -100,000 to 100,000 for x.", "y: Use a number from -100,000 to 100,000 for y.");
        assertThat(place(read(id))).as("nothing moved").isEqualTo("-100000,100000");
    }

    @Test
    void aMissingNumberIsRefusedWithWordsBesideTheField() throws Exception {
        String id = id(added(link("missing", null)));

        HttpResponse<String> noY = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{\"x\":5}");
        HttpResponse<String> nothing = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{}");
        HttpResponse<String> nulls = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{\"x\":null,\"y\":null}");

        assertThat(noY.statusCode()).isEqualTo(400);
        assertThat(fieldProblems(noY)).containsExactly("y: Enter a place for y.");
        assertThat(fieldProblems(nothing)).containsExactlyInAnyOrder("x: Enter a place for x.", "y: Enter a place for y.");
        assertThat(fieldProblems(nulls)).containsExactlyInAnyOrder("x: Enter a place for x.", "y: Enter a place for y.");
        assertThat(place(read(id))).isEqualTo("0,0");
    }

    @Test
    void aNumberThatIsNotWholeIsRefusedNotRounded() throws Exception {
        String id = id(added(link("fraction", null)));

        HttpResponse<String> fraction = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{\"x\":12.5,\"y\":3}");
        HttpResponse<String> wholeWithZeros = http.putJsonAs(anna, "/api/inspirations/" + id + "/place", "{\"x\":12.0,\"y\":3}");

        assertThat(fraction.statusCode()).as(fraction.body()).isEqualTo(400);
        assertThat(fieldProblems(fraction)).containsExactly("x: Use a whole number for x.");
        assertThat(wholeWithZeros.statusCode()).as("12.0 is the whole number 12").isEqualTo(200);
        assertThat(place(read(id))).isEqualTo("12,3");
    }

    @Test
    void anIdeaThatIsNotThereOrIsNotThisWeddingsIsNotFound() throws Exception {
        HttpResponse<String> response = move(anna, UUID.randomUUID().toString(), 1, 1);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) TestHttp.json(response).get("type")).endsWith(":not-found");

        // Even a request that is wrong in itself says "not found" for an idea that is not there.
        HttpResponse<String> wrongAndGone =
                http.putJsonAs(anna, "/api/inspirations/" + UUID.randomUUID() + "/place", "{\"x\":1000000,\"y\":1}");
        assertThat(wrongAndGone.statusCode()).isEqualTo(404);
    }

    @Test
    void movingNeedsASignIn() throws Exception {
        String id = id(added(link("private", null)));

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/inspirations/" + id + "/place"))
                .header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("{\"x\":1,\"y\":1}"))
                .build();
        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(place(read(id))).isEqualTo("0,0");
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> link(String name, String id) {
        Map<String, Object> request = new HashMap<>();
        if (id != null) {
            request.put("id", id);
        }
        request.put("url", "https://example.com/" + name.replace(' ', '-'));
        request.put("boardId", null);
        request.put("title", name);
        request.put("note", "");
        request.put("tags", List.of());
        request.put("planItemId", null);
        return request;
    }

    private Map<String, Object> added(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return TestHttp.json(response);
    }

    private HttpResponse<String> move(String token, String id, int x, int y) throws Exception {
        return http.putJsonAs(token, "/api/inspirations/" + id + "/place", "{\"x\":" + x + ",\"y\":" + y + "}");
    }

    private Map<String, Object> read(String id) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/inspirations/" + id);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM inspiration", Integer.class);
    }

    private static String id(Map<String, Object> inspiration) {
        return (String) inspiration.get("id");
    }

    private static String place(Map<String, Object> inspiration) {
        return inspiration.get("x") + "," + inspiration.get("y");
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldProblems(HttpResponse<String> response) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) TestHttp.json(response).get("fieldProblems");
        return fields.stream().map(field -> field.get("field") + ": " + field.get("message")).toList();
    }
}
