package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The budget: what is planned, and what the payments come to, in total and per category. */
class BudgetIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;
    String venue;
    String music;
    String attire;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        venue = categoryId("Venue");
        music = categoryId("Music");
        attire = categoryId("Attire");
    }

    // ---------------------------------------------------------------- reading

    @Test
    void aBudgetNobodyHasTouchedIsEmptyButComplete() throws Exception {
        Map<String, Object> budget = budget(anna);

        assertThat(budget).containsOnlyKeys("total", "planned", "committed", "paid", "categories", "uncategorised", "version");
        assertThat(budget)
                .containsEntry("total", null)
                .containsEntry("planned", 0)
                .containsEntry("committed", 0)
                .containsEntry("paid", 0)
                .containsEntry("version", 0);
        // Every category is there, in the couple's order, and says "nothing yet".
        assertThat(lines(budget)).extracting(line -> line.get("name")).startsWith("Venue", "Food and drink", "Attire").hasSize(12);
        assertThat(lines(budget)).allSatisfy(line -> assertThat(line)
                .containsOnlyKeys("categoryId", "name", "planned", "committed", "paid", "payments")
                .containsEntry("planned", null)
                .containsEntry("committed", 0)
                .containsEntry("paid", 0)
                .containsEntry("payments", 0));
        assertThat(budget.get("uncategorised")).isEqualTo(Map.of("committed", 0, "paid", 0, "payments", 0));
    }

    @Test
    void committedIsEveryPaymentNotDroppedAndPaidIsWhatIsDone() throws Exception {
        created(payment("Venue deposit", 500_000, venue, "DONE"));
        created(payment("Venue, second part", 700_000, venue, "IN_PROGRESS"));
        created(payment("Venue, the rest", 800_000, venue, "NOT_STARTED"));
        created(payment("The band", 450_000, music, "NOT_STARTED"));
        created(payment("A second band, given up", 300_000, music, "DROPPED"));
        created(payment("Rings", 250_000, null, "DONE"));
        created(payment("Tips", 40_000, null, "NOT_STARTED"));
        created(task("Call the venue about parking", venue));

        Map<String, Object> budget = budget(piotr);

        assertThat(line(budget, "Venue")).containsEntry("committed", 2_000_000).containsEntry("paid", 500_000).containsEntry("payments", 3);
        assertThat(line(budget, "Music")).containsEntry("committed", 450_000).containsEntry("paid", 0).containsEntry("payments", 1);
        assertThat(line(budget, "Attire")).containsEntry("committed", 0).containsEntry("paid", 0).containsEntry("payments", 0);
        assertThat(budget.get("uncategorised")).isEqualTo(Map.of("committed", 290_000, "paid", 250_000, "payments", 2));
        assertThat(budget).containsEntry("committed", 2_740_000).containsEntry("paid", 750_000);
    }

    @Test
    void aPaymentIsOneEntryTickingItOffInTheTaskListMovesItsMoneyToPaid() throws Exception {
        String id = (String) created(payment("The band", 450_000, music, "NOT_STARTED")).get("id");
        assertThat(line(budget(anna), "Music")).containsEntry("committed", 450_000).containsEntry("paid", 0);

        assertThat(http.putJsonAs(piotr, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
        assertThat(line(budget(anna), "Music")).containsEntry("committed", 450_000).containsEntry("paid", 450_000);

        assertThat(http.putJsonAs(piotr, "/api/entries/" + id + "/status", "{\"status\":\"DROPPED\"}").statusCode()).isEqualTo(200);
        assertThat(line(budget(anna), "Music")).containsEntry("committed", 0).containsEntry("paid", 0).containsEntry("payments", 0);

        assertThat(http.deleteAs(piotr, "/api/entries/" + id).statusCode()).isEqualTo(204);
        assertThat(budget(anna)).containsEntry("committed", 0).containsEntry("paid", 0);
    }

    @Test
    void sumsAreExactToTheLastGrosz() throws Exception {
        created(payment("One grosz", 1, venue, "DONE"));
        created(payment("Another", 1, venue, "DONE"));
        created(payment("And a third", 1, venue, "NOT_STARTED"));
        created(payment("Nearly the most there can be", 999_999_999_999L, venue, "NOT_STARTED"));

        Map<String, Object> budget = budget(anna);

        assertThat(((Number) line(budget, "Venue").get("committed")).longValue()).isEqualTo(1_000_000_000_002L);
        assertThat(((Number) budget.get("committed")).longValue()).isEqualTo(1_000_000_000_002L);
        assertThat(budget).containsEntry("paid", 2);
        // As JSON it is a whole number, with no exponent and no fraction.
        assertThat(http.getAs(anna, "/api/budget").body()).contains("\"committed\":1000000000002");
    }

    // ---------------------------------------------------------------- planning

    @Test
    void theTotalAndThePlannedAmountsAreSavedTogether() throws Exception {
        Map<String, Object> saved = saved(change(0, 6_000_000L, plan(venue, 2_000_000L), plan(music, 500_000L), plan(attire, 0L)));

        assertThat(saved).containsEntry("total", 6_000_000).containsEntry("planned", 2_500_000).containsEntry("version", 1);
        assertThat(line(saved, "Venue")).containsEntry("planned", 2_000_000);
        assertThat(line(saved, "Music")).containsEntry("planned", 500_000);
        // Zero is something the couple said; a category they said nothing about has no planned amount.
        assertThat(line(saved, "Attire")).containsEntry("planned", 0);
        assertThat(line(saved, "Guests")).containsEntry("planned", null);
        assertThat(budget(piotr)).isEqualTo(saved);
    }

    @Test
    void aChangeSaysEverythingWhatItLeavesOutIsNoLongerPlanned() throws Exception {
        saved(change(0, 6_000_000L, plan(venue, 2_000_000L), plan(music, 500_000L)));

        Map<String, Object> narrowed = saved(change(1, null, plan(music, 600_000L)));

        assertThat(narrowed).containsEntry("total", null).containsEntry("planned", 600_000).containsEntry("version", 2);
        assertThat(line(narrowed, "Venue")).containsEntry("planned", null);
        assertThat(line(narrowed, "Music")).containsEntry("planned", 600_000);
    }

    @Test
    void theCoupleMayPlanMoreInTheCategoriesThanInTotalTheNumbersSaySo() throws Exception {
        Map<String, Object> saved = saved(change(0, 1_000_000L, plan(venue, 2_000_000L)));

        assertThat(saved).containsEntry("total", 1_000_000).containsEntry("planned", 2_000_000);
    }

    @Test
    void aChangeBasedOnAnOutdatedBudgetIsRefusedAndOverwritesNothing() throws Exception {
        saved(change(0, 6_000_000L, plan(venue, 2_000_000L)));

        // Piotr still has the untouched budget on his screen.
        HttpResponse<String> late = http.putJsonAs(piotr, "/api/budget", TestHttp.jsonOf(change(0, 1L, plan(music, 1L))));

        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(late).get("type")).endsWith(":changed-meanwhile");
        Map<String, Object> kept = budget(piotr);
        assertThat(kept).containsEntry("total", 6_000_000).containsEntry("version", 1);
        assertThat(line(kept, "Venue")).containsEntry("planned", 2_000_000);
        assertThat(line(kept, "Music")).containsEntry("planned", null);
        // And a version that never existed is no better.
        assertThat(http.putJsonAs(piotr, "/api/budget", TestHttp.jsonOf(change(7, 1L))).statusCode()).isEqualTo(409);
    }

    @Test
    void whatCannotBeABudgetIsRefusedWithWordsForThePerson() throws Exception {
        String nobodys = "00000000-0000-4000-8000-000000000000";

        assertThat(refused(change(0, 1L, plan(nobodys, 1L)))).containsExactly("planned[0].categoryId: Choose one of the categories.");
        assertThat(refused(change(0, 1L, plan(venue, 1L), plan(music, 2L), plan(venue, 3L))))
                .containsExactly("planned[2].categoryId: This category is named twice.");
        assertThat(refused(change(0, -1L))).containsExactly("total: Enter an amount, or zero.");
        assertThat(refused(change(0, 1_000_000_000_001L))).containsExactly("total: That amount is too large.");
        assertThat(refused(change(0, null, plan(venue, -5L)))).containsExactly("planned[0].amount: Enter an amount, or zero.");
        assertThat(refused(change(0, null, plan(venue, null)))).containsExactly("planned[0].amount: Enter an amount, or zero.");
        // Nothing of all that was saved.
        assertThat(budget(anna)).containsEntry("version", 0).containsEntry("total", null).containsEntry("planned", 0);
    }

    @Test
    void aFractionOfTheSmallestUnitIsRefusedNotRounded() throws Exception {
        String request = TestHttp.jsonOf(change(0, 7L)).replace("\"total\":7", "\"total\":6000000.5");

        HttpResponse<String> response = http.putJsonAs(anna, "/api/budget", request);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(budget(anna)).containsEntry("total", null);
    }

    // ---------------------------------------------------------------- categories come and go

    @Test
    void aCategoryThatGoesTakesItsPlannedAmountAlongAndLeavesItsPaymentsWithoutOne() throws Exception {
        saved(change(0, null, plan(venue, 2_000_000L), plan(music, 500_000L)));
        created(payment("The band", 450_000, music, "DONE"));

        assertThat(http.deleteAs(anna, "/api/categories/" + music).statusCode()).isEqualTo(204);

        Map<String, Object> budget = budget(anna);
        assertThat(lines(budget)).extracting(line -> line.get("name")).doesNotContain("Music").hasSize(11);
        assertThat(budget).containsEntry("planned", 2_000_000).containsEntry("committed", 450_000).containsEntry("paid", 450_000);
        assertThat(budget.get("uncategorised")).isEqualTo(Map.of("committed", 450_000, "paid", 450_000, "payments", 1));
    }

    @Test
    void aNewCategoryAppearsInTheBudgetAtOnce() throws Exception {
        assertThat(http.postJsonAs(anna, "/api/categories", "{\"name\":\"Fireworks\"}").statusCode()).isEqualTo(201);

        assertThat(line(budget(anna), "Fireworks")).containsEntry("planned", null).containsEntry("committed", 0);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> budget(String as) throws Exception {
        HttpResponse<String> response = http.getAs(as, "/api/budget");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> budget) {
        return (List<Map<String, Object>>) budget.get("categories");
    }

    private static Map<String, Object> line(Map<String, Object> budget, String category) {
        return lines(budget).stream().filter(line -> category.equals(line.get("name"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> plan(String categoryId, Long amount) {
        Map<String, Object> planned = new HashMap<>();
        planned.put("categoryId", categoryId);
        planned.put("amount", amount);
        return planned;
    }

    @SafeVarargs
    private static Map<String, Object> change(int version, Long total, Map<String, Object>... planned) {
        Map<String, Object> request = new HashMap<>();
        request.put("version", version);
        request.put("total", total);
        request.put("planned", new ArrayList<>(List.of(planned)));
        return request;
    }

    private Map<String, Object> saved(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.putJsonAs(anna, "/api/budget", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private List<String> refused(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.putJsonAs(anna, "/api/budget", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return TestHttp.fieldMessages(response);
    }

    private static Map<String, Object> task(String title, String categoryId) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", "TASK");
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", categoryId);
        entry.put("startDate", null);
        entry.put("dueDate", null);
        entry.put("status", "NOT_STARTED");
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", "AUTO");
        entry.put("priority", null);
        entry.put("parentId", null);
        return entry;
    }

    private static Map<String, Object> payment(String title, long amount, String categoryId, String status) {
        Map<String, Object> entry = task(title, categoryId);
        entry.put("amount", amount);
        entry.put("status", status);
        return entry;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private String categoryId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/categories")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> (String) category.get("id"))
                .findFirst()
                .orElseThrow();
    }
}
