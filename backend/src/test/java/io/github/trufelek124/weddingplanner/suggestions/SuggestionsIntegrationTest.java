package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.PaymentKind;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the e-mail import hands in, and what the two owners make of it. Everything here is made up: senders are at
 * .example addresses, and the mail never existed.
 */
class SuggestionsIntegrationTest extends PostgresIntegrationTest {

    static final String GMAIL = "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000001";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    @Autowired
    PlatformTransactionManager transactions;

    TestHttp http;
    String anna;
    String piotr;
    String annaId;
    String piotrId;
    UUID weddingId;
    int keys;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        annaId = wedding.annaId;
        piotrId = wedding.piotrId;
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    // ---------------------------------------------------------------- what accepting makes

    @Test
    void acceptingATaskMakesATaskWithTheProposalsValues() throws Exception {
        UUID id = handIn(task("Send the guest list to the printer").withDue(LocalDate.of(2027, 3, 1)).withNotes("Before the proofs."));

        Map<String, Object> accepted = accept(anna, id);

        assertThat(accepted).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", annaId).containsEntry("madeVendorId", null);
        Map<String, Object> entry = entry((String) accepted.get("madeEntryId"));
        assertThat(entry)
                .containsEntry("kind", "TASK")
                .containsEntry("title", "Send the guest list to the printer")
                .containsEntry("notes", "Before the proofs.")
                .containsEntry("dueDate", "2027-03-01")
                .containsEntry("status", "NOT_STARTED")
                .containsEntry("amount", null);
        assertThat(entries()).hasSize(1);
    }

    @Test
    void acceptingAPaymentMakesATaskWithTheAmountAndTheKindOfPayment() throws Exception {
        UUID id = handIn(base(Kind.PAYMENT, "Deposit for the Palace on the Hill").withAmount(500_000L, PaymentKind.DEPOSIT)
                .withDue(LocalDate.of(2026, 12, 20)));

        Map<String, Object> entry = entry((String) accept(anna, id).get("madeEntryId"));

        assertThat(entry)
                .containsEntry("kind", "TASK")
                .containsEntry("title", "Deposit for the Palace on the Hill")
                .containsEntry("amount", 500_000)
                .containsEntry("paymentKind", "DEPOSIT")
                .containsEntry("dueDate", "2026-12-20");
    }

    @Test
    void acceptingAStepOfThePlanMakesAPlanItemWithItsDays() throws Exception {
        UUID id = handIn(base(Kind.PLAN_ITEM, "Tasting at the Palace on the Hill")
                .withStart(LocalDate.of(2027, 1, 10)).withDue(LocalDate.of(2027, 1, 12)));

        Map<String, Object> entry = entry((String) accept(piotr, id).get("madeEntryId"));

        assertThat(entry).containsEntry("kind", "PLAN_ITEM").containsEntry("startDate", "2027-01-10").containsEntry("dueDate", "2027-01-12");
    }

    @Test
    void acceptingAVendorMakesAVendorThatIsBeingConsidered() throws Exception {
        UUID id = handIn(base(Kind.VENDOR, "Palace on the Hill")
                .withVendor(null, null, "events@palace-hill.example", "+48 600 100 200", "palace-hill.example").withNotes("Garden venue."));

        Map<String, Object> accepted = accept(anna, id);

        assertThat(accepted).containsEntry("madeEntryId", null);
        Map<String, Object> vendor = TestHttp.json(http.getAs(piotr, "/api/vendors/" + accepted.get("madeVendorId")));
        assertThat(vendor)
                .containsEntry("name", "Palace on the Hill")
                .containsEntry("status", "CONSIDERING")
                .containsEntry("email", "events@palace-hill.example")
                .containsEntry("phone", "+48 600 100 200")
                .containsEntry("website", "https://palace-hill.example")
                .containsEntry("notes", "Garden venue.");
        assertThat(entries()).isEmpty();
    }

