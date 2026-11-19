package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** S05 section 4: keeping what was added by itself, undoing it, and the thirty days. */
class KeepAndUndoIntegrationTest extends AddedByItselfTestBase {

    // ---------------------------------------------------------------- keep

    @Test
    void keepingAcceptsTheSuggestionInTheCallersNameAndChangesNothingElse() throws Exception {
        UUID id = added("Book the photographer");
        String entryId = entryOf(id);
        Map<String, Object> before = entry(entryId);

        Map<String, Object> kept = keep(piotr, id);

        assertThat(kept).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", piotrId).containsEntry("madeEntryId", entryId);
        assertThat(kept.get("decidedAt")).isNotNull();
        assertThat(entry(entryId)).as("the entry is as it was, version and all").isEqualTo(before);
        assertThat(ids(anna, "?state=ADDED")).isEmpty();
        assertThat(ids(anna, "?state=ACCEPTED")).containsExactly(id.toString());
    }

    @Test
    void keepingTwiceChangesNothingAndAnswersTheSameAlsoForTheOtherOwner() throws Exception {
        UUID id = added("Book the photographer");

        Map<String, Object> first = keep(anna, id);
        Map<String, Object> again = keep(anna, id);
        Map<String, Object> byTheOther = keep(piotr, id);

        assertThat(again).isEqualTo(first);
        assertThat(sameButTheLink(byTheOther)).as("the first decision stands: it is still Anna's").isEqualTo(sameButTheLink(first));
    }

    // ---------------------------------------------------------------- undo

    @Test
    void undoingRemovesTheEntryAndRejectsTheSuggestionInOneAction() throws Exception {
        UUID other = added("A task that stays");
        UUID siblingFromTheSameMessage = suggestions.handIn(proposal(Kind.PLAN_ITEM, "Another step of the same message", 90));
        UUID id = added("Book the photographer");
        UUID waitingSibling = suggestions.handIn(proposal(Kind.TASK, "Waits", 40));
        String gone = entryOf(id);

        Map<String, Object> undone = undo(piotr, id);

        assertThat(undone).containsEntry("state", "REJECTED").containsEntry("decidedBy", piotrId).containsEntry("madeEntryId", null);
        assertThat(undone.get("decidedAt")).isNotNull();
        assertThat(http.getAs(anna, "/api/entries/" + gone).statusCode()).isEqualTo(404);
        assertThat(entries()).as("nothing else was touched").hasSize(2);
        assertThat(state(other)).isEqualTo("ADDED");
        assertThat(state(siblingFromTheSameMessage)).isEqualTo("ADDED");
        assertThat(state(waitingSibling)).isEqualTo("WAITING");
        assertThat(ids(anna, "?state=ADDED")).doesNotContain(id.toString());
        assertThat(ids(anna, "?state=REJECTED")).containsExactly(id.toString());
    }

    @Test
    void undoingTwiceChangesNothingAndAnswersTheSame() throws Exception {
        UUID id = added("Book the photographer");

        Map<String, Object> first = undo(anna, id);
        Map<String, Object> again = undo(anna, id);
        Map<String, Object> byTheOther = undo(piotr, id);

        assertThat(again).isEqualTo(first);
        assertThat(sameButTheLink(byTheOther)).isEqualTo(sameButTheLink(first));
        assertThat(entries()).isEmpty();
    }

    @Test
    void anUndoneStepOfThePlanIsGoneToo() throws Exception {
        UUID id = addedStep("Tasting");

        undo(anna, id);

        assertThat(entryCount()).isZero();
    }

    // ---------------------------------------------------------------- a decision stands

    @Test
    void keepingAnUndoneOneAndUndoingAKeptOneAreRefusedAsAlreadyDecided() throws Exception {
        UUID undone = added("Undone");
        UUID kept = added("Kept");
        undo(anna, undone);
        keep(anna, kept);

        assertProblem(post(piotr, "/api/suggestions/" + undone + "/keep"), 409, "already-decided");
        assertProblem(post(piotr, "/api/suggestions/" + kept + "/undo"), 409, "already-decided");

        assertThat(one(anna, undone)).containsEntry("state", "REJECTED").containsEntry("decidedBy", annaId);
        assertThat(one(anna, kept)).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", annaId);
        assertThat(entry(entryOf(kept))).containsEntry("title", "Kept");
    }

