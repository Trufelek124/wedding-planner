package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

class EntriesIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestWedding wedding;
    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    // ---------------------------------------------------------------- saving

    @Test
    void aPlanItemWithoutDatesIsSavedAndListedAsNotScheduled() throws Exception {
        Map<String, Object> item = created(planItem("Choose a photographer"));

        assertThat(item).containsEntry("kind", "PLAN_ITEM").containsEntry("startDate", null).containsEntry("dueDate", null);
        assertThat(titles(http.getAs(anna, "/api/entries?scheduled=false"))).containsExactly("Choose a photographer");
        assertThat(titles(http.getAs(anna, "/api/entries?scheduled=true"))).isEmpty();
    }

    @Test
    void aTaskIsSavedWithOrWithoutADueDate() throws Exception {
        Map<String, Object> dated = created(with(task("Pay the venue deposit"), "dueDate", "2027-01-15"));
        Map<String, Object> open = created(task("Call the florist"));

        assertThat(dated).containsEntry("kind", "TASK").containsEntry("dueDate", "2027-01-15");
        assertThat(open).containsEntry("dueDate", null);
        assertThat(titles(http.getAs(anna, "/api/entries?kind=TASK"))).containsExactly("Pay the venue deposit", "Call the florist");
    }

    @Test
    void aTaskIsTickedOffInOneActionAndRemembersWhen() throws Exception {
        String id = id(created(task("Call the florist")));

        Map<String, Object> done = ok(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}"));

        assertThat(done).containsEntry("status", "DONE");
        assertThat(Instant.parse((String) done.get("completedAt"))).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));

        Map<String, Object> reopened = ok(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"NOT_STARTED\"}"));
        assertThat(reopened).containsEntry("status", "NOT_STARTED").containsEntry("completedAt", null);
    }

    @Test
    void everyFieldOfAPlanItemComesBackAsSaved() throws Exception {
        String venue = categoryId("Venue");
        Map<String, Object> request = planItem("Book the venue");
        request.put("notes", "Ask about the garden.");
        request.put("categoryId", venue);
        request.put("startDate", "2026-12-01");
        request.put("dueDate", "2027-01-31");
        request.put("status", "IN_PROGRESS");
        request.put("assigneeIds", List.of(wedding.piotrId, wedding.annaId));
        request.put("priorityMode", "MANUAL");
        request.put("priority", "HIGH");

        Map<String, Object> saved = created(request);
        Map<String, Object> read = ok(http.getAs(piotr, "/api/entries/" + id(saved)));

        assertThat(read).isEqualTo(saved);
        assertThat(read)
                .containsEntry("title", "Book the venue")
                .containsEntry("notes", "Ask about the garden.")
                .containsEntry("categoryId", venue)
                .containsEntry("startDate", "2026-12-01")
                .containsEntry("dueDate", "2027-01-31")
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("priorityMode", "MANUAL")
                .containsEntry("priority", "HIGH")
                .containsEntry("parentId", null)
                .containsEntry("taskCount", 0)
                .containsEntry("version", 0);
        // Whoever sends the people in whatever order, they come back in the wedding's order.
        assertThat(read.get("assigneeIds")).isEqualTo(List.of(wedding.annaId, wedding.piotrId));
    }

    @Test
    void aTaskBelongsToAPlanItemWhichCountsItsTasks() throws Exception {
        String item = id(created(planItem("Send invitations")));
        created(with(task("Order the envelopes"), "parentId", item));
        created(with(task("Collect addresses"), "parentId", item));
        created(task("Unrelated"));

        assertThat(ok(http.getAs(anna, "/api/entries/" + item))).containsEntry("taskCount", 2);
        assertThat(titles(http.getAs(anna, "/api/entries?sort=TITLE&parentId=" + item)))
                .containsExactly("Collect addresses", "Order the envelopes");
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    void whatCannotBeSavedIsSaidPerField() throws Exception {
        String item = id(created(planItem("A plan item")));
        String aTask = id(created(task("A task")));

        assertThat(refused(with(task("x"), "startDate", "2027-01-01", "dueDate", "2027-01-02"))).containsExactly("startDate");
        assertThat(refused(with(planItem("x"), "startDate", "2027-01-01"))).containsExactly("dueDate");
        assertThat(refused(with(planItem("x"), "startDate", "2027-02-01", "dueDate", "2027-01-01"))).containsExactly("dueDate");
        assertThat(refused(with(task("x"), "priorityMode", "MANUAL"))).containsExactly("priority");
        assertThat(refused(with(task("x"), "categoryId", UUID.randomUUID().toString()))).containsExactly("categoryId");
        assertThat(refused(with(task("x"), "assigneeIds", List.of(UUID.randomUUID().toString())))).containsExactly("assigneeIds");
        assertThat(refused(with(planItem("x"), "parentId", item))).containsExactly("parentId");
        assertThat(refused(with(task("x"), "parentId", aTask))).as("a task cannot belong to a task").containsExactly("parentId");
        assertThat(refused(with(task("x"), "parentId", UUID.randomUUID().toString()))).containsExactly("parentId");
        assertThat(refused(task("   "))).containsExactly("title");
        assertThat(refused(task("x".repeat(201)))).containsExactly("title");
        assertThat(refused(with(task("x"), "kind", null))).containsExactly("kind");
        assertThat(refused(with(task("x"), "assigneeIds", List.of(wedding.annaId, wedding.piotrId, wedding.annaId))))
                .containsExactly("assigneeIds");

        HttpResponse<String> notAStatus = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(with(task("x"), "status", "ALMOST")));
        assertThat(notAStatus.statusCode()).isEqualTo(400);

        assertThat(titles(http.getAs(anna, "/api/entries"))).as("none of these was saved").containsExactlyInAnyOrder("A plan item", "A task");
    }

    @Test
    void theSamePersonTwiceIsOnePerson() throws Exception {
        Map<String, Object> saved = created(with(task("x"), "assigneeIds", List.of(wedding.piotrId, wedding.piotrId)));

        assertThat(saved.get("assigneeIds")).isEqualTo(List.of(wedding.piotrId));
    }

    // ---------------------------------------------------------------- changing

    @Test
    void aChangeByOneOwnerIsSeenByTheOther() throws Exception {
        Map<String, Object> saved = created(task("Call the florist"));

        Map<String, Object> change = change(saved);
        change.put("title", "Call the florist about peonies");
        change.put("dueDate", "2027-03-01");
        change.put("assigneeIds", List.of(wedding.piotrId));
        Map<String, Object> changed = ok(http.putJsonAs(piotr, "/api/entries/" + id(saved), TestHttp.jsonOf(change)));

        assertThat(changed).containsEntry("version", 1);
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(saved))))
                .containsEntry("title", "Call the florist about peonies")
                .containsEntry("dueDate", "2027-03-01")
                .containsEntry("assigneeIds", List.of(wedding.piotrId));
    }

    @Test
    void aChangeBasedOnAnOutdatedCopyIsRefusedInsteadOfOverwritingTheOther() throws Exception {
        Map<String, Object> saved = created(task("Call the florist"));
        Map<String, Object> annasCopy = change(saved);
        Map<String, Object> piotrsCopy = change(saved);

        annasCopy.put("title", "Call the florist on Monday");
        ok(http.putJsonAs(anna, "/api/entries/" + id(saved), TestHttp.jsonOf(annasCopy)));
        piotrsCopy.put("notes", "Ask for peonies.");
        HttpResponse<String> late = http.putJsonAs(piotr, "/api/entries/" + id(saved), TestHttp.jsonOf(piotrsCopy));

        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(TestHttp.json(late)).containsEntry("type", "urn:wedding-planner:problem:changed-meanwhile");
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(saved))))
                .containsEntry("title", "Call the florist on Monday")
                .containsEntry("notes", "");
    }

    @Test
    void tickingOffCountsAsAChangeSoAnOutdatedCopyCannotUntickIt() throws Exception {
        Map<String, Object> saved = created(task("Call the florist"));
        Map<String, Object> piotrsCopy = change(saved);

        ok(http.putJsonAs(anna, "/api/entries/" + id(saved) + "/status", "{\"status\":\"DONE\"}"));
        piotrsCopy.put("title", "Call the florist today");
        HttpResponse<String> late = http.putJsonAs(piotr, "/api/entries/" + id(saved), TestHttp.jsonOf(piotrsCopy));

        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(saved)))).containsEntry("status", "DONE");
    }

    @Test
    void whatIsDoneStaysDoneAtTheSameMomentWhenItIsEdited() throws Exception {
        Map<String, Object> saved = created(task("Call the florist"));
        Map<String, Object> done = ok(http.putJsonAs(anna, "/api/entries/" + id(saved) + "/status", "{\"status\":\"DONE\"}"));

        Map<String, Object> change = change(done);
        change.put("notes", "They had peonies.");
        Map<String, Object> edited = ok(http.putJsonAs(anna, "/api/entries/" + id(saved), TestHttp.jsonOf(change)));

        assertThat(edited).containsEntry("status", "DONE").containsEntry("completedAt", done.get("completedAt"));
    }

    // ---------------------------------------------------------------- converting

    @Test
    void aTaskBecomesAPlanItemAndATaskAgainWithoutLosingWhatWasEntered() throws Exception {
        String flowers = categoryId("Flowers and decor");
        String item = id(created(planItem("Decorations")));
        Map<String, Object> request = with(task("Choose the flowers"), "notes", "Peonies if possible.", "dueDate", "2027-04-01",
                "categoryId", flowers, "assigneeIds", List.of(wedding.annaId), "priorityMode", "MANUAL", "priority", "HIGH",
                "parentId", item);
        String id = id(created(request));

        Map<String, Object> asPlanItem = ok(http.postJsonAs(anna, "/api/entries/" + id + "/convert", "{\"kind\":\"PLAN_ITEM\"}"));

        assertThat(asPlanItem)
                .containsEntry("kind", "PLAN_ITEM")
                .containsEntry("parentId", null)
                .containsEntry("startDate", null)
                .containsEntry("dueDate", "2027-04-01");
        assertKept(asPlanItem, flowers);

        Map<String, Object> asTaskAgain = ok(http.postJsonAs(piotr, "/api/entries/" + id + "/convert", "{\"kind\":\"TASK\"}"));

        assertThat(asTaskAgain).containsEntry("kind", "TASK").containsEntry("dueDate", "2027-04-01");
        assertKept(asTaskAgain, flowers);
    }

    private void assertKept(Map<String, Object> entry, String categoryId) {
        assertThat(entry)
                .containsEntry("title", "Choose the flowers")
                .containsEntry("notes", "Peonies if possible.")
                .containsEntry("categoryId", categoryId)
                .containsEntry("assigneeIds", List.of(wedding.annaId))
                .containsEntry("priorityMode", "MANUAL")
                .containsEntry("priority", "HIGH")
                .containsEntry("status", "NOT_STARTED");
    }

    @Test
    void aPlanItemWithTasksCannotBecomeATask() throws Exception {
        String item = id(created(planItem("Send invitations")));
        created(with(task("Order the envelopes"), "parentId", item));

        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries/" + item + "/convert", "{\"kind\":\"TASK\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:entry-has-tasks");
        assertThat(ok(http.getAs(anna, "/api/entries/" + item))).containsEntry("kind", "PLAN_ITEM").containsEntry("taskCount", 1);
    }

    // ---------------------------------------------------------------- deleting

    @Test
    void deletingAPlanItemWithTasksAsksWhatHappensToThem() throws Exception {
        String item = id(created(planItem("Send invitations")));
        created(with(task("Order the envelopes"), "parentId", item));

        HttpResponse<String> undecided = http.deleteAs(anna, "/api/entries/" + item);

        assertThat(undecided.statusCode()).isEqualTo(409);
        assertThat(TestHttp.json(undecided)).containsEntry("type", "urn:wedding-planner:problem:entry-has-tasks");
        assertThat(titles(http.getAs(anna, "/api/entries?sort=TITLE"))).containsExactly("Order the envelopes", "Send invitations");
    }

    @Test
    void theTasksOfADeletedPlanItemCanBeKept() throws Exception {
        String item = id(created(planItem("Send invitations")));
        Map<String, Object> taskBefore = created(with(task("Order the envelopes"), "parentId", item));

        assertThat(http.deleteAs(anna, "/api/entries/" + item + "?tasks=KEEP").statusCode()).isEqualTo(204);

        assertThat(http.getAs(anna, "/api/entries/" + item).statusCode()).isEqualTo(404);
        Map<String, Object> taskAfter = ok(http.getAs(anna, "/api/entries/" + id(taskBefore)));
        assertThat(taskAfter).containsEntry("parentId", null).containsEntry("title", "Order the envelopes");
        assertThat((Integer) taskAfter.get("version")).isGreaterThan((Integer) taskBefore.get("version"));
    }

    @Test
    void theTasksOfADeletedPlanItemCanGoWithIt() throws Exception {
        String item = id(created(planItem("Send invitations")));
        created(with(task("Order the envelopes"), "parentId", item));
        created(task("Unrelated"));

        assertThat(http.deleteAs(anna, "/api/entries/" + item + "?tasks=DELETE").statusCode()).isEqualTo(204);

        assertThat(titles(http.getAs(anna, "/api/entries"))).containsExactly("Unrelated");
    }

    @Test
    void whatHasNoTasksIsDeletedWithoutAQuestion() throws Exception {
        String aTask = id(created(task("Call the florist")));
        String item = id(created(planItem("Choose a photographer")));

        assertThat(http.deleteAs(piotr, "/api/entries/" + aTask).statusCode()).isEqualTo(204);
        assertThat(http.deleteAs(piotr, "/api/entries/" + item).statusCode()).isEqualTo(204);
        assertThat(http.deleteAs(piotr, "/api/entries/" + item).statusCode()).as("it is gone").isEqualTo(404);
        assertThat(titles(http.getAs(anna, "/api/entries"))).isEmpty();
    }

    // ---------------------------------------------------------------- lists

    @Test
    void listsCanBeNarrowedDown() throws Exception {
        String venue = categoryId("Venue");
        created(with(planItem("Book the venue"), "categoryId", venue, "assigneeIds", List.of(wedding.annaId), "status", "IN_PROGRESS"));
        created(with(task("Pay the deposit"), "categoryId", venue, "assigneeIds", List.of(wedding.annaId, wedding.piotrId), "status", "DONE"));
        created(with(task("Call the florist"), "assigneeIds", List.of(wedding.piotrId)));
        created(with(task("Dropped idea"), "status", "DROPPED"));

        assertThat(titles(list("kind=PLAN_ITEM"))).containsExactly("Book the venue");
        assertThat(titles(list("status=DONE"))).containsExactly("Pay the deposit");
        assertThat(titles(list("status=NOT_STARTED&status=IN_PROGRESS"))).containsExactly("Book the venue", "Call the florist");
        assertThat(titles(list("categoryId=" + venue))).containsExactly("Book the venue", "Pay the deposit");
        assertThat(titles(list("assigneeId=" + wedding.piotrId))).as("alone or together").containsExactly("Call the florist", "Pay the deposit");
        assertThat(titles(list("kind=TASK&assigneeId=" + wedding.annaId))).containsExactly("Pay the deposit");
        assertThat(titles(list(""))).hasSize(4);
        assertThat(http.getAs(anna, "/api/entries?sort=SIDEWAYS").statusCode()).isEqualTo(400);
        assertThat(http.getAs(anna, "/api/entries?kind=CHORE").statusCode()).isEqualTo(400);
    }

    @Test
    void listsCanBeOrderedByDateByPriorityByTitleAndByAge() throws Exception {
        // Dates relative to today, so that the level the planner works out for "Charlie" (normal) does not drift.
        String inFortyDays = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(40).toString();
        String inNinetyDays = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(90).toString();
        created(with(task("Delta"), "dueDate", inNinetyDays, "priorityMode", "MANUAL", "priority", "LOW"));
        created(with(task("alpha"), "priorityMode", "MANUAL", "priority", "CRITICAL"));
        created(with(task("Charlie"), "dueDate", inFortyDays));
        created(with(task("bravo"), "dueDate", inFortyDays, "priorityMode", "MANUAL", "priority", "HIGH"));

        assertThat(titles(list("sort=DUE_DATE"))).as("earliest first, undated last; the more urgent first on the same day")
                .containsExactly("bravo", "Charlie", "Delta", "alpha");
        assertThat(titles(http.getAs(anna, "/api/entries"))).as("by date unless asked otherwise")
                .containsExactly("bravo", "Charlie", "Delta", "alpha");
        assertThat(titles(list("sort=PRIORITY"))).containsExactly("alpha", "bravo", "Charlie", "Delta");
        assertThat(titles(list("sort=TITLE"))).as("without regard to capitals").containsExactly("alpha", "bravo", "Charlie", "Delta");
        assertThat(titles(list("sort=CREATED"))).containsExactly("Delta", "alpha", "Charlie", "bravo");
    }

    @Test
    void thereAreNoEntriesWithoutAWedding() throws Exception {
        jdbc.update("DELETE FROM wedding");

        HttpResponse<String> response = http.getAs(anna, "/api/entries");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:wedding-not-set-up");
    }

    @Test
    void anEntryThatDoesNotExistIsNotFound() throws Exception {
        String nowhere = "/api/entries/" + UUID.randomUUID();

        assertThat(http.getAs(anna, nowhere).statusCode()).isEqualTo(404);
        assertThat(http.putJsonAs(anna, nowhere + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(404);
        assertThat(http.postJsonAs(anna, nowhere + "/convert", "{\"kind\":\"TASK\"}").statusCode()).isEqualTo(404);
        assertThat(http.getAs(anna, "/api/entries/not-an-id").statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> entry(String kind, String title) {
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
        return entry;
    }

    private static Map<String, Object> planItem(String title) {
        return entry("PLAN_ITEM", title);
    }

    private static Map<String, Object> task(String title) {
        return entry("TASK", title);
    }

    private static Map<String, Object> with(Map<String, Object> entry, Object... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            entry.put((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return entry;
    }

    /** The body of a change that leaves the entry as it is: the caller alters what the test is about. */
    private static Map<String, Object> change(Map<String, Object> saved) {
        Map<String, Object> change = new HashMap<>();
        for (String field : List.of("version", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds", "priorityMode", "parentId")) {
            change.put(field, saved.get(field));
        }
        change.put("priority", "MANUAL".equals(saved.get("priorityMode")) ? saved.get("priority") : null);
        return change;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private List<String> refused(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return TestHttp.fieldProblems(response);
    }

    private static Map<String, Object> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private HttpResponse<String> list(String query) throws Exception {
        return http.getAs(anna, "/api/entries?" + query);
    }

    private static List<String> titles(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(entry -> (String) entry.get("title")).toList();
    }

    private static String id(Map<String, Object> entry) {
        return (String) entry.get("id");
    }

    private String categoryId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/categories")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> (String) category.get("id"))
                .findFirst()
                .orElseThrow();
    }
}