    @Test
    void acceptingWithChangesMakesItWithTheChangedValues() throws Exception {
        String music = categoryId("Music");
        UUID id = handIn(base(Kind.PAYMENT, "Deposit for the band").withAmount(100_000L, PaymentKind.DEPOSIT));
        Map<String, Object> changes = new HashMap<>();
        changes.put("title", "Deposit for the Echo band");
        changes.put("notes", "Paid by transfer.");
        changes.put("dueDate", "2027-02-01");
        changes.put("amount", 120_000);
        changes.put("paymentKind", "INSTALMENT");
        changes.put("categoryId", music);

        HttpResponse<String> response = http.postJsonAs(piotr, "/api/suggestions/" + id + "/accept", TestHttp.jsonOf(changes));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Map<String, Object> entry = entry((String) TestHttp.json(response).get("madeEntryId"));
        assertThat(entry)
                .containsEntry("title", "Deposit for the Echo band")
                .containsEntry("notes", "Paid by transfer.")
                .containsEntry("dueDate", "2027-02-01")
                .containsEntry("amount", 120_000)
                .containsEntry("paymentKind", "INSTALMENT")
                .containsEntry("categoryId", music);
        // What the import proposed stays as it was proposed.
        assertThat(get(anna, id)).containsEntry("title", "Deposit for the band").containsEntry("amount", 100_000);
    }

    @Test
    void aProposalWithOnlyAVendorsNameMakesTheEntryWithoutAVendor() throws Exception {
        UUID id = handIn(task("Ask about parking").withVendor(null, "Palace on the Hill", null, null, null));

        Map<String, Object> accepted = accept(anna, id);

        assertThat(accepted).containsEntry("vendorName", "Palace on the Hill");
        assertThat(entry((String) accepted.get("madeEntryId"))).containsEntry("vendorId", null);
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/vendors"))).isEmpty();
    }

    @Test
    void aProposalWithAVendorOfThePlannerMakesTheEntryPointToIt() throws Exception {
        String vendor = (String) TestHttp.json(http.postJsonAs(anna, "/api/vendors", TestHttp.jsonOf(vendorRequest("Palace on the Hill")))).get("id");
        UUID id = handIn(task("Ask about parking").withVendor(UUID.fromString(vendor), null, null, null, null));

        assertThat(entry((String) accept(anna, id).get("madeEntryId"))).containsEntry("vendorId", vendor);
    }

    // ---------------------------------------------------------------- a decision stands

    @Test
    void acceptingTwiceMakesOneEntryAndAnswersTheSameBothTimes() throws Exception {
        UUID id = handIn(task("Book the photographer"));

        Map<String, Object> first = accept(anna, id);
        Map<String, Object> second = accept(anna, id);
        Map<String, Object> byTheOther = accept(piotr, id);

        assertThat(second).isEqualTo(first);
        assertThat(byTheOther).isEqualTo(first);
        assertThat(entries()).hasSize(1);
        assertThat(first).containsEntry("decidedBy", annaId);
    }

    @Test
    void acceptingAgainWithOtherValuesChangesNothing() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        Map<String, Object> first = accept(anna, id);