    @Test
    void acceptingOrRejectingOneThatWasAddedByItselfIsAlreadyDecidedAndSaysNobodyDecidedIt() throws Exception {
        UUID id = added("Book the photographer");

        HttpResponse<String> accept = post(anna, "/api/suggestions/" + id + "/accept");
        HttpResponse<String> reject = post(piotr, "/api/suggestions/" + id + "/reject");

        assertProblem(accept, 409, "already-decided");
        assertProblem(reject, 409, "already-decided");
        assertThat((String) TestHttp.json(accept).get("detail")).contains("added by itself").contains("nobody has decided it");
        assertThat(one(anna, id)).containsEntry("state", "ADDED").containsEntry("decidedBy", null).containsEntry("decidedAt", null);
        assertThat(entryCount()).isEqualTo(1);
    }

    @Test
    void keepingOrUndoingOneThatStillWaitsIsABadRequestWithAPlainSentence() throws Exception {
        UUID waiting = suggestions.handIn(proposal(Kind.TASK, "Waits", 40));

        HttpResponse<String> keep = post(anna, "/api/suggestions/" + waiting + "/keep");
        HttpResponse<String> undo = post(anna, "/api/suggestions/" + waiting + "/undo");

        assertProblem(keep, 400, "bad-request");
        assertProblem(undo, 400, "bad-request");
        assertThat((String) TestHttp.json(keep).get("detail")).contains("still waiting");
        assertThat(state(waiting)).isEqualTo("WAITING");
        assertProblem(post(anna, "/api/suggestions/" + UUID.randomUUID() + "/keep"), 404, "not-found");
        assertProblem(post(anna, "/api/suggestions/" + UUID.randomUUID() + "/undo"), 404, "not-found");
    }

