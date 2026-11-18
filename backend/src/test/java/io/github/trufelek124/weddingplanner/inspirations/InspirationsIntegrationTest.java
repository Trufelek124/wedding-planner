package io.github.trufelek124.weddingplanner.inspirations;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ideas the couple keeps: links on boards, with a note and tags. */
class InspirationsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    TestWedding wedding;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    // ------------------------------------------------------------ boards

    @Test
    void aNewWeddingStartsWithTheUsualBoardsOnce() throws Exception {
        List<String> first = boardNames(anna);
        List<String> second = boardNames(piotr);

        assertThat(first).containsExactly("Dress", "Suit", "Hair and make-up", "Flowers", "Decor", "Venue", "Cake", "Invitations");
        assertThat(second).as("asking again adds nothing").isEqualTo(first);
    }

    @Test
    void boardsThatWereRemovedDoNotComeBack() throws Exception {
        for (Map<String, Object> board : TestHttp.jsonList(http.getAs(anna, "/api/boards"))) {
            assertThat(http.deleteAs(anna, "/api/boards/" + board.get("id")).statusCode()).isEqualTo(204);
        }

        assertThat(boardNames(piotr)).isEmpty();
    }

    @Test
    void aBoardCanBeAddedRenamedAndRemovedByEitherOwner() throws Exception {
        boardNames(anna);

        HttpResponse<String> added = http.postJsonAs(anna, "/api/boards", "{\"name\":\"  Table settings \"}");
        assertThat(added.statusCode()).as(added.body()).isEqualTo(201);
        assertThat(TestHttp.json(added)).containsEntry("name", "Table settings").containsEntry("inspirationCount", 0);
        String id = (String) TestHttp.json(added).get("id");
        assertThat(boardNames(piotr)).as("new ones go to the end").endsWith("Invitations", "Table settings");

        HttpResponse<String> renamed = http.putJsonAs(piotr, "/api/boards/" + id, "{\"name\":\"Tables\"}");
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(boardNames(anna)).contains("Tables").doesNotContain("Table settings");

        assertThat(http.deleteAs(anna, "/api/boards/" + id).statusCode()).isEqualTo(204);
        assertThat(boardNames(anna)).doesNotContain("Tables").hasSize(8);
        assertThat(http.deleteAs(anna, "/api/boards/" + id).statusCode()).as("it is gone").isEqualTo(404);
        assertThat(http.putJsonAs(anna, "/api/boards/" + id, "{\"name\":\"Again\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void twoBoardsCannotHaveTheSameNameHoweverCapitalised() throws Exception {
        String flowers = boardId("Flowers");

        HttpResponse<String> added = http.postJsonAs(anna, "/api/boards", "{\"name\":\"DRESS\"}");
        HttpResponse<String> renamed = http.putJsonAs(anna, "/api/boards/" + flowers, "{\"name\":\"dress\"}");

        assertThat(added.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(added)).containsExactly("name: There is a board of that name already.");
        assertThat(TestHttp.fieldMessages(renamed)).containsExactly("name: There is a board of that name already.");
        assertThat(TestHttp.fieldMessages(http.postJsonAs(anna, "/api/boards", "{\"name\":\"   \"}")))
                .containsExactly("name: Enter a name.");
    }

    @Test
    void removingABoardThatHasInspirationsAsksWhatHappensToThem() throws Exception {
        String flowers = boardId("Flowers");
        String dress = boardId("Dress");
        String peonies = id(added(link("https://example.com/peonies", flowers)));
        String roses = id(added(link("https://example.com/roses", flowers)));
        String veil = id(added(link("https://example.com/veil", dress)));
        assertThat(boardCount("Flowers")).isEqualTo(2);

        HttpResponse<String> undecided = http.deleteAs(anna, "/api/boards/" + flowers);
        assertThat(undecided.statusCode()).isEqualTo(409);
        assertThat((String) TestHttp.json(undecided).get("type")).endsWith(":board-has-inspirations");
        assertThat(boardNames(anna)).as("nothing was removed").contains("Flowers");

        // Kept: the inspirations stay, on no board.
        assertThat(http.deleteAs(anna, "/api/boards/" + flowers + "?inspirations=KEEP").statusCode()).isEqualTo(204);
        assertThat(read(peonies).get("boardId")).isNull();
        assertThat(read(roses).get("boardId")).isNull();
        assertThat(titles("withoutBoard=true")).hasSize(2);

        // Deleted: they go with the board; the rest is untouched.
        assertThat(http.deleteAs(anna, "/api/boards/" + dress + "?inspirations=DELETE").statusCode()).isEqualTo(204);
        assertThat(http.getAs(anna, "/api/inspirations/" + veil).statusCode()).isEqualTo(404);
        assertThat(read(peonies)).containsEntry("url", "https://example.com/peonies");
    }

    // ------------------------------------------------------------ inspirations

    @Test
    void aLinkIsKeptWithItsBoardNoteAndTagsAndSaysWhereItComesFromAndWhoAddedIt() throws Exception {
        String dress = boardId("Dress");
        Map<String, Object> request = link("  https://www.instagram.com/p/Cabc123/  ", dress);
        request.put("title", "  Lace sleeves ");
        request.put("note", "  Ask the seamstress about this. ");
        request.put("tags", List.of("#Boho", "  lace   sleeves ", "boho", "##Vintage"));

        Map<String, Object> saved = added(request);

        assertThat(saved)
                .containsEntry("url", "https://www.instagram.com/p/Cabc123/")
                .containsEntry("source", "INSTAGRAM")
                .containsEntry("title", "Lace sleeves")
                .containsEntry("note", "Ask the seamstress about this.")
                .containsEntry("boardId", dress)
                .containsEntry("addedById", wedding.annaId)
                .containsEntry("version", 0);
        // One tag once, in lower case, without the # people type out of habit.
        assertThat(saved.get("tags")).isEqualTo(List.of("boho", "lace sleeves", "vintage"));
        assertThat(saved.get("planItemId")).isNull();
        // The other owner sees the same thing.
        HttpResponse<String> seenByPiotr = http.getAs(piotr, "/api/inspirations/" + saved.get("id"));
        assertThat(TestHttp.json(seenByPiotr)).isEqualTo(saved);
    }

    @Test
    void anIdeaWithoutALinkIsAPhotoWaitingForItsPictures() throws Exception {
        Map<String, Object> request = link(null, null);
        request.put("note", "The arch at the lake.");

        Map<String, Object> saved = added(request);

        assertThat(saved).containsEntry("source", "PHOTO").containsEntry("title", "");
        assertThat(saved.get("url")).isNull();
        assertThat(saved.get("boardId")).isNull();
        // Every field is there, also the empty ones.
        assertThat(saved).containsOnlyKeys(
                "id", "url", "source", "title", "note", "tags", "boardId", "planItemId", "addedById", "version", "createdAt", "updatedAt");
    }

    @Test
    void onlyAWebAddressIsTakenForALink() throws Exception {
        for (String notALink : List.of("javascript:alert(1)", "file:///etc/passwd", "example.com/no-scheme", "ftp://example.com/x")) {
            HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(link(notALink, null)));

            assertThat(response.statusCode()).as(notALink).isEqualTo(400);
            assertThat(TestHttp.fieldMessages(response)).containsExactly("url: Enter a link that starts with http:// or https://.");
        }
        assertThat(titles("")).isEmpty();
    }

    @Test
    void whatARequestPointsAtMustBeTheCouplesOwn() throws Exception {
        String task = entry("TASK", "Call the florist");
        Map<String, Object> request = link("https://example.com/idea", UUID.randomUUID().toString());
        request.put("planItemId", task);

        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(response)).containsExactlyInAnyOrder(
                "boardId: Choose one of your boards.", "planItemId: Choose one of your plan items.");
    }

    @Test
    void tooMuchIsRefusedFieldByField() throws Exception {
        Map<String, Object> request = link("https://example.com/idea", null);
        request.put("title", "t".repeat(201));
        request.put("tags", List.of("x".repeat(41)));

        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(response)).containsExactlyInAnyOrder(
                "title: Use at most 200 characters.", "tags[0]: Use at most 40 characters for a tag.");
    }

    @Test
    void anInspirationIsChangedAsAWholeAndCountsItsSaves() throws Exception {
        String dress = boardId("Dress");
        String decor = boardId("Decor");
        Map<String, Object> saved = added(link("https://example.com/idea", dress));
        TestClock.advance(Duration.ofMinutes(5));

        Map<String, Object> change = link("https://www.tiktok.com/@someone/video/1", decor);
        change.put("note", "Better as a centrepiece.");
        change.put("tags", List.of("tables"));
        change.put("version", 0);
        HttpResponse<String> changed = http.putJsonAs(piotr, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(change));

        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(TestHttp.json(changed))
                .containsEntry("url", "https://www.tiktok.com/@someone/video/1")
                .containsEntry("source", "TIKTOK")
                .containsEntry("boardId", decor)
                .containsEntry("note", "Better as a centrepiece.")
                .containsEntry("version", 1)
                // Who added it stays who added it, whoever changes it later.
                .containsEntry("addedById", wedding.annaId)
                .containsEntry("createdAt", saved.get("createdAt"));
        assertThat(TestHttp.json(changed).get("updatedAt")).isNotEqualTo(saved.get("updatedAt"));
        assertThat(boardCount("Dress")).isZero();
        assertThat(boardCount("Decor")).isEqualTo(1);
    }

    @Test
    void aChangeBasedOnAnOlderCopyIsRefusedAndOverwritesNothing() throws Exception {
        Map<String, Object> saved = added(link("https://example.com/idea", null));
        Map<String, Object> first = link("https://example.com/idea", null);
        first.put("note", "Anna's note.");
        first.put("version", 0);
        assertThat(http.putJsonAs(anna, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(first)).statusCode()).isEqualTo(200);

        Map<String, Object> second = link("https://example.com/idea", null);
        second.put("note", "Piotr's note, written without seeing Anna's.");
        second.put("version", 0);
        HttpResponse<String> refused = http.putJsonAs(piotr, "/api/inspirations/" + saved.get("id"), TestHttp.jsonOf(second));

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat((String) TestHttp.json(refused).get("type")).endsWith(":changed-meanwhile");
        assertThat(read((String) saved.get("id"))).containsEntry("note", "Anna's note.").containsEntry("version", 1);
    }

    @Test
    void anInspirationCanBeDeletedAndIsThenGone() throws Exception {
        String id = id(added(link("https://example.com/idea", null)));

        assertThat(http.deleteAs(piotr, "/api/inspirations/" + id).statusCode()).isEqualTo(204);

        assertThat(http.getAs(anna, "/api/inspirations/" + id).statusCode()).isEqualTo(404);
        assertThat(http.deleteAs(anna, "/api/inspirations/" + id).statusCode()).isEqualTo(404);
        assertThat(titles("")).isEmpty();
    }

    // ------------------------------------------------------------ finding them again

    @Test
    void theListIsNewestFirstAndNarrowsByBoardTagAndPlanItem() throws Exception {
        String dress = boardId("Dress");
        String flowers = boardId("Flowers");
        String venue = entry("PLAN_ITEM", "Book the venue");
        added(titled("veil", dress, List.of("lace", "boho"), null));
        TestClock.advance(Duration.ofSeconds(1));
        added(titled("peonies", flowers, List.of("boho", "pink"), venue));
        TestClock.advance(Duration.ofSeconds(1));
        added(titled("arch", null, List.of("outdoor"), venue));

        assertThat(titles("")).containsExactly("arch", "peonies", "veil");
        assertThat(titles("boardId=" + dress)).containsExactly("veil");
        assertThat(titles("withoutBoard=true")).containsExactly("arch");
        assertThat(titles("tag=boho")).containsExactly("peonies", "veil");
        // A tag is asked for the way people write it; it is matched the way it is kept.
        assertThat(titles("tag=%23Boho")).containsExactly("peonies", "veil");
        assertThat(titles("tag=boho&boardId=" + flowers)).containsExactly("peonies");
        assertThat(titles("planItemId=" + venue)).containsExactly("arch", "peonies");
        assertThat(titles("tag=nothing-has-this")).isEmpty();
    }

    @Test
    void theTagsInUseAreListedWithHowManyCarryThem() throws Exception {
        added(titled("veil", null, List.of("lace", "boho"), null));
        added(titled("peonies", null, List.of("Boho", "pink"), null));
        String arch = id(added(titled("arch", null, List.of("outdoor"), null)));

        assertThat(tagCounts()).containsExactly("boho 2", "lace 1", "outdoor 1", "pink 1");

        http.deleteAs(anna, "/api/inspirations/" + arch);
        assertThat(tagCounts()).as("a tag nothing carries any more is gone").containsExactly("boho 2", "lace 1", "pink 1");
    }

    // ------------------------------------------------------------ the plan item an idea belongs to

    @Test
    void anInspirationCanBelongToAPlanItemAndLosesTheLinkWhenThePlanItemGoes() throws Exception {
        String venue = entry("PLAN_ITEM", "Book the venue");
        Map<String, Object> saved = added(titled("arch", null, List.of(), venue));
        assertThat(saved).containsEntry("planItemId", venue);

        assertThat(http.deleteAs(anna, "/api/entries/" + venue).statusCode()).isEqualTo(204);

        Map<String, Object> afterwards = read(id(saved));
        assertThat(afterwards.get("planItemId")).as("the idea stays, the link is gone").isNull();
        assertThat(afterwards).containsEntry("title", "arch");
    }

    @Test
    void nothingIsServedWithoutSigningIn() throws Exception {
        assertThat(http.get("/api/inspirations").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/boards").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/inspirations/tags").statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------ helpers

    private static Map<String, Object> link(String url, String boardId) {
        Map<String, Object> request = new HashMap<>();
        request.put("url", url);
        request.put("boardId", boardId);
        request.put("title", "");
        request.put("note", "");
        request.put("tags", List.of());
        request.put("planItemId", null);
        return request;
    }

    private static Map<String, Object> titled(String title, String boardId, List<String> tags, String planItemId) {
        Map<String, Object> request = link("https://example.com/" + title, boardId);
        request.put("title", title);
        request.put("tags", tags);
        request.put("planItemId", planItemId);
        return request;
    }

    private Map<String, Object> added(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private static String id(Map<String, Object> inspiration) {
        return (String) inspiration.get("id");
    }

    private Map<String, Object> read(String id) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/inspirations/" + id);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private List<String> titles(String query) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/inspirations" + (query.isEmpty() ? "" : "?" + query));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(one -> (String) one.get("title")).toList();
    }

    private List<String> tagCounts() throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/inspirations/tags")).stream()
                .map(one -> one.get("tag") + " " + one.get("count"))
                .toList();
    }

    private List<String> boardNames(String token) throws Exception {
        return TestHttp.jsonList(http.getAs(token, "/api/boards")).stream().map(board -> (String) board.get("name")).toList();
    }

    private String boardId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/boards")).stream()
                .filter(board -> name.equals(board.get("name")))
                .map(board -> (String) board.get("id"))
                .findFirst()
                .orElseThrow();
    }

    private int boardCount(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/boards")).stream()
                .filter(board -> name.equals(board.get("name")))
                .map(board -> (Integer) board.get("inspirationCount"))
                .findFirst()
                .orElseThrow();
    }

    /** A plan item or a task to attach ideas to. */
    private String entry(String kind, String title) throws Exception {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", kind);
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", null);
        entry.put("startDate", null);
        entry.put("dueDate", null);
        entry.put("status", "NOT_STARTED");
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", "AUTO");
        entry.put("priority", null);
        entry.put("parentId", null);
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(entry));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) TestHttp.json(response).get("id");
    }
}
