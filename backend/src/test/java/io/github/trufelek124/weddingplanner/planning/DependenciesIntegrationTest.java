package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
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

class DependenciesIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;
    LocalDate today;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    @Test
    void aPlanItemCanWaitForAnotherAndBothOwnersSeeIt() throws Exception {
        String guestList = planItem("Draw up the guest list", null, null);
        String invitations = planItem("Send invitations", null, null);

        HttpResponse<String> linked = http.putJsonAs(anna, link(invitations, guestList), "");

        assertThat(linked.statusCode()).as(linked.body()).isEqualTo(200);
        assertThat(TestHttp.json(linked).get("dependsOnIds")).isEqualTo(List.of(guestList));
        assertThat(read(piotr, invitations).get("dependsOnIds")).isEqualTo(List.of(guestList));
        assertThat(read(piotr, guestList).get("dependsOnIds")).as("the link has a direction").isEqualTo(List.of());
    }

    @Test
    void sayingItTwiceChangesNothing() throws Exception {
        String guestList = planItem("Draw up the guest list", null, null);
        String invitations = planItem("Send invitations", null, null);

        http.putJsonAs(anna, link(invitations, guestList), "");
        HttpResponse<String> again = http.putJsonAs(piotr, link(invitations, guestList), "");

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(again).get("dependsOnIds")).isEqualTo(List.of(guestList));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_dependency", Integer.class)).isEqualTo(1);
    }

    @Test
    void aCircleIsRefusedWithItsOwnKindOfError() throws Exception {
        String a = planItem("A", null, null);
        String b = planItem("B", null, null);
        String c = planItem("C", null, null);
        http.putJsonAs(anna, link(a, b), "");
        http.putJsonAs(anna, link(b, c), "");

        for (String closing : List.of(link(c, a), link(b, a), link(a, a))) {
            HttpResponse<String> response = http.putJsonAs(anna, closing, "");

            assertThat(response.statusCode()).as(closing).isEqualTo(409);
            assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:dependency-cycle");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_dependency", Integer.class)).as("nothing was added").isEqualTo(2);
        assertThat(http.putJsonAs(anna, link(a, c), "").statusCode()).as("a shortcut in the same direction is fine").isEqualTo(200);
    }

    @Test
    void onlyPlanItemsThatExistCanWaitForEachOther() throws Exception {
        String item = planItem("Send invitations", null, null);
        String task = entry("TASK", "Order the envelopes", null, null);

        assertThat(http.putJsonAs(anna, link(task, item), "").statusCode()).isEqualTo(400);
        assertThat(http.putJsonAs(anna, link(item, task), "").statusCode()).isEqualTo(400);
        assertThat(http.putJsonAs(anna, link(item, UUID.randomUUID().toString()), "").statusCode()).isEqualTo(404);
        assertThat(http.putJsonAs(anna, link(UUID.randomUUID().toString(), item), "").statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_dependency", Integer.class)).isZero();
    }

    @Test
    void aLinkCanBeTakenAway() throws Exception {
        String guestList = planItem("Draw up the guest list", null, null);
        String invitations = planItem("Send invitations", null, null);
        http.putJsonAs(anna, link(invitations, guestList), "");

        HttpResponse<String> removed = http.deleteAs(piotr, link(invitations, guestList));

        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(removed).get("dependsOnIds")).isEqualTo(List.of());
        assertThat(http.deleteAs(piotr, link(invitations, guestList)).statusCode()).as("it is gone").isEqualTo(404);
    }

    @Test
    void aPlanItemThatBeginsBeforeWhatItWaitsForEndsIsMarked() throws Exception {
        String guestList = planItem("Draw up the guest list", today.plusDays(10), today.plusDays(40));
        String venue = planItem("Book the venue", today.plusDays(1), today.plusDays(20));
        String invitations = planItem("Send invitations", today.plusDays(30), today.plusDays(60));
        http.putJsonAs(anna, link(invitations, guestList), "");
        http.putJsonAs(anna, link(invitations, venue), "");

        Map<String, Object> entry = read(anna, invitations);

        assertThat(entry.get("conflictsWithIds")).as("the guest list ends ten days after the invitations begin").isEqualTo(List.of(guestList));
        @SuppressWarnings("unchecked")
        List<String> waitsFor = (List<String>) entry.get("dependsOnIds");
        assertThat(waitsFor).containsExactlyInAnyOrder(guestList, venue);
        assertThat(read(anna, venue).get("conflictsWithIds")).isEqualTo(List.of());
    }

    @Test
    void whatSomethingSoonWaitsForMovesUpInPriority() throws Exception {
        String venue = planItem("Book the venue", null, today.plusDays(200));
        String tasting = planItem("Menu tasting", null, today.plusDays(25));
        assertThat(levelOf(read(anna, venue))).isEqualTo("LOW DUE_LATER");

        http.putJsonAs(anna, link(tasting, venue), "");
        assertThat(levelOf(read(anna, venue))).isEqualTo("HIGH NEEDED_BY_ANOTHER");

        http.putJsonAs(anna, "/api/entries/" + tasting + "/status", "{\"status\":\"DONE\"}");
        assertThat(levelOf(read(anna, venue))).as("nothing unfinished waits any more").isEqualTo("LOW DUE_LATER");
    }

    @Test
    void theListCarriesTheSameLinksAndLevelsAsASingleEntry() throws Exception {
        String venue = planItem("Book the venue", null, today.plusDays(200));
        String tasting = planItem("Menu tasting", null, today.plusDays(25));
        http.putJsonAs(anna, link(tasting, venue), "");

        List<Map<String, Object>> list = TestHttp.jsonList(http.getAs(anna, "/api/entries?sort=PRIORITY"));

        assertThat(list).extracting(entry -> entry.get("title")).containsExactly("Book the venue", "Menu tasting");
        assertThat(list.get(0)).isEqualTo(read(anna, venue));
        assertThat(list.get(1)).isEqualTo(read(anna, tasting));
    }

    @Test
    void deletingAPlanItemTakesItsLinksWithIt() throws Exception {
        String guestList = planItem("Draw up the guest list", null, null);
        String invitations = planItem("Send invitations", null, null);
        String thankYous = planItem("Send thank-you cards", null, null);
        http.putJsonAs(anna, link(invitations, guestList), "");
        http.putJsonAs(anna, link(thankYous, invitations), "");

        assertThat(http.deleteAs(anna, "/api/entries/" + invitations).statusCode()).isEqualTo(204);

        assertThat(read(anna, thankYous).get("dependsOnIds")).isEqualTo(List.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry_dependency", Integer.class)).isZero();
    }

    @Test
    void aPlanItemThatBecomesATaskWaitsForNothingAndNothingWaitsForIt() throws Exception {
        String guestList = planItem("Draw up the guest list", null, null);
        String invitations = planItem("Send invitations", null, null);
        String thankYous = planItem("Send thank-you cards", null, null);
        http.putJsonAs(anna, link(invitations, guestList), "");
        http.putJsonAs(anna, link(thankYous, invitations), "");

        HttpResponse<String> converted = http.postJsonAs(anna, "/api/entries/" + invitations + "/convert", "{\"kind\":\"TASK\"}");

        assertThat(converted.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(converted).get("dependsOnIds")).isEqualTo(List.of());
        assertThat(read(anna, thankYous).get("dependsOnIds")).isEqualTo(List.of());
    }

    private String planItem(String title, LocalDate start, LocalDate due) throws Exception {
        Map<String, Object> item = new HashMap<>();
        item.put("startDate", start == null ? null : start.toString());
        return entry("PLAN_ITEM", title, item, due);
    }

    private String entry(String kind, String title, Map<String, Object> extra, LocalDate due) throws Exception {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", kind);
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", null);
        entry.put("startDate", null);
        entry.put("dueDate", due == null ? null : due.toString());
        entry.put("status", "NOT_STARTED");
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", "AUTO");
        entry.put("priority", null);
        entry.put("parentId", null);
        if (extra != null) {
            entry.putAll(extra);
        }
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(entry));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return (String) TestHttp.json(response).get("id");
    }

    private static String link(String entry, String dependsOn) {
        return "/api/entries/" + entry + "/dependencies/" + dependsOn;
    }

    private Map<String, Object> read(String who, String id) throws Exception {
        HttpResponse<String> response = http.getAs(who, "/api/entries/" + id);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private static String levelOf(Map<String, Object> entry) {
        return entry.get("priority") + " " + entry.get("priorityReason");
    }
}