    @Test
    void whatWasAcceptedByAPersonCannotBeUndoneAndKeepingItChangesNothing() throws Exception {
        UUID waiting = suggestions.handIn(proposal(Kind.TASK, "Waits", 40));
        Map<String, Object> accepted = ok(post(anna, "/api/suggestions/" + waiting + "/accept"));

        assertThat(sameButTheLink(keep(piotr, waiting))).isEqualTo(sameButTheLink(accepted));
        assertProblem(post(piotr, "/api/suggestions/" + waiting + "/undo"), 409, "already-decided");
        assertThat(entryCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- an entry that is no longer what was added

    @Test
    void anEntryThatWasEditedStaysAndTheSuggestionCountsAsKept() throws Exception {
        UUID id = added("Book the photographer");
        Map<String, Object> saved = entry(entryOf(id));
        assertThat(http.putJsonAs(piotr, "/api/entries/" + saved.get("id"), TestHttp.jsonOf(retitled(saved, "Book the photographer and the videographer")))
                .statusCode()).isEqualTo(200);

        assertRefusedAndKept(id, anna, "Book the photographer and the videographer");
    }

    @Test
    void anEntryThatWasTickedOffStays() throws Exception {
        UUID id = added("Book the photographer");
        assertThat(http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(200);

        assertRefusedAndKept(id, piotr, "Book the photographer");
    }

    @Test
    void anEntryWhoseStatusChangedInAnyWayStays() throws Exception {
        UUID id = added("Book the photographer");
        // Started, and back to not started: it is a different entry from the one that was added (its version moved on).
        http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/status", "{\"status\":\"IN_PROGRESS\"}");
        http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/status", "{\"status\":\"NOT_STARTED\"}");

        assertRefusedAndKept(id, anna, "Book the photographer");
    }

    @Test
    void aStepWithATaskOnItStays() throws Exception {
        UUID id = addedStep("Tasting");
        Map<String, Object> task = newEntry("TASK", "Pick a date");
        task.put("parentId", entryOf(id));
        made(task);

        assertRefusedAndKept(id, anna, "Tasting");
        assertThat(entries()).hasSize(2);
    }

    @Test
    void aStepWithAPaymentHungOnItStays() throws Exception {
        UUID id = addedStep("Tasting");
        Map<String, Object> payment = newEntry("TASK", "Pay for the tasting");
        payment.put("parentId", entryOf(id));
        payment.put("amount", 30_000);
        made(payment);

        assertRefusedAndKept(id, piotr, "Tasting");
    }

    @Test
    void aStepThatSomethingWaitsForStays() throws Exception {
        UUID id = addedStep("Tasting");
        Map<String, Object> later = made(newEntry("PLAN_ITEM", "Choose the menu"));
        assertThat(http.putJsonAs(anna, "/api/entries/" + later.get("id") + "/dependencies/" + entryOf(id), "").statusCode()).isEqualTo(200);

        assertRefusedAndKept(id, anna, "Tasting");
    }

    @Test
    void aStepThatWaitsForSomethingStays() throws Exception {
        UUID id = addedStep("Tasting");
        Map<String, Object> earlier = made(newEntry("PLAN_ITEM", "Choose the venue"));
        assertThat(http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/dependencies/" + earlier.get("id"), "").statusCode()).isEqualTo(200);

        assertRefusedAndKept(id, anna, "Tasting");
        assertThat(entries()).hasSize(2);
    }

    @Test
    void aStepThatAnInspirationIsPutOnStays() throws Exception {
        UUID id = addedStep("Tasting");
        Map<String, Object> idea = new HashMap<>();
        idea.put("url", "https://example.com/tasting-menu");
        idea.put("boardId", null);
        idea.put("title", "A menu we like");
        idea.put("note", "");
        idea.put("tags", List.of());
        idea.put("planItemId", entryOf(id));
        HttpResponse<String> saved = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(idea));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);

        assertRefusedAndKept(id, anna, "Tasting");
        assertThat(http.getAs(anna, "/api/inspirations/" + TestHttp.json(saved).get("id")).statusCode()).isEqualTo(200);
    }

    @Test
    void anEntryThatWasRemovedByHandIsGoneAndTheSuggestionCountsAsKept() throws Exception {
        UUID id = added("Book the photographer");
        String entryId = entryOf(id);
        assertThat(http.deleteAs(anna, "/api/entries/" + entryId).statusCode()).isEqualTo(204);

        HttpResponse<String> response = post(piotr, "/api/suggestions/" + id + "/undo");

        assertProblem(response, 409, "entry-changed");
        // It does not say that the entry stays: it is not there.
        assertThat(response.body()).contains("no longer in your plan").doesNotContain("stays in your plan");
        assertThat(one(anna, id)).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", piotrId);
    }

    @Test
    void theRefusalSaysSoInWordsForAPersonAndDecidesFromTheTypeOnly() throws Exception {
        UUID id = added("Book the photographer");
        http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/status", "{\"status\":\"DONE\"}");

        HttpResponse<String> response = post(anna, "/api/suggestions/" + id + "/undo");

        assertThat(TestHttp.json(response))
                .containsEntry("status", 409)
                .containsEntry("type", "urn:wedding-planner:problem:entry-changed");
        assertThat((String) TestHttp.json(response).get("detail")).contains("changed after it was added").contains("stays");
    }

    @Test
    void afterTheRefusalUndoingAgainIsAlreadyDecidedBecauseItCountsAsKept() throws Exception {
        UUID id = added("Book the photographer");
        http.putJsonAs(anna, "/api/entries/" + entryOf(id) + "/status", "{\"status\":\"DONE\"}");
        assertProblem(post(anna, "/api/suggestions/" + id + "/undo"), 409, "entry-changed");

        assertProblem(post(piotr, "/api/suggestions/" + id + "/undo"), 409, "already-decided");
        assertThat(keep(piotr, id)).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", annaId);
    }

    @Test
    void anEntryThatOnlyHasSomethingElseInThePlanAroundItIsStillUntouched() throws Exception {
        UUID id = addedStep("Tasting");
        made(newEntry("PLAN_ITEM", "Choose the venue"));
        made(newEntry("TASK", "Unrelated"));

        undo(anna, id);

        assertThat(entries()).extracting(e -> e.get("title")).containsExactlyInAnyOrder("Choose the venue", "Unrelated");
    }

    // ---------------------------------------------------------------- thirty days

    @Test
    void atTwentyNineDaysItIsStillInTheListAndCanBeUndone() throws Exception {
        UUID id = added("Book the photographer");

        TestClock.advance(Duration.ofDays(29));
        String annaNow = TestSignIn.signIn(http, ANNA).accessToken();

        assertThat(ids(annaNow, "?state=ADDED")).containsExactly(id.toString());
        assertThat(undo(annaNow, id)).containsEntry("state", "REJECTED");
        assertThat(entryCount()).isZero();
    }

    @Test
    void atThirtyOneDaysItCountsAsKeptInEveryAnswerAndCannotBeUndone() throws Exception {
        UUID id = added("Book the photographer");
        String entryId = entryOf(id);

        TestClock.advance(Duration.ofDays(31));
        String annaNow = TestSignIn.signIn(http, ANNA).accessToken();
        String piotrNow = TestSignIn.signIn(http, PIOTR).accessToken();

        assertThat(ids(annaNow, "?state=ADDED")).isEmpty();
        assertThat(ids(annaNow, "?state=ACCEPTED")).containsExactly(id.toString());
        assertThat(ids(piotrNow, "?state=ALL")).containsExactly(id.toString());
        assertThat(ids(annaNow, "?state=WAITING")).isEmpty();
        Map<String, Object> answered = one(piotrNow, id);
        assertThat(answered).containsEntry("state", "ACCEPTED").containsEntry("decidedBy", null).containsEntry("madeEntryId", entryId);
        assertThat(answered.get("decidedAt")).as("the moment it came to count as kept").isNotNull();
        assertProblem(post(annaNow, "/api/suggestions/" + id + "/undo"), 409, "already-decided");
        assertProblem(post(annaNow, "/api/suggestions/" + id + "/reject"), 409, "already-decided");
        assertThat(sameButTheLink(keep(annaNow, id))).as("keeping what already counts as kept changes nothing").isEqualTo(sameButTheLink(answered));
        assertThat(sameButTheLink(ok(post(annaNow, "/api/suggestions/" + id + "/accept")))).isEqualTo(sameButTheLink(answered));
        assertThat(http.getAs(annaNow, "/api/entries/" + entryId).statusCode()).as("the entry stays").isEqualTo(200);
        assertThat(state(id)).as("nothing had to run: the row itself is as it was").isEqualTo("ADDED");
    }

    // ---------------------------------------------------------------- helpers

    /** The other owner is answered the link without the part that names Anna's mailbox: everything else is the same. */
    private static Map<String, Object> sameButTheLink(Map<String, Object> answer) {
        Map<String, Object> same = new HashMap<>(answer);
        same.remove("mailLink");
        return same;
    }

    /** Undo is refused with entry-changed, the entry is still there, and the suggestion counts as kept by the one who asked. */
    private void assertRefusedAndKept(UUID id, String asToken, String titleOfTheEntry) throws Exception {
        String entryId = entryOf(id);
        String who = asToken.equals(anna) ? annaId : piotrId;

        HttpResponse<String> response = post(asToken, "/api/suggestions/" + id + "/undo");

        assertProblem(response, 409, "entry-changed");
        assertThat(http.getAs(anna, "/api/entries/" + entryId).statusCode()).as("the entry stays").isEqualTo(200);
        assertThat(entry(entryId)).containsEntry("title", titleOfTheEntry);
        Map<String, Object> now = one(anna, id);
        assertThat(now).as("the refusal did not roll back that it counts as kept")
                .containsEntry("state", "ACCEPTED").containsEntry("decidedBy", who).containsEntry("madeEntryId", entryId);
        assertThat(ids(anna, "?state=ADDED")).doesNotContain(id.toString());
    }
}
