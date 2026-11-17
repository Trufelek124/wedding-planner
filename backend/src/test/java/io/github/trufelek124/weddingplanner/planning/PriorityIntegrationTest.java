package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

class PriorityIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    /** Today where the test wedding takes place. */
    LocalDate today;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    @Test
    void anEntryLeftToThePlannerGetsItsLevelFromItsDateWithTheReason() throws Exception {
        assertThat(levelOf(created(task("Soon", today.plusDays(2), "AUTO", null)))).isEqualTo("CRITICAL DUE_WITHIN_3_DAYS");
        assertThat(levelOf(created(task("Next week", today.plusDays(9), "AUTO", null)))).isEqualTo("HIGH DUE_WITHIN_14_DAYS");
        assertThat(levelOf(created(task("Next month", today.plusDays(35), "AUTO", null)))).isEqualTo("NORMAL DUE_WITHIN_60_DAYS");
        assertThat(levelOf(created(task("Next year", today.plusDays(300), "AUTO", null)))).isEqualTo("LOW DUE_LATER");
        assertThat(levelOf(created(task("Some day", null, "AUTO", null)))).isEqualTo("LOW NO_DATE");
        assertThat(levelOf(created(task("Forgotten", today.minusDays(1), "AUTO", null)))).isEqualTo("CRITICAL OVERDUE");
    }

    @Test
    void theLevelFollowsTheCalendarWithoutAnyoneTouchingTheEntry() throws Exception {
        String id = (String) created(task("Book the band", today.plusDays(20), "AUTO", null)).get("id");
        assertThat(levelOf(read(id))).isEqualTo("NORMAL DUE_WITHIN_60_DAYS");

        TestClock.advance(Duration.ofDays(7));
        assertThat(levelOf(read(id))).isEqualTo("HIGH DUE_WITHIN_14_DAYS");

        TestClock.advance(Duration.ofDays(11));
        assertThat(levelOf(read(id))).isEqualTo("CRITICAL DUE_WITHIN_3_DAYS");

        TestClock.advance(Duration.ofDays(3));
        Map<String, Object> late = read(id);
        assertThat(levelOf(late)).isEqualTo("CRITICAL OVERDUE");
        assertThat(late).as("nothing was written: the entry is as it was saved").containsEntry("version", 0);
    }

    @Test
    void aLevelSetByHandStaysWhateverTheDate() throws Exception {
        String id = (String) created(task("Overdue but unimportant", today.minusDays(10), "MANUAL", "LOW")).get("id");

        assertThat(levelOf(read(id))).isEqualTo("LOW SET_BY_HAND");
        TestClock.advance(Duration.ofDays(30));
        assertThat(levelOf(read(id))).isEqualTo("LOW SET_BY_HAND");
    }

    @Test
    void switchingBackToThePlannerGivesTheCalculatedLevelAgain() throws Exception {
        Map<String, Object> saved = created(task("Pay the band", today.plusDays(1), "MANUAL", "LOW"));

        Map<String, Object> change = new HashMap<>(saved);
        change.keySet().retainAll(List.of("version", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds", "parentId"));
        change.put("priorityMode", "AUTO");
        change.put("priority", null);
        HttpResponse<String> response = http.putJsonAs(anna, "/api/entries/" + saved.get("id"), TestHttp.jsonOf(change));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(levelOf(TestHttp.json(response))).isEqualTo("CRITICAL DUE_WITHIN_3_DAYS");
        assertThat(jdbc.queryForObject("SELECT priority FROM entry WHERE id = ?::uuid", String.class, saved.get("id")))
                .as("only a level chosen by a person is stored")
                .isNull();
    }

    @Test
    void whatIsFinishedNeedsNoAttention() throws Exception {
        String id = (String) created(task("Forgotten", today.minusDays(5), "AUTO", null)).get("id");
        assertThat(levelOf(read(id))).isEqualTo("CRITICAL OVERDUE");

        http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}");

        assertThat(levelOf(read(id))).isEqualTo("LOW FINISHED");
    }

    @Test
    void aPlanItemThatShouldHaveStartedMovesUpOneLevel() throws Exception {
        Map<String, Object> item = task("Choose a photographer", today.plusDays(40), "AUTO", null);
        item.put("kind", "PLAN_ITEM");
        item.put("startDate", today.minusDays(3).toString());
        String id = (String) created(item).get("id");

        assertThat(levelOf(read(id))).isEqualTo("HIGH SHOULD_HAVE_STARTED");

        http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"IN_PROGRESS\"}");
        assertThat(levelOf(read(id))).isEqualTo("NORMAL DUE_WITHIN_60_DAYS");
    }

    @Test
    void todayIsTheDateWhereTheWeddingTakesPlace() throws Exception {
        // Kiritimati is 25 hours ahead of Pago Pago: its "yesterday" has not ended in Pago Pago.
        LocalDate yesterdayInKiritimati = LocalDate.now(ZoneId.of("Pacific/Kiritimati")).minusDays(1);
        String id = (String) created(task("Due on the date line", yesterdayInKiritimati, "AUTO", null)).get("id");

        setTimeZone("Pacific/Kiritimati");
        assertThat(levelOf(read(id))).isEqualTo("CRITICAL OVERDUE");

        setTimeZone("Pacific/Pago_Pago");
        assertThat(levelOf(read(id))).isEqualTo("CRITICAL DUE_WITHIN_3_DAYS");
    }

    @Test
    void listsOrderByTheLevelWorkedOutForToday() throws Exception {
        created(task("far away", today.plusDays(200), "AUTO", null));
        created(task("overdue", today.minusDays(2), "AUTO", null));
        created(task("chosen high", today.plusDays(200), "MANUAL", "HIGH"));
        created(task("next month", today.plusDays(30), "AUTO", null));

        List<String> byPriority = TestHttp.jsonList(http.getAs(anna, "/api/entries?sort=PRIORITY")).stream()
                .map(entry -> (String) entry.get("title"))
                .toList();

        assertThat(byPriority).containsExactly("overdue", "chosen high", "next month", "far away");
    }

    private static Map<String, Object> task(String title, LocalDate dueDate, String mode, String priority) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", "TASK");
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", null);
        entry.put("startDate", null);
        entry.put("dueDate", dueDate == null ? null : dueDate.toString());
        entry.put("status", "NOT_STARTED");
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", mode);
        entry.put("priority", priority);
        entry.put("parentId", null);
        return entry;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    /** Signs in afresh each time: a test that moves the clock by days outlives its access token. */
    private Map<String, Object> read(String id) throws Exception {
        HttpResponse<String> response = http.getAs(TestSignIn.signIn(http, ANNA).accessToken(), "/api/entries/" + id);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private static String levelOf(Map<String, Object> entry) {
        return entry.get("priority") + " " + entry.get("priorityReason");
    }

    private void setTimeZone(String zone) throws Exception {
        HttpResponse<String> response = http.putJsonAs(anna, "/api/wedding",
                "{\"date\":\"2027-06-19\",\"currency\":\"PLN\",\"timeZone\":\"" + zone + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
}
