package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.PaymentKind;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** S05 sections 2, 3 and 5: which proposals the import adds by itself, and what that makes. */
class SuggestionsAddedByItselfIntegrationTest extends AddedByItselfTestBase {

    // ---------------------------------------------------------------- the four conditions, one at a time

    @Test
    void aTaskAtTheThresholdIsAddedAtOnceWithTheProposalsValues() throws Exception {
        UUID id = suggestions.handIn(noted(due(proposal(Kind.TASK, "Send the guest list to the printer", 85), LocalDate.of(2027, 3, 1)),
                "Before the proofs."));

        Map<String, Object> suggestion = one(anna, id);
        assertThat(suggestion).containsEntry("state", "ADDED").containsEntry("decidedBy", null).containsEntry("decidedAt", null);
        Map<String, Object> entry = entry((String) suggestion.get("madeEntryId"));
        assertThat(entry)
                .containsEntry("kind", "TASK")
                .containsEntry("title", "Send the guest list to the printer")
                .containsEntry("notes", "Before the proofs.")
                .containsEntry("dueDate", "2027-03-01")
                .containsEntry("status", "NOT_STARTED")
                .containsEntry("amount", null)
                .containsEntry("fromSuggestionId", id.toString());
        assertThat(entries()).hasSize(1);
    }

    @Test
    void aStepOfThePlanAtTheThresholdIsAddedWithItsDays() throws Exception {
        UUID id = suggestions.handIn(starting(due(proposal(Kind.PLAN_ITEM, "Tasting at the Palace on the Hill", 85),
                LocalDate.of(2027, 1, 12)), LocalDate.of(2027, 1, 10)));

        assertThat(state(id)).isEqualTo("ADDED");
        assertThat(entry(entryOf(id)))
                .containsEntry("kind", "PLAN_ITEM")
                .containsEntry("startDate", "2027-01-10")
                .containsEntry("dueDate", "2027-01-12")
                .containsEntry("fromSuggestionId", id.toString());
    }

    @Test
    void theEdgeOfTheThresholdIs85Added84Waits() throws Exception {
        UUID below = suggestions.handIn(proposal(Kind.TASK, "Ask about parking", 84));
        UUID at = suggestions.handIn(proposal(Kind.TASK, "Ask about the cake", 85));

        assertThat(state(below)).isEqualTo("WAITING");
        assertThat(entryOf(below)).isNull();
        assertThat(state(at)).isEqualTo("ADDED");
        assertThat(entries()).extracting(e -> e.get("title")).containsExactly("Ask about the cake");
    }

