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

/** What the first page shows: the days left, everything overdue, and what to turn to next. */
class HomeIntegrationTest extends PostgresIntegrationTest {

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
    void countsTheDaysToTheWeddingAndFollowsTheCalendar() throws Exception {
        setWedding(today.plusDays(214).toString(), "Europe/Warsaw");

        Map<String, Object> home = home();
        assertThat(home).containsEntry("today", today.toString()).containsEntry("weddingDate", today.plusDays(214).toString());
        assertThat(home.get("daysLeft")).isEqualTo(214);

        TestClock.advance(Duration.ofDays(214));
        anna = TestSignIn.signIn(http, ANNA).accessToken();
        assertThat(home().get("daysLeft")).isEqualTo(0);

        TestClock.advance(Duration.ofDays(3));
        anna = TestSignIn.signIn(http, ANNA).accessToken();
        assertThat(home().get("daysLeft")).isEqualTo(-3);
    }

    @Test
    void todayIsTheDateWhereTheWeddingTakesPlace() throws Exception {
        // Kiritimati is 25 hours ahead of Pago Pago: at any moment their dates differ by one or two days.
        LocalDate todayInKiritimati = LocalDate.now(ZoneId.of("Pacific/Kiritimati"));
        LocalDate todayInPagoPago = LocalDate.now(ZoneId.of("Pacific/Pago_Pago"));
        String weddingDay = todayInKiritimati.plusDays(10).toString();

        setWedding(weddingDay, "Pacific/Kiritimati");
        assertThat(home()).containsEntry("today", todayInKiritimati.toString());
        assertThat(home().get("daysLeft")).isEqualTo(10);

        setWedding(weddingDay, "Pacific/Pago_Pago");
        assertThat(home()).containsEntry("today", todayInPagoPago.toString());
        assertThat((int) home().get("daysLeft")).isGreaterThan(10);
    }

    @Test
    void hasNoCountdownWhileTheDayIsNotFixed() throws Exception {
        setWedding(null, "Europe/Warsaw");

        Map<String, Object> home = home();

        // Both are there and say "nothing", so that a client never has to guess from a missing field.
        assertThat(home).containsKeys("weddingDate", "daysLeft");
        assertThat(home.get("weddingDate")).isNull();
        assertThat(home.get("daysLeft")).isNull();
        assertThat(home).containsEntry("today", today.toString());
    }

    @Test
    void listsEverythingUnfinishedWhoseDateHasPassedTheLongestOverdueFirst() throws Exception {
        created(entry("TASK", "due today", null, today, "NOT_STARTED", "AUTO", null));
        created(entry("TASK", "a day late", null, today.minusDays(1), "NOT_STARTED", "AUTO", null));
        created(entry("PLAN_ITEM", "a plan item that ended last week", today.minusDays(20), today.minusDays(7), "IN_PROGRESS", "AUTO", null));
        created(entry("TASK", "late but done", null, today.minusDays(3), "DONE", "AUTO", null));
        created(entry("TASK", "late but dropped", null, today.minusDays(3), "DROPPED", "AUTO", null));
        created(entry("TASK", "late, and marked low by hand", null, today.minusDays(30), "NOT_STARTED", "MANUAL", "LOW"));
        created(entry("TASK", "no date", null, null, "NOT_STARTED", "AUTO", null));

        assertThat(titles(home(), "overdue")).containsExactly(
                "late, and marked low by hand", "a plan item that ended last week", "a day late");
        // What is overdue is not named a second time under what comes next.
        assertThat(titles(home(), "next")).containsExactly("due today", "no date");
    }

