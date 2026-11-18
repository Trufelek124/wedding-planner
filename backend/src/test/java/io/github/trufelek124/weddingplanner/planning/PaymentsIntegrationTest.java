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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** A task that carries money is a payment: what may be saved about it, and what marking it done records. */
class PaymentsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;
    /** Today where the test wedding takes place. */
    LocalDate today;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
    }

    // ---------------------------------------------------------------- saving

    @Test
    void aTaskCarriesAnAmountAndAKindAndComesBackAsSaved() throws Exception {
        String venue = categoryId("Venue");

        Map<String, Object> saved = created(with(task("Pay the venue deposit"),
                "amount", 250_000, "paymentKind", "DEPOSIT", "categoryId", venue, "dueDate", today.plusDays(30).toString()));
        Map<String, Object> read = ok(http.getAs(piotr, "/api/entries/" + id(saved)));

        assertThat(read).isEqualTo(saved);
        assertThat(read)
                .containsEntry("kind", "TASK")
                .containsEntry("amount", 250_000)
                .containsEntry("paymentKind", "DEPOSIT")
                .containsEntry("categoryId", venue)
                .containsEntry("paidOn", null);
    }

    @Test
    void anEntryWithoutMoneySaysSoWithNulls() throws Exception {
        Map<String, Object> plain = created(task("Call the florist"));

        // The fields are there and say "nothing", so that a client never has to guess from a missing one.
        assertThat(plain).containsKeys("amount", "paymentKind", "paidOn");
        assertThat(plain).containsEntry("amount", null).containsEntry("paymentKind", null).containsEntry("paidOn", null);
    }

    @Test
    void theKindOfAPaymentMayBeLeftOpen() throws Exception {
        Map<String, Object> saved = created(with(task("Pay the band"), "amount", 400_000));

        assertThat(saved).containsEntry("amount", 400_000).containsEntry("paymentKind", null);
    }

    @Test
    void anAmountIsKeptToTheLastGroszHoweverLarge() throws Exception {
        long nearlyTheMost = 999_999_999_999L;

        Map<String, Object> saved = created(with(task("Buy the castle"), "amount", nearlyTheMost));

        assertThat(((Number) saved.get("amount")).longValue()).isEqualTo(nearlyTheMost);
        assertThat(jdbc.queryForObject("SELECT amount FROM entry WHERE id = ?::uuid", Long.class, id(saved))).isEqualTo(nearlyTheMost);
        // A whole number in the database, not something that only looks like one.
        assertThat(jdbc.queryForObject(
                        "SELECT data_type FROM information_schema.columns WHERE table_name = 'entry' AND column_name = 'amount'",
                        String.class))
                .isEqualTo("bigint");
    }

    @Test
    void anAmountThatIsNotAWholeNumberOfTheSmallestUnitIsRefusedNotRounded() throws Exception {
        String request = TestHttp.jsonOf(with(task("Pay the florist"), "amount", 0)).replace("\"amount\":0", "\"amount\":1250.5");

        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", request);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat((String) TestHttp.json(response).get("type")).endsWith(":bad-request");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entry", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- paid

    @Test
    void markingAPaymentDoneRecordsTheDayAndReopeningItForgetsTheDay() throws Exception {
        String id = id(created(with(task("Pay the venue deposit"), "amount", 250_000)));

        Map<String, Object> paid = ok(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}"));
        assertThat(paid).containsEntry("status", "DONE").containsEntry("paidOn", today.toString());

        Map<String, Object> reopened = ok(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"IN_PROGRESS\"}"));
        assertThat(reopened).containsEntry("status", "IN_PROGRESS").containsEntry("paidOn", null);
    }

    @Test
    void theDayIsTheDateWhereTheWeddingTakesPlace() throws Exception {
        // Kiritimati is 25 hours ahead of Pago Pago: at any moment their dates differ by one or two days.
        setTimeZone("Pacific/Kiritimati");
        String first = id(created(with(task("Pay the boat"), "amount", 10_000)));
        assertThat(ok(http.putJsonAs(anna, "/api/entries/" + first + "/status", "{\"status\":\"DONE\"}")))
                .containsEntry("paidOn", LocalDate.now(ZoneId.of("Pacific/Kiritimati")).toString());

        setTimeZone("Pacific/Pago_Pago");
        String second = id(created(with(task("Pay the other boat"), "amount", 10_000)));
        assertThat(ok(http.putJsonAs(anna, "/api/entries/" + second + "/status", "{\"status\":\"DONE\"}")))
                .containsEntry("paidOn", LocalDate.now(ZoneId.of("Pacific/Pago_Pago")).toString());
    }

    @Test
    void aTaskWithoutMoneyHasNoPaidDateWhenItIsDone() throws Exception {
        String id = id(created(task("Call the florist")));

        assertThat(ok(http.putJsonAs(anna, "/api/entries/" + id + "/status", "{\"status\":\"DONE\"}"))).containsEntry("paidOn", null);
    }

    @Test
    void thePaidDateCanBeSetByHandAndThenStays() throws Exception {
        Map<String, Object> saved = created(with(task("Pay the photographer"), "amount", 300_000));
        String lastWeek = today.minusDays(6).toString();

        Map<String, Object> paid = changed(with(change(saved), "status", "DONE", "paidOn", lastWeek));
        assertThat(paid).containsEntry("paidOn", lastWeek);

        // A later save that does not mention the day keeps it, and so does ticking it off once more.
        Map<String, Object> renamed = changed(with(change(paid), "title", "Pay the photographer in full", "paidOn", null));
        assertThat(renamed).containsEntry("paidOn", lastWeek);
        assertThat(ok(http.putJsonAs(anna, "/api/entries/" + id(saved) + "/status", "{\"status\":\"DONE\"}")))
                .containsEntry("paidOn", lastWeek);
    }

    @Test
    void aPaymentSavedAsDoneWithoutADayIsPaidToday() throws Exception {
        Map<String, Object> saved = created(with(task("Paid in cash at the fair"), "amount", 5_000, "status", "DONE"));

        assertThat(saved).containsEntry("paidOn", today.toString());
    }

    @Test
    void takingTheAmountAwayMakesItAnOrdinaryTaskAgain() throws Exception {
        Map<String, Object> paid = created(with(task("Pay the DJ"), "amount", 120_000, "paymentKind", "FINAL", "status", "DONE"));

        Map<String, Object> plain = changed(with(change(paid), "amount", null, "paymentKind", null, "paidOn", null));

        assertThat(plain).containsEntry("amount", null).containsEntry("paymentKind", null).containsEntry("paidOn", null)
                .containsEntry("status", "DONE");
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    void onlyATaskCarriesMoney() throws Exception {
        Map<String, Object> planItem = with(task("Book the venue"), "kind", "PLAN_ITEM", "amount", 250_000);

        assertThat(refused(planItem)).containsExactly("amount: Only a task can carry an amount. Leave it empty here.");
    }

    @Test
    void theKindAndThePaidDateNeedAnAmount() throws Exception {
        assertThat(refused(with(task("Call the florist"), "paymentKind", "DEPOSIT")))
                .containsExactly("paymentKind: The kind of payment needs an amount. Enter one, or leave the kind empty.");
        assertThat(refused(with(task("Call the florist"), "status", "DONE", "paidOn", today.toString())))
                .containsExactly("paidOn: Only a payment has a paid date. Enter an amount, or leave the date empty.");
    }

    @Test
    void aPaidDateNeedsADonePaymentAndIsNotInTheFuture() throws Exception {
        assertThat(refused(with(task("Pay the band"), "amount", 400_000, "paidOn", today.toString())))
                .containsExactly("paidOn: A payment has a paid date once it is done. Mark it done, or leave the date empty.");
        assertThat(refused(with(task("Pay the band"), "amount", 400_000, "status", "DONE", "paidOn", today.plusDays(1).toString())))
                .containsExactly("paidOn: The paid date cannot be in the future.");
    }

    @Test
    void anAmountIsAboveZeroAndNotAbsurd() throws Exception {
        assertThat(refused(with(task("Pay nothing"), "amount", 0))).containsExactly("amount: Enter an amount above zero.");
        assertThat(refused(with(task("Get paid"), "amount", -100))).containsExactly("amount: Enter an amount above zero.");
        assertThat(refused(with(task("Buy the moon"), "amount", 1_000_000_000_001L))).containsExactly("amount: That amount is too large.");
    }

    @Test
    void aPaymentDoesNotBecomeAPlanItemAndLoseItsMoneyOnTheWay() throws Exception {
        Map<String, Object> payment = created(with(task("Pay the venue deposit"), "amount", 250_000));

        HttpResponse<String> refusal = http.postJsonAs(anna, "/api/entries/" + id(payment) + "/convert", "{\"kind\":\"PLAN_ITEM\"}");

        assertThat(refusal.statusCode()).as(refusal.body()).isEqualTo(400);
        assertThat((String) TestHttp.json(refusal).get("detail")).contains("Remove the amount first");
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(payment)))).containsEntry("kind", "TASK").containsEntry("amount", 250_000);

        // Once the person has taken the amount away, it converts like any task.
        changed(with(change(payment), "amount", null));
        assertThat(ok(http.postJsonAs(anna, "/api/entries/" + id(payment) + "/convert", "{\"kind\":\"PLAN_ITEM\"}")))
                .containsEntry("kind", "PLAN_ITEM");
    }

    // ---------------------------------------------------------------- lists and levels

    @Test
    void aPaymentIsTheSameEntryInTheTaskListAndCanBeAskedForOnItsOwn() throws Exception {
        String venue = categoryId("Venue");
        created(with(task("Pay the venue deposit"), "amount", 250_000, "categoryId", venue));
        created(with(task("Pay the band"), "amount", 400_000));
        created(with(task("Call the florist"), "categoryId", venue));
        created(with(task("Buy a guest book"), "kind", "PLAN_ITEM"));

        assertThat(titles("kind=TASK&sort=TITLE")).containsExactly("Call the florist", "Pay the band", "Pay the venue deposit");
        assertThat(titles("payment=true&sort=TITLE")).containsExactly("Pay the band", "Pay the venue deposit");
        assertThat(titles("payment=false&sort=TITLE")).containsExactly("Buy a guest book", "Call the florist");
        assertThat(titles("payment=true&categoryId=" + venue)).containsExactly("Pay the venue deposit");
        assertThat(titles("payment=true&withoutCategory=true")).containsExactly("Pay the band");
        assertThat(titles("withoutCategory=true&sort=TITLE")).containsExactly("Buy a guest book", "Pay the band");
    }

    @Test
    void aPaymentDueWithinSevenDaysIsCriticalWhereAnotherTaskIsHigh() throws Exception {
        String inSixDays = today.plusDays(6).toString();

        Map<String, Object> payment = created(with(task("Pay the florist"), "amount", 80_000, "dueDate", inSixDays));
        Map<String, Object> call = created(with(task("Call the florist"), "dueDate", inSixDays));
        Map<String, Object> later = created(with(task("Pay the band"), "amount", 400_000, "dueDate", today.plusDays(8).toString()));

        assertThat(payment).containsEntry("priority", "CRITICAL").containsEntry("priorityReason", "PAYMENT_DUE_WITHIN_7_DAYS");
        assertThat(call).containsEntry("priority", "HIGH").containsEntry("priorityReason", "DUE_WITHIN_14_DAYS");
        assertThat(later).containsEntry("priority", "HIGH").containsEntry("priorityReason", "DUE_WITHIN_14_DAYS");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> task(String title) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", "TASK");
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
        entry.put("amount", null);
        entry.put("paymentKind", null);
        entry.put("paidOn", null);
        return entry;
    }

    private static Map<String, Object> with(Map<String, Object> entry, Object... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            entry.put((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return entry;
    }

    /** What a client sends to change an entry it has read: every field, and the version it read. */
    private static Map<String, Object> change(Map<String, Object> saved) {
        Map<String, Object> request = new HashMap<>();
        for (String field : List.of("id", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds",
                "priorityMode", "parentId", "amount", "paymentKind", "paidOn", "version")) {
            request.put(field, saved.get(field));
        }
        request.put("priority", "MANUAL".equals(saved.get("priorityMode")) ? saved.get("priority") : null);
        return request;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private Map<String, Object> changed(Map<String, Object> request) throws Exception {
        return ok(http.putJsonAs(anna, "/api/entries/" + request.get("id"), TestHttp.jsonOf(request)));
    }

    private List<String> refused(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return TestHttp.fieldMessages(response);
    }

    private static Map<String, Object> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private List<String> titles(String query) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/entries?" + query);
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

    private void setTimeZone(String zone) throws Exception {
        Map<String, Object> details = new HashMap<>();
        details.put("date", "2027-06-19");
        details.put("currency", "PLN");
        details.put("timeZone", zone);
        HttpResponse<String> response = http.putJsonAs(anna, "/api/wedding", TestHttp.jsonOf(details));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
}