    @Test
    void aPaymentAtFullConfidenceStillWaitsAndMakesNothing() throws Exception {
        UUID id = suggestions.handIn(money(proposal(Kind.PAYMENT, "Deposit for the Palace on the Hill", 100), 500_000L, PaymentKind.DEPOSIT));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aTaskThatCarriesAnAmountIsAPaymentAndWaitsWhateverItIsCalled() throws Exception {
        UUID id = suggestions.handIn(money(proposal(Kind.TASK, "Pay the florist", 100), 120_000L, null));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aVendorAtFullConfidenceStillWaitsAndMakesNothing() throws Exception {
        UUID id = suggestions.handIn(proposal(Kind.VENDOR, "Palace on the Hill", 100));

        assertThat(state(id)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor", Integer.class)).isZero();
        assertThat(entryCount()).isZero();
    }

    @Test
    void whenSwitchedOffEverythingWaitsEvenAtFullConfidence() throws Exception {
        assertThat(ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":false,\"threshold\":85}")))
                .containsEntry("addsByItself", false);

        UUID task = suggestions.handIn(proposal(Kind.TASK, "Ask about parking", 100));
        UUID step = suggestions.handIn(proposal(Kind.PLAN_ITEM, "Tasting", 100));

        assertThat(state(task)).isEqualTo("WAITING");
        assertThat(state(step)).isEqualTo("WAITING");
        assertThat(entryCount()).isZero();
    }

    @Test
    void aProposalThePlannerWouldRefuseWaitsAndNothingIsHalfMade() throws Exception {
        // A task has only a due date: the planner refuses a start date on it, as it would from the form.
        UUID startOnATask = suggestions.handIn(starting(due(proposal(Kind.TASK, "Book the band", 95), LocalDate.of(2027, 2, 1)),
                LocalDate.of(2027, 1, 1)));
        // A category that is not the wedding's.
        UUID strangeCategory = suggestions.handIn(category(proposal(Kind.TASK, "Choose the flowers", 95), UUID.randomUUID()));
        // A step that starts but has no end.
        UUID noEnd = suggestions.handIn(starting(proposal(Kind.PLAN_ITEM, "Fittings", 95), LocalDate.of(2027, 1, 1)));
        // A kind of payment without an amount.
        UUID kindOnly = suggestions.handIn(money(proposal(Kind.TASK, "Pay later", 95), null, PaymentKind.FINAL));

        for (UUID id : List.of(startOnATask, strangeCategory, noEnd, kindOnly)) {
            assertThat(state(id)).as("a refused proposal waits").isEqualTo("WAITING");
            assertThat(entryOf(id)).isNull();
        }
        assertThat(entryCount()).as("nothing was made, not even half").isZero();
        // And a good one handed in afterwards is still added: nothing was left in a state that spoils the next.
        assertThat(state(suggestions.handIn(proposal(Kind.TASK, "Ask about parking", 95)))).isEqualTo("ADDED");
        assertThat(entryCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- whose entry it is

    @Test
    void theEntryIsMadeInTheNameOfTheOwnerWhoseInboxItWasIn() throws Exception {
        UUID annas = suggestions.handIn(proposal(Kind.TASK, "From Anna's mailbox", 90));
        UUID piotrs = suggestions.handIn(inPiotrsInbox(proposal(Kind.TASK, "From Piotr's mailbox", 90)));

        assertThat(createdBy(annas)).isEqualTo(UUID.fromString(annaId));
        assertThat(createdBy(piotrs)).isEqualTo(UUID.fromString(piotrId));
    }

    @Test
    void anOwnerWhoHasNeverSignedInIsStillTheOneTheEntryIsMadeFor() throws Exception {
        jdbc.update("UPDATE wedding_member SET user_id = NULL WHERE id = ?", UUID.fromString(piotrId));

        UUID id = suggestions.handIn(inPiotrsInbox(proposal(Kind.TASK, "From a mailbox of someone not yet in", 90)));

        assertThat(state(id)).isEqualTo("ADDED");
        assertThat(createdBy(id)).isEqualTo(UUID.fromString(piotrId));
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void handingInTheSameKeyAgainAddsOnce() throws Exception {
        Proposal proposal = proposal(Kind.TASK, "Book the photographer", 90);

        UUID first = suggestions.handIn(proposal);
        UUID second = suggestions.handIn(proposal);

        assertThat(second).isEqualTo(first);
        assertThat(entryCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suggestion", Integer.class)).isEqualTo(1);
        // A suggestion that was undone is not added again by the same message either.
        undo(anna, first);
        assertThat(suggestions.handIn(proposal)).isEqualTo(first);
        assertThat(entryCount()).isZero();
        assertThat(state(first)).isEqualTo("REJECTED");
    }

    @Test
    void thePlannersRulesAppliedToTheProposalMakeTheEntryWithItsValuesAndNothingElse() throws Exception {
        UUID id = suggestions.handIn(due(proposal(Kind.TASK, "Choose the music", 90), LocalDate.of(2027, 4, 1)));

        Map<String, Object> entry = entry(entryOf(id));

        assertThat(entry).containsEntry("assigneeIds", List.of()).containsEntry("parentId", null).containsEntry("vendorId", null)
                .containsEntry("version", 0).containsEntry("priorityMode", "AUTO");
    }

    // ---------------------------------------------------------------- the lists

    @Test
    void theListsKeepWhatWaitsAndWhatWasAddedApart() throws Exception {
        UUID waiting = suggestions.handIn(proposal(Kind.TASK, "Waits", 60));
        UUID older = suggestions.handIn(withReceived(proposal(Kind.TASK, "Added earlier", 90), "2026-11-10T09:00:00Z"));
        UUID newer = suggestions.handIn(withReceived(proposal(Kind.TASK, "Added later", 90), "2026-11-12T09:00:00Z"));
        UUID kept = suggestions.handIn(proposal(Kind.PLAN_ITEM, "Kept", 90));
        keep(anna, kept);

        assertThat(ids(anna, "")).containsExactly(waiting.toString());
        assertThat(ids(anna, "?state=WAITING")).containsExactly(waiting.toString());
        assertThat(ids(anna, "?state=ADDED")).as("the newest first").containsExactly(newer.toString(), older.toString());
        assertThat(ids(piotr, "?state=ADDED")).containsExactly(newer.toString(), older.toString());
        assertThat(ids(anna, "?state=ACCEPTED")).containsExactly(kept.toString());
        assertThat(ids(anna, "?state=ALL")).containsExactlyInAnyOrder(waiting.toString(), older.toString(), newer.toString(), kept.toString());
        assertThat(ids(anna, "?state=REJECTED")).isEmpty();
    }

    // ---------------------------------------------------------------- one suggestion

    @Test
    void oneSuggestionIsAnsweredToBothOwnersWithTheLinkAsEachMaySeeIt() throws Exception {
        UUID id = added("Book the photographer");

        Map<String, Object> mine = one(anna, id);
        Map<String, Object> theirs = one(piotr, id);

        assertThat(mine).containsEntry("id", id.toString()).containsEntry("state", "ADDED").containsEntry("mailLink", ANNAS_LINK)
                .containsEntry("title", "Book the photographer").containsEntry("senderAddress", "jan@palace-hill.example");
        assertThat((String) theirs.get("mailLink")).startsWith("https://mail.google.com/").doesNotContain("authuser").doesNotContain("anna.mailbox");
        assertThat(theirs).containsEntry("title", "Book the photographer").containsEntry("madeEntryId", mine.get("madeEntryId"));
    }

    @Test
    void anUnknownSuggestionIsNotFound() throws Exception {
        assertProblem(http.getAs(anna, "/api/suggestions/" + UUID.randomUUID()), 404, "not-found");
        assertProblem(http.getAs(piotr, "/api/suggestions/" + UUID.randomUUID()), 404, "not-found");
    }

    @Test
    void theOlderListAnswersWhatAPhoneThatOnlyAsksForWhatWaitsExpects() throws Exception {
        added("Book the photographer");
        UUID waiting = suggestions.handIn(proposal(Kind.TASK, "Needs a look", 50));

        HttpResponse<String> response = http.getAs(anna, "/api/suggestions");

        assertThat(TestHttp.jsonList(response)).extracting(one -> one.get("id")).containsExactly(waiting.toString());
    }

    // ---------------------------------------------------------------- the database holds the new truths too

    @Test
    void theDatabaseRefusesAnAddedSuggestionWithoutItsEntryOrWithADecider() {
        UUID id = suggestions.handIn(proposal(Kind.TASK, "Waits", 10));

        assertRefusedByTheDatabase("UPDATE suggestion SET state = 'ADDED' WHERE id = ?", id);
        UUID entry = UUID.randomUUID();
        assertRefusedByTheDatabase(
                "UPDATE suggestion SET state = 'ADDED', made_entry_id = '" + entry + "', decided_by = '" + annaId + "' WHERE id = ?", id);
        assertRefusedByTheDatabase(
                "UPDATE suggestion SET state = 'ADDED', made_entry_id = '" + entry + "', decided_at = now() WHERE id = ?", id);
        assertThat(state(id)).isEqualTo("WAITING");
    }

    @Test
    void theDatabaseRefusesAnAddedPaymentOrVendor() {
        UUID payment = suggestions.handIn(money(proposal(Kind.PAYMENT, "Deposit", 10), 1_000L, PaymentKind.DEPOSIT));
        UUID vendor = suggestions.handIn(proposal(Kind.VENDOR, "Palace on the Hill", 10));
        UUID taskWithMoney = suggestions.handIn(money(proposal(Kind.TASK, "Pay", 10), 1_000L, null));
        UUID entry = UUID.randomUUID();

        for (UUID id : List.of(payment, vendor, taskWithMoney)) {
            assertRefusedByTheDatabase("UPDATE suggestion SET state = 'ADDED', made_entry_id = '" + entry + "' WHERE id = ?", id);
        }
    }

    @Test
    void theDatabaseKeepsAnEntryOnlyForAnAcceptedOrAddedSuggestion() {
        UUID id = added("Book the photographer");

        assertRefusedByTheDatabase("UPDATE suggestion SET state = 'REJECTED', decided_by = '" + annaId + "', decided_at = now() WHERE id = ?", id);
    }

    @Test
    void theSettingsTableHoldsOnlyAThresholdFrom50To100() {
        assertRefusedByTheDatabase("INSERT INTO suggestion_setting (wedding_id, adds_by_itself, threshold, updated_at) "
                + "SELECT id, true, 49, now() FROM wedding WHERE ? IS NOT NULL", weddingId);
        assertRefusedByTheDatabase("INSERT INTO suggestion_setting (wedding_id, adds_by_itself, threshold, updated_at) "
                + "SELECT id, true, 101, now() FROM wedding WHERE ? IS NOT NULL", weddingId);
    }

    // ---------------------------------------------------------------- helpers

    private UUID createdBy(UUID suggestion) {
        return jdbc.queryForObject("SELECT created_by FROM entry WHERE id = ?", UUID.class, UUID.fromString(entryOf(suggestion)));
    }

    private static Proposal withReceived(Proposal p, String at) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(), p.amount(),
                p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(), p.vendorWebsite(),
                p.senderName(), p.senderAddress(), p.subject(), java.time.Instant.parse(at), p.inboxMemberId(), p.mailLink(),
                p.passage(), p.confidence());
    }

    private void assertRefusedByTheDatabase(String sql, UUID id) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