    @Test
    void namesTheFiveEntriesToTurnToNextByPriorityAndThenByDate() throws Exception {
        created(entry("TASK", "next year", null, today.plusDays(300), "NOT_STARTED", "AUTO", null));
        created(entry("TASK", "in ten days", null, today.plusDays(10), "NOT_STARTED", "AUTO", null));
        created(entry("TASK", "in two days", null, today.plusDays(2), "IN_PROGRESS", "AUTO", null));
        created(entry("TASK", "tomorrow", null, today.plusDays(1), "NOT_STARTED", "AUTO", null));
        created(entry("PLAN_ITEM", "a plan item ending in twelve days", today.plusDays(1), today.plusDays(12), "NOT_STARTED", "AUTO", null));
        created(entry("TASK", "next year, but critical by hand", null, today.plusDays(301), "NOT_STARTED", "MANUAL", "CRITICAL"));
        created(entry("TASK", "in a month", null, today.plusDays(30), "NOT_STARTED", "AUTO", null));
        created(entry("TASK", "tomorrow, but done", null, today.plusDays(1), "DONE", "AUTO", null));

        Map<String, Object> home = home();

        // Critical first (by date within the level), then high; the sixth and seventh do not fit.
        assertThat(titles(home, "next")).containsExactly(
                "tomorrow", "in two days", "next year, but critical by hand", "in ten days", "a plan item ending in twelve days");
        assertThat(titles(home, "overdue")).isEmpty();
        // An entry here is the same as anywhere else: it carries its level, the reason and its version.
        Map<String, Object> first = entries(home, "next").get(0);
        assertThat(first).containsEntry("priority", "CRITICAL").containsEntry("priorityReason", "DUE_WITHIN_3_DAYS").containsKey("version");
    }

    @Test
    void whatWasNextBecomesOverdueWhenItsDayHasPassed() throws Exception {
        created(entry("TASK", "due tomorrow", null, today.plusDays(1), "NOT_STARTED", "AUTO", null));
        assertThat(titles(home(), "next")).containsExactly("due tomorrow");
        assertThat(titles(home(), "overdue")).isEmpty();

        TestClock.advance(Duration.ofDays(2));
        anna = TestSignIn.signIn(http, ANNA).accessToken();

        assertThat(titles(home(), "overdue")).containsExactly("due tomorrow");
        assertThat(titles(home(), "next")).isEmpty();
    }

    @Test
    void isEmptyButCompleteForAPlanWithNothingInIt() throws Exception {
        Map<String, Object> home = home();

        assertThat(home).containsOnlyKeys("today", "weddingDate", "daysLeft", "overdue", "next", "budget", "nextPayments");
        assertThat(titles(home, "overdue")).isEmpty();
        assertThat(titles(home, "next")).isEmpty();
        assertThat(titles(home, "nextPayments")).isEmpty();
        assertThat(budget(home)).containsEntry("total", null).containsEntry("planned", 0).containsEntry("committed", 0).containsEntry("paid", 0);
    }

    @Test
    void showsTheBudgetAtAGlance() throws Exception {
        String venue = categoryId("Venue");
        String music = categoryId("Music");
        putBudget(0, 6_000_000L, Map.of(venue, 2_000_000L, music, 500_000L));
        created(payment("Venue deposit", 500_000, venue, today.plusDays(10), "DONE"));
        created(payment("Venue, the rest", 1_500_000, venue, today.plusDays(100), "NOT_STARTED"));
        created(payment("The band", 450_000, music, null, "IN_PROGRESS"));
        created(payment("A second band, given up", 300_000, music, null, "DROPPED"));
        created(payment("Rings", 250_000, null, today.plusDays(40), "NOT_STARTED"));
        created(entry("TASK", "no money in this one", null, today.plusDays(5), "NOT_STARTED", "AUTO", null));

        Map<String, Object> glance = budget(home());

        assertThat(glance).containsOnlyKeys("total", "planned", "committed", "paid");
        assertThat(glance)
                .containsEntry("total", 6_000_000)
                .containsEntry("planned", 2_500_000)
                .containsEntry("committed", 2_700_000)
                .containsEntry("paid", 500_000);
        // The same four sums as the budget's own page.
        Map<String, Object> page = TestHttp.json(http.getAs(anna, "/api/budget"));
        assertThat(glance).isEqualTo(Map.of(
                "total", page.get("total"), "planned", page.get("planned"), "committed", page.get("committed"), "paid", page.get("paid")));
    }