        HttpResponse<String> again = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", TestHttp.jsonOf(Map.of("title", "Something else")));

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(again)).isEqualTo(first);
        assertThat(entries()).extracting(entry -> entry.get("title")).containsExactly("Book the photographer");
    }

    @Test
    void rejectingTwiceIsFineAndMakesNothing() throws Exception {
        UUID id = handIn(task("Book the photographer"));

        Map<String, Object> first = reject(anna, id);
        Map<String, Object> second = reject(piotr, id);

        assertThat(first).containsEntry("state", "REJECTED").containsEntry("decidedBy", annaId).containsEntry("madeEntryId", null);
        assertThat(second).isEqualTo(first);
        assertThat(entries()).isEmpty();
    }

    @Test
    void theOtherDecisionIsRefusedAfterAnAcceptanceAndNothingIsUnmade() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        Map<String, Object> accepted = accept(anna, id);

        HttpResponse<String> refused = http.postJsonAs(piotr, "/api/suggestions/" + id + "/reject", "");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(refused).get("type")).endsWith(":already-decided");
        assertThat(entries()).hasSize(1);
        assertThat(get(piotr, id)).isEqualTo(accepted);
    }

    @Test
    void theOtherDecisionIsRefusedAfterARejectionAndNothingIsMade() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        Map<String, Object> rejected = reject(anna, id);

        HttpResponse<String> refused = post(piotr, "/api/suggestions/" + id + "/accept");

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(refused).get("type")).endsWith(":already-decided");
        assertThat(entries()).isEmpty();
        assertThat(get(piotr, id)).isEqualTo(rejected);
    }

    @Test
    void twoRequestsToAcceptAtTheSameMomentMakeOneEntry() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        int requests = 6;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<HttpResponse<String>>> answers = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String who = i % 2 == 0 ? anna : piotr;
                Callable<HttpResponse<String>> call = () -> {
                    ready.countDown();
                    go.await();
                    return post(who, "/api/suggestions/" + id + "/accept");
                };
                answers.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Map<String, Object>> bodies = new ArrayList<>();
            for (Future<HttpResponse<String>> answer : answers) {
                HttpResponse<String> response = answer.get();
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                bodies.add(TestHttp.json(response));
            }
            assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(bodies.get(0)));
        } finally {
            pool.shutdownNow();
        }
        assertThat(entries()).hasSize(1);
    }

    @Test
    void anAcceptanceWaitsForWhoeverHoldsTheSuggestionAndThenSeesWhatWasDecided() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> late = new TransactionTemplate(transactions).execute(status -> {
                // Someone else is deciding it right now, and holds the row until they are done.
                jdbc.queryForObject("SELECT id FROM suggestion WHERE id = ? FOR UPDATE", UUID.class, id);
                Future<HttpResponse<String>> request = pool.submit(() -> post(piotr, "/api/suggestions/" + id + "/accept"));
                try {
                    Thread.sleep(800);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
                assertThat(request).as("the acceptance waits while the row is held").isNotDone();
                jdbc.update("UPDATE suggestion SET state = 'REJECTED', decided_by = ?, decided_at = now() WHERE id = ?",
                        UUID.fromString(annaId), id);
                return request;
            });

            HttpResponse<String> response = late.get();

            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat((String) TestHttp.json(response).get("type")).endsWith(":already-decided");
            assertThat(entries()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- what is not valid is not made

    @Test
    void withoutATitleAfterTheChangesNothingIsMadeAndTheSuggestionStillWaits() throws Exception {
        UUID id = handIn(task("Book the photographer"));

        HttpResponse<String> refused = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", TestHttp.jsonOf(Map.of("title", "  ")));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("title: Enter a title.");
        assertStillWaiting(id);
    }

    @Test
    void aNegativeAmountIsRefusedWithTheMessageOfTheForm() throws Exception {
        UUID id = handIn(base(Kind.PAYMENT, "Deposit").withAmount(100_000L, null));
        Map<String, Object> changes = Map.of("title", "Deposit", "amount", -5);

        HttpResponse<String> refused = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", TestHttp.jsonOf(changes));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("amount: Enter an amount above zero.");
        assertStillWaiting(id);
    }

    @Test
    void aPaymentWithoutAnAmountIsRefused() throws Exception {
        UUID id = handIn(base(Kind.PAYMENT, "Deposit"));

        HttpResponse<String> refused = post(anna, "/api/suggestions/" + id + "/accept");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("amount: Enter the amount of this payment.");
        assertStillWaiting(id);
    }

    @Test
    void aVendorThatIsGoneIsSaidAndNothingIsMade() throws Exception {
        String vendor = (String) TestHttp.json(http.postJsonAs(anna, "/api/vendors", TestHttp.jsonOf(vendorRequest("Palace on the Hill")))).get("id");
        UUID id = handIn(task("Ask about parking").withVendor(UUID.fromString(vendor), null, null, null, null));
        assertThat(http.deleteAs(anna, "/api/vendors/" + vendor).statusCode()).isEqualTo(204);

        HttpResponse<String> refused = post(anna, "/api/suggestions/" + id + "/accept");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("vendorId: Choose one of your vendors.");
        assertStillWaiting(id);
    }

    @Test
    void aCategoryThatIsGoneIsSaidAndNothingIsMade() throws Exception {
        String category = (String) TestHttp.json(http.postJsonAs(anna, "/api/categories", TestHttp.jsonOf(Map.of("name", "Fireworks")))).get("id");
        UUID id = handIn(task("Order the fireworks").withCategory(UUID.fromString(category)));
        assertThat(http.deleteAs(anna, "/api/categories/" + category).statusCode()).isEqualTo(204);

        HttpResponse<String> refused = post(anna, "/api/suggestions/" + id + "/accept");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("categoryId: Choose one of the categories.");
        assertStillWaiting(id);
    }

    @Test
    void aVendorWithAnAddressThatIsNotOneIsRefusedNextToTheField() throws Exception {
        UUID id = handIn(base(Kind.VENDOR, "Palace on the Hill").withVendor(null, null, "events at palace", null, null));

        HttpResponse<String> refused = post(anna, "/api/suggestions/" + id + "/accept");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(refused)).containsExactly("vendorEmail: Enter an e-mail address like name@example.com, or leave it empty.");
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/vendors"))).isEmpty();
        assertStillWaiting(id);
    }

    @Test
    void aRefusedAcceptanceCanBeMadeRightAndThenWorks() throws Exception {
        UUID id = handIn(base(Kind.PAYMENT, "Deposit"));
        assertThat(post(anna, "/api/suggestions/" + id + "/accept").statusCode()).isEqualTo(400);

        HttpResponse<String> fixed = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", TestHttp.jsonOf(Map.of("title", "Deposit", "amount", 90_000)));

        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(200);
        assertThat(entries()).hasSize(1);
    }

    // ---------------------------------------------------------------- reading

    @Test
    void theListShowsWhatWaitsNewestMessageFirstAndTheOthersOnAsking() throws Exception {
        UUID old = handIn(task("Old one").withReceived(Instant.parse("2026-11-01T10:00:00Z")));
        UUID newest = handIn(task("Newest").withReceived(Instant.parse("2026-11-18T10:00:00Z")));
        UUID middle = handIn(task("Middle").withReceived(Instant.parse("2026-11-10T10:00:00Z")));
        UUID accepted = handIn(task("Accepted one"));
        UUID rejected = handIn(task("Rejected one"));
        accept(anna, accepted);
        reject(piotr, rejected);

        assertThat(ids("")).containsExactly(newest.toString(), middle.toString(), old.toString());
        assertThat(ids("?state=WAITING")).containsExactly(newest.toString(), middle.toString(), old.toString());
        assertThat(ids("?state=ACCEPTED")).containsExactly(accepted.toString());
        assertThat(ids("?state=REJECTED")).containsExactly(rejected.toString());
        assertThat(ids("?state=ALL")).hasSize(5);
    }

    @Test
    void everyFieldOfASuggestionIsAlwaysThere() throws Exception {
        UUID id = handIn(base(Kind.PLAN_ITEM, "Tasting"));

        Map<String, Object> shown = get(anna, id);

        assertThat(shown).containsOnlyKeys(
                "id", "kind", "state", "title", "notes", "startDate", "dueDate", "amount", "paymentKind", "categoryId",
                "vendorId", "vendorName", "vendorEmail", "vendorPhone", "vendorWebsite", "senderName", "senderAddress",
                "subject", "receivedAt", "inboxMemberId", "mailLink", "passage", "confidence", "decidedBy", "decidedAt",
                "madeEntryId", "madeVendorId", "createdAt");
        assertThat(shown)
                .containsEntry("state", "WAITING")
                .containsEntry("senderName", "Jan from Palace on the Hill")
                .containsEntry("senderAddress", "jan@palace-hill.example")
                .containsEntry("subject", "Your tasting")
                .containsEntry("passage", "We could meet on Friday.")
                .containsEntry("mailLink", GMAIL)
                .containsEntry("confidence", 80)
                .containsEntry("inboxMemberId", annaId)
                .containsEntry("decidedBy", null)
                .containsEntry("decidedAt", null);
    }

    @Test
    void theOtherOwnerSeesTheSameSuggestionsAndWhoDecidedIsWhoseRequestWasTaken() throws Exception {
        UUID id = handIn(task("Book the photographer"));
        assertThat(ids(anna, "")).containsExactly(id.toString());
        assertThat(ids(piotr, "")).containsExactly(id.toString());

        Map<String, Object> accepted = accept(piotr, id);

        assertThat(accepted).containsEntry("decidedBy", piotrId).containsEntry("inboxMemberId", annaId);
        assertThat(get(anna, id)).isEqualTo(accepted);
    }

    @Test
    void aSuggestionThatDoesNotExistIsNotFound() throws Exception {
        String nothing = UUID.randomUUID().toString();

        for (HttpResponse<String> response : List.of(
                post(anna, "/api/suggestions/" + nothing + "/accept"),
                post(anna, "/api/suggestions/" + nothing + "/reject"))) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            assertThat((String) TestHttp.json(response).get("type")).endsWith(":not-found");
        }
    }

    @Test
    void withoutATokenNothingIsAnswered() throws Exception {
        UUID id = handIn(task("Book the photographer"));

        assertThat(http.get("/api/suggestions").statusCode()).isEqualTo(401);
        assertThat(http.postJson("/api/suggestions/" + id + "/accept", "").statusCode()).isEqualTo(401);
        assertThat(http.postJson("/api/suggestions/" + id + "/reject", "").statusCode()).isEqualTo(401);
        assertStillWaiting(id);
    }

    @Test
    void thereIsNoCallThatAddsASuggestion() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Pay the florist");
        body.put("kind", "PAYMENT");

        for (String method : List.of("POST", "PUT")) {
            HttpResponse<String> response = method.equals("POST")
                    ? http.postJsonAs(anna, "/api/suggestions", TestHttp.jsonOf(body))
                    : http.putJsonAs(anna, "/api/suggestions", TestHttp.jsonOf(body));
            assertThat(response.statusCode()).as(method).isGreaterThanOrEqualTo(400);
        }
        assertThat(ids("?state=ALL")).isEmpty();
    }

    // ---------------------------------------------------------------- handing in

    @Test
    void handingInTheSameKeyAgainKeepsOneSuggestionAndChangesNothing() throws Exception {
        Draft first = task("Book the photographer");
        UUID id = handIn(first);

        UUID again = handIn(first.withTitle("A different title, as the import now reads it"));

        assertThat(again).isEqualTo(id);
        assertThat(ids("?state=ALL")).hasSize(1);
        assertThat(get(anna, id)).containsEntry("title", "Book the photographer");
    }

    @Test
    void handingInTheSameKeyAfterADecisionStillChangesNothing() throws Exception {
        Draft first = task("Book the photographer");
        UUID id = handIn(first);
        reject(anna, id);

        assertThat(handIn(first)).isEqualTo(id);
        assertThat(get(anna, id)).containsEntry("state", "REJECTED");
        assertThat(ids("")).isEmpty();
    }

    @Test
    void aLinkThatIsNotAnHttpsAddressAtGmailIsRefused() {
        for (String link : List.of(
                "http://mail.google.com/mail/u/0/#inbox/1",
                "https://evil.example/mail.google.com",
                "https://mail.google.com.evil.example/mail",
                "https://mail.google.com@evil.example/mail",
                "https://evil.example@mail.google.com/mail",
                "https://mail.google.com:8443/mail",
                "https://notmail.google.com/mail",
                "https://google.com/mail",
                "https://MAIL.GOOGLE.COM/mail",
                "https://mail.google.com\\@evil.example/",
                "https://mail.google.com/a b",
                "javascript:alert(1)",
                "//mail.google.com/mail",
                "mail.google.com/mail",
                "")) {
            assertThat(refusedFields(task("Book the photographer").withLink(link))).as(link).containsExactly("mailLink");
        }
        assertThat(refusedFields(task("Book the photographer").withLink(null))).containsExactly("mailLink");
        assertThat(count()).isZero();
    }

    @Test
    void aLinkAtGmailIsKept() {
        for (String link : List.of("https://mail.google.com", "https://mail.google.com/", GMAIL, "https://mail.google.com/mail/?view=cm&fs=1")) {
            handIn(task("Book the photographer " + keys).withLink(link));
        }
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void theDatabaseHoldsTheLinkRuleToo() {
        UUID id = handIn(task("Book the photographer"));

        for (String link : List.of("http://mail.google.com/x", "https://mail.google.com.evil.example/x", "https://evil.example/", "https://mail.google.com@evil.example/")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE suggestion SET mail_link = ? WHERE id = ?", link, id))
                    .as(link).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void whatIsNotValidIsRefusedWithAMessagePerField() {
        assertThat(refusedFields(task("  "))).containsExactly("title");
        assertThat(refusedFields(task("Fine").withPassage("x".repeat(1001)))).containsExactly("passage");
        assertThat(refusedFields(task("Fine").withSubject("x".repeat(301)))).containsExactly("subject");
        assertThat(refusedFields(task("Fine").withConfidence(101))).containsExactly("confidence");
        assertThat(refusedFields(task("Fine").withConfidence(-1))).containsExactly("confidence");
        assertThat(refusedFields(task("Fine").withInbox(UUID.randomUUID()))).containsExactly("inboxMemberId");
        assertThat(refusedFields(task("Fine").withInbox(null))).containsExactly("inboxMemberId");
        assertThat(refusedFields(task("Fine").withAmount(-1L, null))).containsExactly("amount");
        assertThat(refusedFields(task("Fine").withKey(" "))).containsExactly("sourceKey");
        assertThat(count()).isZero();
        // The limits themselves are fine.
        handIn(task("Fine").withPassage("x".repeat(1000)).withSubject("y".repeat(300)).withConfidence(100));
        handIn(task("Fine too").withConfidence(0));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aTextIsKeptStrippedAndWithoutMarkupBeingTakenForAnything() throws Exception {
        UUID id = handIn(task("  <b>Pay now</b> at https://evil.example  ").withPassage("  Click https://evil.example  "));

        assertThat(get(anna, id))
                .containsEntry("title", "<b>Pay now</b> at https://evil.example")
                .containsEntry("passage", "Click https://evil.example")
                .containsEntry("mailLink", GMAIL);
    }

    // ---------------------------------------------------------------- helpers

    /** A proposal, written the short way. */
    record Draft(Proposal proposal) {

        Draft with(java.util.function.UnaryOperator<Builder> change) {
            return new Draft(change.apply(new Builder(proposal)).build());
        }

        Draft withTitle(String title) { return with(b -> b.title(title)); }
        Draft withNotes(String notes) { return with(b -> b.notes(notes)); }
        Draft withDue(LocalDate day) { return with(b -> b.due(day)); }
        Draft withStart(LocalDate day) { return with(b -> b.start(day)); }
        Draft withAmount(Long amount, PaymentKind kind) { return with(b -> b.amount(amount, kind)); }
        Draft withCategory(UUID id) { return with(b -> b.category(id)); }
        Draft withVendor(UUID id, String name, String email, String phone, String website) {
            return with(b -> b.vendor(id, name, email, phone, website));
        }
        Draft withLink(String link) { return with(b -> b.link(link)); }
        Draft withPassage(String passage) { return with(b -> b.passage(passage)); }
        Draft withSubject(String subject) { return with(b -> b.subject(subject)); }
        Draft withConfidence(int confidence) { return with(b -> b.confidence(confidence)); }
        Draft withInbox(UUID member) { return with(b -> b.inbox(member)); }
        Draft withKey(String key) { return with(b -> b.key(key)); }
        Draft withReceived(Instant at) { return with(b -> b.received(at)); }
    }

    /** Proposal is a record: this changes one part at a time. */
    static final class Builder {
        UUID weddingId; String key; Kind kind; String title; String notes; LocalDate start; LocalDate due; Long amount;
        PaymentKind paymentKind; UUID category; UUID vendorId; String vendorName; String vendorEmail; String vendorPhone;
        String vendorWebsite; String senderName; String senderAddress; String subject; Instant received; UUID inbox;
        String link; String passage; int confidence;

        Builder(Proposal p) {
            weddingId = p.weddingId(); key = p.sourceKey(); kind = p.kind(); title = p.title(); notes = p.notes();
            start = p.startDate(); due = p.dueDate(); amount = p.amount(); paymentKind = p.paymentKind();
            category = p.categoryId(); vendorId = p.vendorId(); vendorName = p.vendorName(); vendorEmail = p.vendorEmail();
            vendorPhone = p.vendorPhone(); vendorWebsite = p.vendorWebsite(); senderName = p.senderName();
            senderAddress = p.senderAddress(); subject = p.subject(); received = p.receivedAt(); inbox = p.inboxMemberId();
            link = p.mailLink(); passage = p.passage(); confidence = p.confidence();
        }

        Builder title(String v) { title = v; return this; }
        Builder notes(String v) { notes = v; return this; }
        Builder due(LocalDate v) { due = v; return this; }
        Builder start(LocalDate v) { start = v; return this; }
        Builder amount(Long v, PaymentKind k) { amount = v; paymentKind = k; return this; }
        Builder category(UUID v) { category = v; return this; }
        Builder vendor(UUID id, String name, String email, String phone, String website) {
            vendorId = id; vendorName = name; vendorEmail = email; vendorPhone = phone; vendorWebsite = website; return this;
        }
        Builder link(String v) { link = v; return this; }
        Builder passage(String v) { passage = v; return this; }
        Builder subject(String v) { subject = v; return this; }
        Builder confidence(int v) { confidence = v; return this; }
        Builder inbox(UUID v) { inbox = v; return this; }
        Builder key(String v) { key = v; return this; }
        Builder received(Instant v) { received = v; return this; }

        Proposal build() {
            return new Proposal(weddingId, key, kind, title, notes, start, due, amount, paymentKind, category, vendorId,
                    vendorName, vendorEmail, vendorPhone, vendorWebsite, senderName, senderAddress, subject, received, inbox,
                    link, passage, confidence);
        }
    }

    Draft base(Kind kind, String title) {
        return new Draft(new Proposal(
                weddingId, "mail-" + (++keys), kind, title, "", null, null, null, null, null, null, null, null, null, null,
                "Jan from Palace on the Hill", "jan@palace-hill.example", "Your tasting", Instant.parse("2026-11-12T09:30:00Z"),
                UUID.fromString(annaId), GMAIL, "We could meet on Friday.", 80));
    }

    Draft task(String title) {
        return base(Kind.TASK, title);
    }

    UUID handIn(Draft draft) {
        return suggestions.handIn(draft.proposal());
    }

    List<String> refusedFields(Draft draft) {
        try {
            suggestions.handIn(draft.proposal());
            return List.of("(kept)");
        } catch (ApiException refused) {
            return refused.fields().stream().map(FieldProblem::field).toList();
        }
    }

    int count() {
        return jdbc.queryForObject("SELECT count(*) FROM suggestion", Integer.class);
    }

    HttpResponse<String> post(String token, String path) throws Exception {
        return http.postJsonAs(token, path, "");
    }

    Map<String, Object> accept(String token, UUID id) throws Exception {
        HttpResponse<String> response = post(token, "/api/suggestions/" + id + "/accept");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    Map<String, Object> reject(String token, UUID id) throws Exception {
        HttpResponse<String> response = post(token, "/api/suggestions/" + id + "/reject");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    Map<String, Object> get(String token, UUID id) throws Exception {
        return TestHttp.jsonList(http.getAs(token, "/api/suggestions?state=ALL")).stream()
                .filter(one -> id.toString().equals(one.get("id")))
                .findFirst()
                .orElseThrow();
    }

    List<String> ids(String query) throws Exception {
        return ids(anna, query);
    }

    List<String> ids(String token, String query) throws Exception {
        HttpResponse<String> response = http.getAs(token, "/api/suggestions" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(one -> (String) one.get("id")).toList();
    }

    void assertStillWaiting(UUID id) throws Exception {
        assertThat(ids("")).contains(id.toString());
        assertThat(get(anna, id)).containsEntry("state", "WAITING").containsEntry("madeEntryId", null).containsEntry("madeVendorId", null);
        assertThat(entries()).isEmpty();
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/vendors"))).isEmpty();
    }

    List<Map<String, Object>> entries() throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/entries");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response);
    }

    Map<String, Object> entry(String id) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/entries/" + id);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    String categoryId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/categories")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> (String) category.get("id"))
                .findFirst()
                .orElseThrow();
    }

    static Map<String, Object> vendorRequest(String name) {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", "");
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        return vendor;
    }
}
