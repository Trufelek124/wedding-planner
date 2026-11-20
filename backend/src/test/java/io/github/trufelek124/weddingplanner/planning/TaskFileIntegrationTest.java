package io.github.trufelek124.weddingplanner.planning;

import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.changesOf;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.file;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.fileProblems;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.problemsOf;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.row;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.rowOf;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.rows;
import static io.github.trufelek124.weddingplanner.planning.TaskFileFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** A file of tasks (S06): what a row does, through the real endpoints. */
class TaskFileIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TaskFileFixture files;
    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        files = new TaskFileFixture(TestWedding.fresh(jdbc, port));
        http = files.http;
        anna = files.wedding.anna;
        piotr = files.wedding.piotr;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ---------------------------------------------------------------- the published example

    @Test
    void thePublishedExampleIsTakenAsItIs() throws Exception {
        String example = TaskFileFixture.read("wedding-planner-tasks-1.example.json");

        Map<String, Object> check = files.checked(example);
        assertThat(check).containsEntry("applied", false).containsEntry("canBeApplied", true)
                .containsEntry("added", 8).containsEntry("changed", 0).containsEntry("unchanged", 0).containsEntry("refused", 0);
        assertThat(rows(check)).extracting(row -> row.get("outcome")).containsOnly("NEW");
        assertThat(rows(check)).extracting(row -> row.get("row")).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        // The wedding has no categories yet and the starter list is not offered yet: Venue, Formalities and Guests are
        // in it, Photography is not.
        assertThat(strings(check.get("newCategories"))).containsExactly("Photography");
        // Checking changes nothing, however often.
        files.checked(example);
        assertThat(count("entry")).isZero();
        assertThat(count("category")).isZero();

        Map<String, Object> applied = files.applied(example);
        assertThat(applied).containsEntry("applied", true).containsEntry("added", 8);
        assertThat(files.tasks()).hasSize(8).containsKeys("Order the rings", "Pay the venue's deposit");
        assertThat(files.task("Pay the venue's second instalment"))
                .containsEntry("amount", 1_200_000).containsEntry("paymentKind", "INSTALMENT").containsEntry("dueDate", "2027-01-15");
        assertThat(files.task("Pay the photographer's booking fee"))
                .containsEntry("amount", 150_000).containsEntry("status", "DONE").containsEntry("paidOn", "2026-10-15");
        assertThat(files.categoryNamed("Photography")).isNotNull();

        // Again: nothing twice, nothing changes, every row says so.
        Map<String, Object> versions = versions();
        Map<String, Object> again = files.applied(example);
        assertThat(again).containsEntry("added", 0).containsEntry("changed", 0).containsEntry("unchanged", 8)
                .containsEntry("canBeApplied", true);
        assertThat(rows(again)).extracting(row -> row.get("outcome")).containsOnly("UNCHANGED");
        assertThat(count("entry")).isEqualTo(8);
        assertThat(versions()).isEqualTo(versions);
        assertThat(files.checked(example)).containsEntry("unchanged", 8);
    }

    private Map<String, Object> versions() throws Exception {
        Map<String, Object> versions = new java.util.TreeMap<>();
        files.tasks().forEach((title, task) -> versions.put(title, task.get("version") + "/" + task.get("updatedAt")));
        return versions;
    }

    // ---------------------------------------------------------------- a new task

    @Test
    void aNewTaskWithOnlyAKeyAndATitleStartsAsOnTheForm() throws Exception {
        Map<String, Object> report = files.applied(file(row("a", "Call the florist")));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "NEW").containsEntry("key", "a").containsEntry("title", "Call the florist");
        assertThat(changesOf(rowOf(report, 1))).isEmpty();
        Map<String, Object> task = files.task("Call the florist");
        assertThat(task).containsEntry("kind", "TASK").containsEntry("notes", "").containsEntry("dueDate", null)
                .containsEntry("status", "NOT_STARTED").containsEntry("priorityMode", "AUTO").containsEntry("categoryId", null)
                .containsEntry("vendorId", null).containsEntry("amount", null).containsEntry("paymentKind", null)
                .containsEntry("paidOn", null).containsEntry("parentId", null).containsEntry("version", 0);
        assertThat(task.get("assigneeIds")).isEqualTo(List.of());
        assertThat(jdbc.queryForObject("SELECT task_key FROM entry", String.class)).isEqualTo("a");
        assertThat(jdbc.queryForObject("SELECT created_by::text FROM entry", String.class)).isEqualTo(files.wedding.annaId);
    }

    @Test
    void everyFieldGivenMakesATaskWithEveryField() throws Exception {
        files.categories();
        String vendor = files.vendor("Riverside Barn");
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
        String paidOn = today.minusDays(3).toString();

        Map<String, Object> check = files.checked(file(row("all", "Pay the venue", "'notes':'By transfer.'",
                "'dueDate':'2027-01-15'", "'status':'DONE'", "'priority':'HIGH'", "'category':' venue '", "'vendor':' riverside BARN'",
                "'amount':{'value':'12000.00','currency':'PLN'}", "'paymentKind':'INSTALMENT'", "'paidOn':'" + paidOn + "'")));
        assertThat(check).containsEntry("canBeApplied", true).containsEntry("added", 1);
        assertThat(strings(check.get("newCategories"))).isEmpty();

        files.applied(file(row("all", "Pay the venue", "'notes':'By transfer.'",
                "'dueDate':'2027-01-15'", "'status':'DONE'", "'priority':'HIGH'", "'category':' venue '", "'vendor':' riverside BARN'",
                "'amount':{'value':'12000.00','currency':'PLN'}", "'paymentKind':'INSTALMENT'", "'paidOn':'" + paidOn + "'")));
        Map<String, Object> task = files.task("Pay the venue");
        assertThat(task).containsEntry("notes", "By transfer.").containsEntry("dueDate", "2027-01-15")
                .containsEntry("status", "DONE").containsEntry("priorityMode", "MANUAL").containsEntry("priority", "HIGH")
                .containsEntry("categoryId", files.categoryNamed("Venue")).containsEntry("vendorId", vendor)
                .containsEntry("amount", 1_200_000).containsEntry("paymentKind", "INSTALMENT").containsEntry("paidOn", paidOn);
        assertThat(task.get("completedAt")).isNotNull();
    }

    // ---------------------------------------------------------------- left out against null

    private static final String BASE = "'notes':'Old notes','dueDate':'2027-01-15','priority':'HIGH','category':'Venue',"
            + "'vendor':'Riverside Barn','amount':{'value':'6000.00','currency':'PLN'},'paymentKind':'DEPOSIT'";

    private String base(String... without) {
        String made = BASE;
        for (String field : without) {
            made = java.util.Arrays.stream(made.split(",(?=')")).filter(part -> !part.startsWith("'" + field + "'"))
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return made;
    }

    static Stream<Arguments> fieldsThatCanBeEmptied() {
        return Stream.of(
                Arguments.of("dueDate", "dueDate: 2027-01-15 -> null", "dueDate", "2027-01-15"),
                Arguments.of("priority", "priority: HIGH -> null", "priorityMode", "AUTO"),
                Arguments.of("category", "category: Venue -> null", "categoryId", null),
                Arguments.of("vendor", "vendor: Riverside Barn -> null", "vendorId", null));
    }

    @ParameterizedTest
    @MethodSource("fieldsThatCanBeEmptied")
    void aFieldLeftOutIsNotTouchedAndTheSameFieldGivenAsNullIsEmptied(String field, String change, String task, Object afterLeftOut)
            throws Exception {
        files.apply(anna, file(row("k", "A task", base())));
        files.vendor("Riverside Barn");
        files.applied(file(row("k", "A task", base())));
        Map<String, Object> before = files.task("A task");

        Map<String, Object> left = files.applied(file(row("k", "A task")));
        assertThat(rowOf(left, 1)).containsEntry("outcome", "UNCHANGED");
        assertThat(files.task("A task")).isEqualTo(before);

        Map<String, Object> emptied = files.applied(file(row("k", "A task", "'" + field + "':null")));
        assertThat(rowOf(emptied, 1)).containsEntry("outcome", "CHANGED");
        assertThat(changesOf(rowOf(emptied, 1))).containsExactly(change);
        assertThat(files.task("A task").get(task)).isNotEqualTo(before.get(task));
        assertThat(files.task("A task")).containsEntry("version", (Integer) before.get("version") + 1);
    }

    @Test
    void aTaskThatWasMadeByFileHasNoVendorBeforeTheVendorExists() throws Exception {
        // The first upload in the test above is refused (no such vendor): nothing came of it.
        HttpResponse<String> refused = files.apply(anna, file(row("k", "A task", "'vendor':'Riverside Barn'")));
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(files.tasks()).isEmpty();
    }

    @Test
    void theAmountEmptiedWithItsKindEmptiesTheTask() throws Exception {
        files.categories();
        files.applied(file(row("k", "A payment", "'amount':{'value':'6000','currency':'PLN'}", "'paymentKind':'DEPOSIT'")));

        Map<String, Object> left = files.applied(file(row("k", "A payment")));
        assertThat(rowOf(left, 1)).containsEntry("outcome", "UNCHANGED");

        // The amount alone, with its kind staying, is the form's own refusal.
        HttpResponse<String> kindStays = files.check(anna, file(row("k", "A payment", "'amount':null")));
        Map<String, Object> report = TestHttp.json(kindStays);
        assertThat(rowOf(report, 1)).containsEntry("outcome", "REFUSED");
        assertThat(problemsOf(rowOf(report, 1))).containsExactly(
                "paymentKind: The kind of payment needs an amount. Enter one, or leave the kind empty.");

        Map<String, Object> both = files.applied(file(row("k", "A payment", "'amount':null", "'paymentKind':null")));
        assertThat(changesOf(rowOf(both, 1))).containsExactly("amount: 600000 -> null", "paymentKind: DEPOSIT -> null");
        assertThat(files.task("A payment")).containsEntry("amount", null).containsEntry("paymentKind", null);
    }

    @Test
    void anEmptyTextEmptiesTheNotes() throws Exception {
        files.applied(file(row("k", "A task", "'notes':'Something to know'")));

        Map<String, Object> left = files.applied(file(row("k", "A task")));
        assertThat(rowOf(left, 1)).containsEntry("outcome", "UNCHANGED");
        assertThat(files.task("A task")).containsEntry("notes", "Something to know");

        Map<String, Object> emptied = files.applied(file(row("k", "A task", "'notes':''")));
        assertThat(changesOf(rowOf(emptied, 1))).containsExactly("notes: Something to know -> ");
        assertThat(files.task("A task")).containsEntry("notes", "");
    }

    @Test
    void aPaidDateThatTheTaskHasStaysWhenTheRowLeavesItOutOrGivesNull() throws Exception {
        files.applied(file(row("k", "A payment", "'status':'DONE'", "'amount':{'value':'100','currency':'PLN'}",
                "'paidOn':'2026-10-01'")));

        assertThat(rowOf(files.applied(file(row("k", "A payment"))), 1)).containsEntry("outcome", "UNCHANGED");
        // A done payment always has its paid date: null leaves the day that is recorded.
        assertThat(rowOf(files.applied(file(row("k", "A payment", "'paidOn':null"))), 1)).containsEntry("outcome", "UNCHANGED");
        assertThat(files.task("A payment")).containsEntry("paidOn", "2026-10-01");
    }

    // ---------------------------------------------------------------- a change of each field alone

    static Stream<Arguments> oneFieldChanged() {
        return Stream.of(
                Arguments.of("title", "'Pay the first deposit'", "title: Pay the deposit -> Pay the first deposit"),
                Arguments.of("notes", "'New notes'", "notes: Old notes -> New notes"),
                Arguments.of("dueDate", "'2027-02-01'", "dueDate: 2027-01-15 -> 2027-02-01"),
                Arguments.of("status", "'IN_PROGRESS'", "status: NOT_STARTED -> IN_PROGRESS"),
                Arguments.of("priority", "'LOW'", "priority: HIGH -> LOW"),
                Arguments.of("category", "'Music'", "category: Venue -> Music"),
                Arguments.of("vendor", "'Second Vendor'", "vendor: Riverside Barn -> Second Vendor"),
                Arguments.of("amount", "{'value':'7000.50','currency':'PLN'}", "amount: 600000 -> 700050"),
                Arguments.of("amount", "{'value':7000.5,'currency':'PLN'}", "amount: 600000 -> 700050"),
                Arguments.of("paymentKind", "'FINAL'", "paymentKind: DEPOSIT -> FINAL"));
    }

    @ParameterizedTest
    @MethodSource("oneFieldChanged")
    void aChangeOfOneFieldIsTheOnlyChangeSaidAndTheOnlyOneMade(String field, String value, String change) throws Exception {
        files.categories();
        files.vendor("Riverside Barn");
        files.vendor("Second Vendor");
        files.applied(file(row("k", "Pay the deposit", base())));
        Map<String, Object> before = files.task("Pay the deposit");

        Map<String, Object> report = files.applied(file(row("k", field.equals("title") ? "Pay the first deposit" : "Pay the deposit",
                field.equals("title") ? "'notes':'Old notes'" : "'" + field + "':" + value)));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "CHANGED");
        assertThat(changesOf(rowOf(report, 1))).containsExactly(change);
        assertThat(report).containsEntry("changed", 1);
        String title = field.equals("title") ? "Pay the first deposit" : "Pay the deposit";
        assertThat(files.task(title)).containsEntry("version", (Integer) before.get("version") + 1);
        assertThat(files.task(title).get("id")).isEqualTo(before.get("id"));
    }

    @Test
    void aPaidDateChangedAloneIsTheOnlyChange() throws Exception {
        files.applied(file(row("k", "A payment", "'status':'DONE'", "'amount':{'value':'100','currency':'PLN'}",
                "'paidOn':'2026-10-01'")));

        Map<String, Object> report = files.applied(file(row("k", "A payment", "'paidOn':'2026-10-02'")));

        assertThat(changesOf(rowOf(report, 1))).containsExactly("paidOn: 2026-10-01 -> 2026-10-02");
    }

    @Test
    void makingAPaymentDoneSaysThatItIsPaidTodayToo() throws Exception {
        files.applied(file(row("k", "A payment", "'amount':{'value':'100','currency':'PLN'}")));
        String today = LocalDate.now(ZoneId.of("Europe/Warsaw")).toString();

        Map<String, Object> report = files.applied(file(row("k", "A payment", "'status':'DONE'")));

        assertThat(changesOf(rowOf(report, 1))).containsExactly("status: NOT_STARTED -> DONE", "paidOn: null -> " + today);
        assertThat(files.task("A payment")).containsEntry("status", "DONE").containsEntry("paidOn", today);
        assertThat(files.task("A payment").get("completedAt")).isNotNull();
    }

    @Test
    void thePriorityIsSetByHandHandedBackToThePlannerOrLeftAlone() throws Exception {
        files.applied(file(row("k", "A task", "'priority':'CRITICAL'")));
        assertThat(files.task("A task")).containsEntry("priorityMode", "MANUAL").containsEntry("priority", "CRITICAL");

        assertThat(rowOf(files.applied(file(row("k", "A task"))), 1)).containsEntry("outcome", "UNCHANGED");
        assertThat(files.task("A task")).containsEntry("priorityMode", "MANUAL");

        Map<String, Object> back = files.applied(file(row("k", "A task", "'priority':null")));
        assertThat(changesOf(rowOf(back, 1))).containsExactly("priority: CRITICAL -> null");
        assertThat(files.task("A task")).containsEntry("priorityMode", "AUTO");

        assertThat(rowOf(files.applied(file(row("k", "A task", "'priority':null"))), 1)).containsEntry("outcome", "UNCHANGED");
    }

    // ---------------------------------------------------------------- category, vendor

    @Test
    void aCategoryThatExistsIsFoundInAnotherSpellingAndOneThatIsNewIsAdded() throws Exception {
        String venue = files.categoryNamed("Venue");
        assertThat(venue).isNotNull();

        Map<String, Object> check = files.checked(file(
                row("a", "A", "'category':'  VENUE '"), row("b", "B", "'category':'Cake tasting'")));

        assertThat(strings(check.get("newCategories"))).containsExactly("Cake tasting");
        int before = files.categories().size();
        files.applied(file(row("a", "A", "'category':'  VENUE '"), row("b", "B", "'category':'Cake tasting'")));
        assertThat(files.task("A")).containsEntry("categoryId", venue);
        assertThat(files.categories()).hasSize(before + 1);
        assertThat(files.task("B")).containsEntry("categoryId", files.categoryNamed("Cake tasting"));
    }

    @Test
    void theSameNewCategoryInThreeRowsIsOneCategoryWrittenAsTheFirstRowWritesIt() throws Exception {
        files.categories();
        String body = file(row("a", "A", "'category':'Honeymoon trip'"), row("b", "B", "'category':'honeymoon TRIP'"),
                row("c", "C", "'category':' Honeymoon Trip '"));

        Map<String, Object> check = files.checked(body);
        assertThat(strings(check.get("newCategories"))).containsExactly("Honeymoon trip");
        files.applied(body);

        long made = files.categories().stream().filter(c -> ((String) c.get("name")).toLowerCase().startsWith("honeymoon t")).count();
        assertThat(made).isEqualTo(1);
        String id = files.categoryNamed("Honeymoon trip");
        assertThat(id).isNotNull();
        assertThat(List.of(files.task("A"), files.task("B"), files.task("C"))).extracting(task -> task.get("categoryId")).containsOnly(id);
    }

    @Test
    void aCategoryOfTheStarterListIsFoundBeforeTheListWasEverAsked() throws Exception {
        files.applied(file(row("a", "A", "'category':'venue'")));

        // The starter list came with the first category the file needed; nothing is doubled.
        assertThat(files.categories().stream().filter(c -> "venue".equalsIgnoreCase((String) c.get("name"))).count()).isEqualTo(1);
        assertThat(files.task("A").get("categoryId")).isEqualTo(files.categoryNamed("Venue"));
    }

    @Test
    void aCategoryNameFollowsTheCategoriesOwnRules() throws Exception {
        Map<String, Object> report = files.checked(file(row("a", "A", "'category':'" + "x".repeat(61) + "'"), row("b", "B", "'category':'   '")));

        assertThat(problemsOf(rowOf(report, 1))).containsExactly("category: Use at most 60 characters.");
        assertThat(problemsOf(rowOf(report, 2))).containsExactly("category: Enter a name.");
    }

    @Test
    void aVendorIsFoundByNameInAnotherSpellingButNeverMadeFromAFile() throws Exception {
        String vendor = files.vendor("Riverside Barn");
        files.vendor("Twin Studio");
        files.vendor("twin studio");

        Map<String, Object> report = files.checked(file(
                row("a", "A", "'vendor':'  riverside barn '"), row("b", "B", "'vendor':'Nobody Hall'"), row("c", "C", "'vendor':'Twin Studio'")));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "NEW");
        assertThat(rowOf(report, 2)).containsEntry("outcome", "REFUSED");
        assertThat(problemsOf(rowOf(report, 2))).hasSize(1).first().asString().startsWith("vendor: The planner does not have exactly one vendor")
                .contains("Add the vendor first");
        assertThat(problemsOf(rowOf(report, 3))).isEqualTo(problemsOf(rowOf(report, 2)));
        int vendors = TestHttp.jsonList(http.getAs(anna, "/api/vendors")).size();

        files.applied(file(row("a", "A", "'vendor':'  riverside barn '")));
        assertThat(files.task("A")).containsEntry("vendorId", vendor);
        assertThat(files.apply(anna, file(row("b", "B", "'vendor':'Nobody Hall'"))).statusCode()).isEqualTo(422);
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/vendors"))).hasSize(vendors);
    }

    // ---------------------------------------------------------------- amounts

    static Stream<Arguments> amountsThatAreTaken() {
        return Stream.of(
                Arguments.of("{'value':'12000','currency':'PLN'}", 1_200_000L),
                Arguments.of("{'value':'12000.00','currency':'PLN'}", 1_200_000L),
                Arguments.of("{'value':12000,'currency':'PLN'}", 1_200_000L),
                Arguments.of("{'value':12000.5,'currency':'PLN'}", 1_200_050L),
                Arguments.of("{'value':'0.07','currency':'PLN'}", 7L),
                Arguments.of("{'value':0.29,'currency':'PLN'}", 29L),
                Arguments.of("{'value':'4.1','currency':'PLN'}", 410L),
                Arguments.of("{'value':'0007','currency':'PLN'}", 700L),
                Arguments.of("{'value':'10000000000','currency':'PLN'}", 1_000_000_000_000L));
    }

    @ParameterizedTest
    @MethodSource("amountsThatAreTaken")
    void anAmountIsTurnedIntoItsSmallestUnitExactly(String amount, long expected) throws Exception {
        files.applied(file(row("k", "A payment", "'amount':" + amount)));

        assertThat(((Number) files.task("A payment").get("amount")).longValue()).isEqualTo(expected);
    }

    static Stream<Arguments> amountsThatAreRefused() {
        return Stream.of(
                Arguments.of("{'value':'12000.555','currency':'PLN'}", "amount: In PLN the amount can have at most 2 decimals."),
                Arguments.of("{'value':12000.505,'currency':'PLN'}", "amount: In PLN the amount can have at most 2 decimals."),
                Arguments.of("{'value':'12000.000','currency':'PLN'}", "amount: In PLN the amount can have at most 2 decimals."),
                Arguments.of("{'value':'1','currency':'EUR'}",
                        "amount: The wedding's currency is PLN. An amount in another currency is refused: nothing is converted."),
                Arguments.of("{'value':'1','currency':'pln'}",
                        "amount: The wedding's currency is PLN. An amount in another currency is refused: nothing is converted."),
                Arguments.of("{'value':'1'}", "amount: The amount needs its currency: \"PLN\"."),
                Arguments.of("{'currency':'PLN'}", "amount: The amount needs a value, such as \"12000.00\"."),
                Arguments.of("{'value':'-5','currency':'PLN'}", "amount: The amount cannot be negative."),
                Arguments.of("{'value':-5,'currency':'PLN'}", "amount: The amount cannot be negative."),
                Arguments.of("{'value':1e3,'currency':'PLN'}", "amount: Write the amount without an exponent, for example 12000.50."),
                Arguments.of("{'value':1.5E+2,'currency':'PLN'}", "amount: Write the amount without an exponent, for example 12000.50."),
                Arguments.of("{'value':'1e3','currency':'PLN'}",
                        "amount: Write the amount in digits, for example \"12000\" or \"12000.50\": no signs, no separators, no letters."),
                Arguments.of("{'value':'12 000','currency':'PLN'}",
                        "amount: Write the amount in digits, for example \"12000\" or \"12000.50\": no signs, no separators, no letters."),
                Arguments.of("{'value':'10000000000.01','currency':'PLN'}", "amount: That amount is too large."),
                Arguments.of("{'value':'99999999999999','currency':'PLN'}", "amount: That amount is too large."),
                Arguments.of("{'value':'0','currency':'PLN'}", "amount: Enter an amount above zero."),
                Arguments.of("{'value':true,'currency':'PLN'}", "amount: The value of the amount has to be a number, or text such as \"12000.00\"."),
                Arguments.of("{'value':'1','currency':'PLN','tip':3}", "amount: The amount has no field called \"tip\". It has \"value\" and \"currency\"."),
                Arguments.of("'12000'", "amount: Give the amount as { \"value\": \"12000.00\", \"currency\": \"PLN\" }, or null for none."));
    }

    @ParameterizedTest
    @MethodSource("amountsThatAreRefused")
    void anAmountThatWouldHaveToBeRoundedOrIsNotTheWeddingsIsRefusedWithItsWords(String amount, String said) throws Exception {
        Map<String, Object> report = files.checked(file(row("k", "A payment", "'amount':" + amount)));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "REFUSED");
        assertThat(problemsOf(rowOf(report, 1))).containsExactly(said);
        assertThat(report).containsEntry("canBeApplied", false);
    }

    @Test
    void aCurrencyWithNoDecimalsAcceptsWholeAmountsOnly() throws Exception {
        jdbc.update("UPDATE wedding SET currency = 'JPY'");

        Map<String, Object> report = files.checked(file(
                row("a", "A", "'amount':{'value':'5000','currency':'JPY'}"), row("b", "B", "'amount':{'value':'5000.5','currency':'JPY'}")));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "NEW");
        assertThat(problemsOf(rowOf(report, 2))).containsExactly("amount: In JPY the amount has no decimals.");
        files.applied(file(row("a", "A", "'amount':{'value':'5000','currency':'JPY'}")));
        assertThat(files.task("A")).containsEntry("amount", 5000);
    }

    // ---------------------------------------------------------------- the planner's own rules

    @Test
    void theFormsOwnRulesHoldForEveryRowInTheFormsOwnWords() throws Exception {
        String tomorrow = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(1).toString();
        Map<String, Object> report = files.checked(file(
                row("a", "A", "'paymentKind':'DEPOSIT'"),
                row("b", "B", "'paidOn':'2026-10-01'"),
                row("c", "C", "'amount':{'value':'5','currency':'PLN'}", "'paidOn':'2026-10-01'"),
                row("d", "D", "'status':'DONE'", "'amount':{'value':'5','currency':'PLN'}", "'paidOn':'" + tomorrow + "'"),
                row("e", "E", "'status':'DONE'", "'amount':{'value':'5','currency':'PLN'}", "'paidOn':'" + LocalDate.now(ZoneId.of("Europe/Warsaw")) + "'")));

        assertThat(problemsOf(rowOf(report, 1))).containsExactly(
                "paymentKind: The kind of payment needs an amount. Enter one, or leave the kind empty.");
        assertThat(problemsOf(rowOf(report, 2))).containsExactly(
                "paidOn: Only a payment has a paid date. Enter an amount, or leave the date empty.");
        assertThat(problemsOf(rowOf(report, 3))).containsExactly(
                "paidOn: A payment has a paid date once it is done. Mark it done, or leave the date empty.");
        assertThat(problemsOf(rowOf(report, 4))).containsExactly("paidOn: The paid date cannot be in the future.");
        assertThat(rowOf(report, 5)).containsEntry("outcome", "NEW");
        assertThat(report).containsEntry("added", 1).containsEntry("refused", 4);
    }

    @Test
    void aPaidDateOfTomorrowIsTakenOnceTheClockHasMovedAFullDay() throws Exception {
        String tomorrow = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(1).toString();
        String body = file(row("d", "D", "'status':'DONE'", "'amount':{'value':'5','currency':'PLN'}", "'paidOn':'" + tomorrow + "'"));
        assertThat(files.checked(body)).containsEntry("canBeApplied", false);

        io.github.trufelek124.weddingplanner.TestClock.advance(java.time.Duration.ofDays(1));
        try {
            // The access token has run out meanwhile: sign in again.
            String fresh = io.github.trufelek124.weddingplanner.TestSignIn.signIn(http, ANNA).accessToken();
            HttpResponse<String> response = files.check(fresh, body);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(TestHttp.json(response)).containsEntry("canBeApplied", true);
        } finally {
            io.github.trufelek124.weddingplanner.TestClock.reset();
        }
    }

    @Test
    void theRulesAreHeldAgainstTheTaskAsItWouldBeAfterTheRowNotJustTheRow() throws Exception {
        files.applied(file(row("k", "A payment", "'amount':{'value':'5','currency':'PLN'}", "'paymentKind':'DEPOSIT'")));

        // The row says nothing of the amount, and the task has one: a paid date then needs it done, not the row's field.
        Map<String, Object> report = files.checked(file(row("k", "A payment", "'paidOn':'2026-10-01'")));

        assertThat(problemsOf(rowOf(report, 1))).containsExactly(
                "paidOn: A payment has a paid date once it is done. Mark it done, or leave the date empty.");
    }

    @Test
    void aTitleAndNotesFollowTheFormsLengths() throws Exception {
        Map<String, Object> report = files.checked(file(
                row("a", "x".repeat(201)), row("b", "   "), row("c", "C", "'notes':'" + "n".repeat(10_001) + "'")));

        assertThat(problemsOf(rowOf(report, 1))).containsExactly("title: Use at most 200 characters.");
        assertThat(problemsOf(rowOf(report, 2))).containsExactly("title: Enter a title.");
        assertThat(problemsOf(rowOf(report, 3))).containsExactly("notes: Use at most 10,000 characters.");
    }

    // ---------------------------------------------------------------- changes in the planner

    @Test
    void anUnchangedRowLeavesItsTaskAloneAndAChangedOneMovesItsVersion() throws Exception {
        files.applied(file(row("a", "A", "'dueDate':'2027-01-01'"), row("b", "B", "'dueDate':'2027-01-02'")));
        Map<String, Object> a = files.task("A");
        Map<String, Object> b = files.task("B");
        assertThat(a).containsEntry("version", 0);

        Map<String, Object> report = files.applied(file(row("a", "A", "'dueDate':'2027-01-01'"), row("b", "B", "'dueDate':'2027-02-02'")));

        assertThat(report).containsEntry("unchanged", 1).containsEntry("changed", 1);
        assertThat(files.task("A")).isEqualTo(a);
        assertThat(files.task("B")).containsEntry("version", 1).containsEntry("dueDate", "2027-02-02");
        assertThat(files.task("B").get("updatedAt")).isNotEqualTo(b.get("updatedAt"));
        assertThat(files.task("B").get("createdAt")).isEqualTo(b.get("createdAt"));
    }

    @Test
    void aTaskChangedInThePlannerAfterTheUploadIsOverwrittenInTheFieldsTheRowGivesAndInNoOther() throws Exception {
        files.applied(file(row("k", "Order the rings", "'dueDate':'2027-03-01'", "'notes':'From the file'")));
        Map<String, Object> task = files.task("Order the rings");
        Map<String, Object> change = new java.util.HashMap<>(task);
        change.put("title", "Order the rings, both");
        change.put("dueDate", "2027-04-01");
        change.put("notes", "Written in the planner");
        change.put("assigneeIds", List.of(files.wedding.piotrId));
        HttpResponse<String> saved = http.putJsonAs(anna, "/api/entries/" + task.get("id"), TestHttp.jsonOf(change));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);

        Map<String, Object> report = files.applied(file(row("k", "Order the rings", "'dueDate':'2027-03-01'")));

        assertThat(changesOf(rowOf(report, 1))).containsExactly("title: Order the rings, both -> Order the rings",
                "dueDate: 2027-04-01 -> 2027-03-01");
        Map<String, Object> now = files.task("Order the rings");
        assertThat(now).containsEntry("notes", "Written in the planner").containsEntry("dueDate", "2027-03-01");
        assertThat(now.get("assigneeIds")).isEqualTo(List.of(files.wedding.piotrId));
    }

    @Test
    void aTaskRemovedInThePlannerComesBackAsNewWhenALaterFileStillHasItsRow() throws Exception {
        files.applied(file(row("k", "Order the rings")));
        String first = (String) files.task("Order the rings").get("id");
        assertThat(http.deleteAs(anna, "/api/entries/" + first).statusCode()).isEqualTo(204);

        Map<String, Object> report = files.applied(file(row("k", "Order the rings")));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "NEW");
        assertThat(files.task("Order the rings").get("id")).isNotEqualTo(first);
        assertThat(count("entry")).isEqualTo(1);
    }

    @Test
    void aKeyWhoseEntryWasMadeAStepOfThePlanIsRefusedAndKeepsItsKey() throws Exception {
        files.applied(file(row("k", "Order the rings")));
        String id = (String) files.task("Order the rings").get("id");
        HttpResponse<String> converted = http.postJsonAs(anna, "/api/entries/" + id + "/convert", "{\"kind\":\"PLAN_ITEM\"}");
        assertThat(converted.statusCode()).as(converted.body()).isEqualTo(200);

        Map<String, Object> report = files.checked(file(row("k", "Order the rings")));

        assertThat(rowOf(report, 1)).containsEntry("outcome", "REFUSED");
        assertThat(problemsOf(rowOf(report, 1))).containsExactly(
                "key: This key belongs to a step of the plan now, not to a task. Use another key to make a task.");
        assertThat(files.apply(anna, file(row("k", "Order the rings"))).statusCode()).isEqualTo(422);
        assertThat(count("entry")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_key FROM entry", String.class)).isEqualTo("k");
    }

    @Test
    void theOtherOwnerSeesWhatTheFirstUploadedAndCanUpdateIt() throws Exception {
        files.applied(file(row("k", "Order the rings", "'dueDate':'2027-03-01'")));

        HttpResponse<String> seen = http.getAs(piotr, "/api/entries?kind=TASK");
        assertThat(TestHttp.jsonList(seen)).extracting(task -> task.get("title")).containsExactly("Order the rings");

        HttpResponse<String> update = files.apply(piotr, file(row("k", "Order the rings", "'dueDate':'2027-03-05'")));
        assertThat(update.statusCode()).as(update.body()).isEqualTo(200);
        assertThat(TestHttp.json(update)).containsEntry("changed", 1);
        assertThat(files.task("Order the rings")).containsEntry("dueDate", "2027-03-05");
        assertThat(jdbc.queryForObject("SELECT created_by::text FROM entry", String.class)).isEqualTo(files.wedding.annaId);
    }

    @Test
    void nothingIsRemovedByAFileWithFewerRows() throws Exception {
        files.applied(file(row("a", "A"), row("b", "B"), row("c", "C")));

        Map<String, Object> report = files.applied(file(row("a", "A")));

        assertThat(report).containsEntry("unchanged", 1).containsEntry("added", 0);
        assertThat(files.tasks()).containsOnlyKeys("A", "B", "C");
        // A task no longer wanted is dropped, which a file can say.
        files.applied(file(row("b", "B", "'status':'DROPPED'")));
        assertThat(files.task("B")).containsEntry("status", "DROPPED");
        assertThat(files.tasks()).hasSize(3);
    }

    // ---------------------------------------------------------------- the key

    @Test
    void aKeyIsKeptWithTheTaskAndNeverShownInAnEntry() throws Exception {
        files.applied(file(row("a-key", "A")));

        assertThat(files.task("A")).doesNotContainKey("key").doesNotContainKey("taskKey");
        assertThat(jdbc.queryForObject("SELECT task_key FROM entry", String.class)).isEqualTo("a-key");
    }

    @Test
    void keysDifferByCapitalLetters() throws Exception {
        Map<String, Object> report = files.applied(file(row("Key", "First"), row("key", "Second")));

        assertThat(report).containsEntry("added", 2);
        assertThat(files.tasks()).containsOnlyKeys("First", "Second");
    }

    @Test
    void theTableHoldsKeysToTheirLengthAndToOneEntryEach() {
        UUID wedding = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        String insert = """
                INSERT INTO entry (id, wedding_id, kind, title, status, priority_mode, version, created_at, updated_at, task_key)
                VALUES (?, ?, 'TASK', 'T', 'NOT_STARTED', 'AUTO', 0, now(), now(), ?)
                """;
        jdbc.update(insert, UUID.randomUUID(), wedding, "x".repeat(100));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), wedding, "x".repeat(101)))
                .hasMessageContaining("entry_task_key_length");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), wedding, "x".repeat(100)))
                .hasMessageContaining("entry_task_key");
        jdbc.update(insert, UUID.randomUUID(), wedding, null);
        jdbc.update(insert, UUID.randomUUID(), wedding, null);
        assertThat(count("entry")).isEqualTo(3);
    }
}
