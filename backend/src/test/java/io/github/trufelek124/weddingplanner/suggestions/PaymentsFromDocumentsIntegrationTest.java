package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.PaymentKind;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * S05 section 2 and D-014: a payment that a document names is added by itself, once, and only as the plan can take it.
 * Everything is made up; the proposals come in the way the reading hands them in.
 */
class PaymentsFromDocumentsIntegrationTest extends AddedByItselfTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 12, 1);

    // ---------------------------------------------------------------- what is made

    @Test
    void aPaymentFromADocumentAtTheThresholdIsAddedAsATaskWithItsAmountDayKindCategoryAndVendor() throws Exception {
        UUID vendor = vendor("Palace on the Hill");
        UUID category = UUID.randomUUID();
        jdbc.update("INSERT INTO category (id, wedding_id, name, position) VALUES (?, ?, 'Venue', 1)", category, weddingId);
        UUID id = suggestions.handIn(fromDocument(withVendor(category(
                due(money(proposal(Kind.PAYMENT, "Pay the deposit", 85), 250_000L, PaymentKind.DEPOSIT), DAY), category), vendor)));

        Map<String, Object> suggestion = one(anna, id);
        assertThat(suggestion).containsEntry("state", "ADDED").containsEntry("fromDocument", true).containsEntry("decidedBy", null)
                .containsEntry("amount", 250_000);
        Map<String, Object> entry = entry((String) suggestion.get("madeEntryId"));
        assertThat(entry)
                .containsEntry("kind", "TASK")
                .containsEntry("title", "Pay the deposit")
                .containsEntry("amount", 250_000)
                .containsEntry("dueDate", "2026-12-01")
                .containsEntry("paymentKind", "DEPOSIT")
                .containsEntry("categoryId", category.toString())
                .containsEntry("vendorId", vendor.toString())
                .containsEntry("status", "NOT_STARTED")
                .containsEntry("fromSuggestionId", id.toString());
        assertThat(jdbc.queryForObject("SELECT created_by FROM entry", UUID.class)).isEqualTo(UUID.fromString(annaId));
        assertThat(ok(http.getAs(anna, "/api/budget"))).as("the budget counts it from that moment").containsEntry("committed", 250_000);
    }

    @Test
    void whereThePlannerDoesNotKnowTheVendorThePaymentIsAddedWithoutOneAndNoVendorIsMade() throws Exception {
        Proposal unknown = new Proposal(
                weddingId, "mail-x", Kind.PAYMENT, "Pay the deposit", "", null, DAY, 250_000L, PaymentKind.DEPOSIT, null, null,
                "Palace on the Hill", "info@palace-hill.example", "", "", "Jan", "jan@palace-hill.example", "Offer",
                java.time.Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(annaId), ANNAS_LINK, "Deposit: 2500,00 PLN", 90, true);

        UUID id = suggestions.handIn(unknown);

        assertThat(state(id)).isEqualTo("ADDED");
        assertThat(entry(entryOf(id))).containsEntry("vendorId", null).containsEntry("amount", 250_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor", Integer.class)).isZero();
    }

    @Test
    void aPaymentThatOnlyTheMailsWordsNameStillWaits() throws Exception {
        UUID id = suggestions.handIn(due(money(proposal(Kind.PAYMENT, "Pay the deposit", 100), 250_000L, PaymentKind.DEPOSIT), DAY));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(one(anna, id)).containsEntry("fromDocument", false);
        assertThat(entryCount()).isZero();
    }

    @Test
    void aVendorFromADocumentStillWaits() throws Exception {
        UUID id = suggestions.handIn(fromDocument(proposal(Kind.VENDOR, "Palace on the Hill", 100)));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(one(anna, id)).containsEntry("fromDocument", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor", Integer.class)).isZero();
    }

    @Test
    void aPaymentFromADocumentWithoutAnAmountWaits() throws Exception {
        // As when the offer names the sum in another currency: the reading hands it in with no amount and a note.
        UUID id = suggestions.handIn(fromDocument(due(proposal(Kind.PAYMENT, "Pay the deposit in euro", 100), DAY)));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aTaskAndAStepAreAddedAsBeforeWhetherOrNotTheyComeFromADocument() throws Exception {
        UUID task = suggestions.handIn(fromDocument(proposal(Kind.TASK, "Send the guest list", 90)));
        UUID step = suggestions.handIn(proposal(Kind.PLAN_ITEM, "Tasting", 90));

        assertThat(state(task)).isEqualTo("ADDED");
        assertThat(state(step)).isEqualTo("ADDED");
    }

    // ---------------------------------------------------------------- how sure, and switched off

    @Test
    void theThresholdHoldsForAPaymentToo() throws Exception {
        UUID below = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 84));
        UUID at = suggestions.handIn(paymentFromADocument("Pay the second instalment", 500_000, DAY, 85));

        assertThat(state(below)).isEqualTo("WAITING");
        assertThat(state(at)).isEqualTo("ADDED");
        assertThat(entries()).extracting(e -> e.get("title")).containsExactly("Pay the second instalment");
    }

    @Test
    void theWeddingsOwnThresholdIsTheOneThatCounts() throws Exception {
        assertThat(ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":95}"))).containsEntry("threshold", 95);

        UUID below = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 94));
        UUID at = suggestions.handIn(paymentFromADocument("Pay the second instalment", 500_000, DAY, 95));

        assertThat(state(below)).isEqualTo("WAITING");
        assertThat(state(at)).isEqualTo("ADDED");
    }

    @Test
    void whenSwitchedOffAPaymentFromADocumentWaitsEvenAtFullConfidence() throws Exception {
        assertThat(ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":false,\"threshold\":85}")))
                .containsEntry("addsByItself", false);

        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 100));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aPaymentThePlannerWouldRefuseWaitsAndNothingIsMade() throws Exception {
        // A category that is not the wedding's.
        UUID id = suggestions.handIn(fromDocument(category(
                due(money(proposal(Kind.PAYMENT, "Pay the deposit", 95), 250_000L, PaymentKind.DEPOSIT), DAY), UUID.randomUUID())));
        UUID withTheFlag = suggestions.handIn(fromDocument(category(due(money(proposal(Kind.PAYMENT, "Pay again", 95), 1_000L, PaymentKind.FINAL), DAY), UUID.randomUUID())));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(state(withTheFlag)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
        assertThat(state(suggestions.handIn(paymentFromADocument("Pay for real", 777_000, DAY, 95)))).isEqualTo("ADDED");
    }

    // ---------------------------------------------------------------- the planner has it already

    @Test
    void aTaskWithTheSameAmountAndDayAlreadyInThePlanMakesTheProposalWait() throws Exception {
        made(payment("Deposit paid by hand", 250_000L, DAY, "NOT_STARTED"));

        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(entryCount()).as("nothing was counted twice").isEqualTo(1);
    }

    @Test
    void thatHoldsWhateverTheStatusOfTheTaskInThePlan() throws Exception {
        for (String status : List.of("DONE", "DROPPED", "IN_PROGRESS")) {
            jdbc.update("DELETE FROM entry");
            Map<String, Object> existing = payment("Deposit " + status, 250_000L, DAY, status);
            made(existing);

            UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit " + status, 250_000, DAY, 99));

            assertThat(state(id)).as(status).isEqualTo("WAITING");
        }
    }

    @Test
    void aWaitingSuggestionWithTheSameAmountAndDayMakesTheProposalWait() throws Exception {
        UUID first = suggestions.handIn(due(money(proposal(Kind.PAYMENT, "The mail's deposit", 60), 250_000L, PaymentKind.DEPOSIT), DAY));
        assertThat(state(first)).isEqualTo("WAITING");

        UUID second = suggestions.handIn(paymentFromADocument("The offer's deposit", 250_000, DAY, 99));

        assertThat(state(second)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void anAddedSuggestionWithTheSameAmountAndDayMakesTheProposalWaitToo() throws Exception {
        UUID first = suggestions.handIn(paymentFromADocument("The offer's deposit", 250_000, DAY, 99));
        assertThat(state(first)).isEqualTo("ADDED");

        UUID second = suggestions.handIn(paymentFromADocument("The same deposit in the next letter", 250_000, DAY, 99));

        assertThat(state(second)).isEqualTo("WAITING");
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void aPaymentThatWasUndoneDoesNotComeBackByItself() throws Exception {
        UUID first = suggestions.handIn(paymentFromADocument("The offer's deposit", 250_000, DAY, 99));
        assertThat(undo(piotr, first)).containsEntry("state", "REJECTED");
        assertThat(entryCount()).isZero();

        UUID second = suggestions.handIn(paymentFromADocument("The same deposit in the next letter", 250_000, DAY, 99));

        // Somebody said no to this payment: the next proposal of it waits for a person.
        assertThat(state(second)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aPaymentThatWasKeptAndThenRemovedDoesNotComeBackByItselfEither() throws Exception {
        UUID first = suggestions.handIn(paymentFromADocument("The offer's deposit", 250_000, DAY, 99));
        assertThat(keep(anna, first)).containsEntry("state", "ACCEPTED");
        assertThat(http.deleteAs(anna, "/api/entries/" + entryOf(first)).statusCode()).isEqualTo(204);

        UUID second = suggestions.handIn(paymentFromADocument("The same deposit in the next letter", 250_000, DAY, 99));

        assertThat(state(second)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void anAddedSuggestionWhoseTaskWasRemovedByAPersonStillCountsAsProposed() throws Exception {
        UUID first = suggestions.handIn(paymentFromADocument("The offer's deposit", 250_000, DAY, 99));
        assertThat(http.deleteAs(anna, "/api/entries/" + entryOf(first)).statusCode()).isEqualTo(204);
        assertThat(entryCount()).isZero();

        UUID second = suggestions.handIn(paymentFromADocument("The same deposit in the next letter", 250_000, DAY, 99));

        assertThat(state(first)).isEqualTo("ADDED");
        assertThat(state(second)).isEqualTo("WAITING");
    }

    @Test
    void aDifferentAmountOrAnotherDayIsAnotherPayment() throws Exception {
        made(payment("Deposit paid by hand", 250_000L, DAY, "NOT_STARTED"));

        UUID otherAmount = suggestions.handIn(paymentFromADocument("Other amount", 250_001, DAY, 99));
        UUID otherDay = suggestions.handIn(paymentFromADocument("Other day", 250_000, DAY.plusDays(1), 99));
        UUID noDay = suggestions.handIn(paymentFromADocument("No day", 250_000, null, 99));

        assertThat(state(otherAmount)).isEqualTo("ADDED");
        assertThat(state(otherDay)).isEqualTo("ADDED");
        assertThat(state(noDay)).isEqualTo("ADDED");
    }

    @Test
    void bothWithoutADayIsTheSameDay() throws Exception {
        made(payment("Deposit paid by hand", 250_000L, null, "NOT_STARTED"));

        UUID again = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, null, 99));
        UUID other = suggestions.handIn(paymentFromADocument("Pay the other", 300_000, null, 99));
        UUID alsoNone = suggestions.handIn(due(money(proposal(Kind.PAYMENT, "Waiting without a day", 60), 300_000L, PaymentKind.FINAL), null));
        UUID third = suggestions.handIn(paymentFromADocument("Pay the other again", 300_000, null, 99));

        assertThat(state(again)).isEqualTo("WAITING");
        assertThat(state(other)).isEqualTo("ADDED");
        assertThat(state(alsoNone)).isEqualTo("WAITING");
        assertThat(state(third)).isEqualTo("WAITING");
    }

    @Test
    void aTaskThatIsNotAPaymentWithTheSameDayDoesNotCount() throws Exception {
        made(newEntryOn("Call the hall", DAY));

        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));

        assertThat(state(id)).isEqualTo("ADDED");
    }

    // ---------------------------------------------------------------- keep and undo

    @Test
    void undoRemovesTheAddedPaymentAndRejectsTheSuggestion() throws Exception {
        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));

        Map<String, Object> undone = undo(piotr, id);

        assertThat(undone).containsEntry("state", "REJECTED").containsEntry("madeEntryId", null);
        assertThat(entryCount()).isZero();
        assertThat(ok(http.getAs(anna, "/api/budget"))).containsEntry("committed", 0);
    }

    @Test
    void keepAcceptsTheSuggestionAndTheTaskStays() throws Exception {
        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));

        Map<String, Object> kept = keep(piotr, id);

        assertThat(kept).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", piotrId).containsEntry("fromDocument", true);
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void undoAfterThePaymentWasMarkedPaidIsRefusedAndTheSuggestionCountsAsKept() throws Exception {
        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));
        String entry = entryOf(id);
        assertThat(http.putJsonAs(anna, "/api/entries/" + entry + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(200);

        assertProblem(post(anna, "/api/suggestions/" + id + "/undo"), 409, "entry-changed");

        assertThat(state(id)).isEqualTo("ACCEPTED");
        assertThat(entryCount()).isEqualTo(1);
        assertThat(entry(entry)).containsEntry("status", "DONE");
    }

    @Test
    void undoAfterThePaymentWasEditedIsRefusedToo() throws Exception {
        UUID id = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));
        Map<String, Object> saved = entry(entryOf(id));
        Map<String, Object> change = retitled(saved, "Pay the deposit, by transfer");
        change.put("amount", saved.get("amount"));
        change.put("vendorId", null);
        change.put("paymentKind", saved.get("paymentKind"));
        assertThat(http.putJsonAs(anna, "/api/entries/" + saved.get("id"), TestHttp.jsonOf(change)).statusCode()).isEqualTo(200);

        assertProblem(post(anna, "/api/suggestions/" + id + "/undo"), 409, "entry-changed");

        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void anAddedPaymentIsListedUnderAddedAndNotUnderWaitingAndAnswersFromDocument() throws Exception {
        UUID added = suggestions.handIn(paymentFromADocument("Pay the deposit", 250_000, DAY, 99));
        UUID waiting = suggestions.handIn(due(money(proposal(Kind.PAYMENT, "Pay by the mail", 60), 100L, PaymentKind.FINAL), DAY.plusDays(3)));

        assertThat(ids(anna, "?state=ADDED")).containsExactly(added.toString());
        assertThat(ids(anna, "")).containsExactly(waiting.toString());
        List<Map<String, Object>> all = TestHttp.jsonList(http.getAs(anna, "/api/suggestions?state=ALL"));
        assertThat(all).extracting(s -> s.get("fromDocument")).containsExactlyInAnyOrder(true, false);
    }

    // ---------------------------------------------------------------- the table says the same

    @Test
    void theTableLetsAPaymentFromADocumentBeAddedButNotOneThatOnlyTheMailNames() throws Exception {
        UUID entry = UUID.fromString(entryOf(suggestions.handIn(proposal(Kind.TASK, "A task that makes an entry", 99))));
        UUID mailOnly = suggestions.handIn(due(money(proposal(Kind.PAYMENT, "Mail only", 60), 100L, PaymentKind.FINAL), DAY));
        UUID document = suggestions.handIn(fromDocument(due(money(proposal(Kind.PAYMENT, "From a document", 60), 200L, PaymentKind.FINAL), DAY)));
        UUID withoutAmount = suggestions.handIn(fromDocument(proposal(Kind.PAYMENT, "Without an amount", 60)));
        UUID vendor = suggestions.handIn(fromDocument(proposal(Kind.VENDOR, "A vendor", 60)));

        for (UUID refused : List.of(mailOnly, withoutAmount, vendor)) {
            assertThatThrownBy(() -> jdbc.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", entry, refused))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("UPDATE suggestion SET state = 'ADDED', made_entry_id = ? WHERE id = ?", entry, document);
        assertThat(state(document)).isEqualTo("ADDED");
        assertThat(state(mailOnly)).isEqualTo("WAITING");
    }

    // ---------------------------------------------------------------- helpers

    private UUID vendor(String name) throws Exception {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", "jan@palace-hill.example");
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        HttpResponse<String> created = http.postJsonAs(anna, "/api/vendors", TestHttp.jsonOf(vendor));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString((String) TestHttp.json(created).get("id"));
    }

    private static Map<String, Object> payment(String title, long amount, LocalDate day, String status) {
        Map<String, Object> entry = newEntry("TASK", title);
        entry.put("amount", amount);
        entry.put("paymentKind", "DEPOSIT");
        entry.put("dueDate", day == null ? null : day.toString());
        entry.put("status", status);
        entry.put("vendorId", null);
        return entry;
    }

    private static Map<String, Object> newEntryOn(String title, LocalDate day) {
        Map<String, Object> entry = newEntry("TASK", title);
        entry.put("dueDate", day.toString());
        return entry;
    }
}