    @Test
    void namesTheNextPaymentsStillToBeMadeTheSoonestDueFirst() throws Exception {
        created(payment("in a month", 10_000, null, today.plusDays(30), "NOT_STARTED"));
        created(payment("some day", 10_000, null, null, "NOT_STARTED"));
        created(payment("late", 10_000, null, today.minusDays(4), "IN_PROGRESS"));
        created(payment("tomorrow", 10_000, null, today.plusDays(1), "NOT_STARTED"));
        created(payment("paid already", 10_000, null, today.plusDays(2), "DONE"));
        created(payment("given up", 10_000, null, today.plusDays(2), "DROPPED"));
        created(payment("in a week", 10_000, null, today.plusDays(7), "NOT_STARTED"));
        created(payment("next year", 10_000, null, today.plusDays(300), "NOT_STARTED"));
        created(entry("TASK", "due today, but no payment", null, today, "NOT_STARTED", "AUTO", null));

        Map<String, Object> home = home();

        // Five at most; one that is late leads the list, one without a date would come last and does not fit here.
        assertThat(titles(home, "nextPayments")).containsExactly("late", "tomorrow", "in a week", "in a month", "next year");
        // A payment that is late is overdue like any other entry.
        assertThat(titles(home, "overdue")).containsExactly("late");
        Map<String, Object> first = entries(home, "nextPayments").get(0);
        assertThat(first).containsEntry("amount", 10_000).containsEntry("paidOn", null).containsKey("version");

        String id = (String) first.get("id");
        assertThat(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
        assertThat(titles(home(), "nextPayments")).containsExactly("tomorrow", "in a week", "in a month", "next year", "some day");
    }

    @Test
    void saysThatTheWeddingIsNotSetUpToSomeoneWhoHasNone() throws Exception {
        jdbc.update("DELETE FROM wedding");

        HttpResponse<String> response = http.getAs(anna, "/api/home");

        assertThat((String) TestHttp.json(response).get("type")).endsWith(":wedding-not-set-up");
    }

    private Map<String, Object> home() throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/home");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> home, String list) {
        return (List<Map<String, Object>>) home.get(list);
    }

    private static List<String> titles(Map<String, Object> home, String list) {
        return entries(home, list).stream().map(entry -> (String) entry.get("title")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> budget(Map<String, Object> home) {
        return (Map<String, Object>) home.get("budget");
    }

    private static Map<String, Object> payment(String title, long amount, String categoryId, LocalDate dueDate, String status) {
        Map<String, Object> entry = entry("TASK", title, null, dueDate, status, "AUTO", null);
        entry.put("amount", amount);
        entry.put("categoryId", categoryId);
        return entry;
    }

    private void putBudget(int version, Long total, Map<String, Long> plannedByCategory) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("version", version);
        request.put("total", total);
        request.put("planned", plannedByCategory.entrySet().stream()
                .map(one -> Map.of("categoryId", one.getKey(), "amount", one.getValue()))
                .toList());
        HttpResponse<String> response = http.putJsonAs(anna, "/api/budget", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private String categoryId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/categories")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> (String) category.get("id"))
                .findFirst()
                .orElseThrow();
    }

    private void setWedding(String date, String zone) throws Exception {
        Map<String, Object> details = new HashMap<>();
        details.put("date", date);
        details.put("currency", "PLN");
        details.put("timeZone", zone);
        HttpResponse<String> response = http.putJsonAs(anna, "/api/wedding", TestHttp.jsonOf(details));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static Map<String, Object> entry(
            String kind, String title, LocalDate startDate, LocalDate dueDate, String status, String mode, String priority) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", kind);
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", null);
        entry.put("startDate", startDate == null ? null : startDate.toString());
        entry.put("dueDate", dueDate == null ? null : dueDate.toString());
        entry.put("status", status);
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", mode);
        entry.put("priority", priority);
        entry.put("parentId", null);
        return entry;
    }

    private void created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }
}
