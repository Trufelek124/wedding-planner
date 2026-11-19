package io.github.trufelek124.weddingplanner.mailreading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * What the service's trouble comes to, for both steps: an outcome each, never an exception, and no loop of our own
 * (the SDK retries twice by itself; a call that is refused for good is made once).
 */
class ReadingErrorsIntegrationTest extends ReadingTestBase {

    private record Case(String name, Supplier<Outcome<?>> call) {}

    private List<Case> bothSteps() {
        return List.of(
                new Case("triage", () -> triage.triage(triageInput("A message."))),
                new Case("extraction", () -> extraction.extract(extractionInput("A message."))));
    }

    private void each(Reply reply, Outcome.Why expected, int mostCalls, int fewestCalls) {
        for (Case step : bothSteps()) {
            service.reset();
            service.answerEveryCallWith(reply);

            Outcome<?> outcome = step.call().get();

            assertThat(outcome).as(step.name()).isEqualTo(new Outcome.Unanswered<>(expected, Used.NOTHING));
            assertThat(service.calls().size()).as(step.name() + ", calls").isBetween(fewestCalls, mostCalls);
        }
    }

    @Test
    void tooManyRequestsIsTryLaterAfterTheSdksOwnTwoRetries() {
        each(Reply.error(429, "rate_limit_error", "Body-Secret-Qx"), Outcome.Why.TRY_LATER, 3, 3);
    }

    @Test
    void overloadedIsTryLater() {
        each(Reply.error(529, "overloaded_error", "Body-Secret-Qx"), Outcome.Why.TRY_LATER, 3, 3);
    }

    @Test
    void aServerErrorIsTryLater() {
        each(Reply.error(500, "api_error", "Body-Secret-Qx"), Outcome.Why.TRY_LATER, 3, 3);
        each(Reply.error(503, "api_error", "Body-Secret-Qx"), Outcome.Why.TRY_LATER, 3, 3);
    }

    @Test
    void aConnectionThatFailsIsTryLater() {
        each(Reply.dropped(), Outcome.Why.TRY_LATER, 3, 1);
    }

    @Test
    void aKeyThatIsNotAcceptedStopsTheReadingAndIsNotAskedAgain() {
        each(Reply.error(401, "authentication_error", "invalid x-api-key Body-Secret-Qx"), Outcome.Why.NOT_ACCEPTED, 1, 1);
    }

    @Test
    void noPermissionStopsTheReading() {
        each(Reply.error(403, "permission_error", "Body-Secret-Qx"), Outcome.Why.NOT_ACCEPTED, 1, 1);
    }

    @Test
    void noCreditStopsTheReading() {
        each(Reply.error(402, "billing_error", "Body-Secret-Qx"), Outcome.Why.NOT_ACCEPTED, 1, 1);
    }

    @Test
    void aModelTheServiceDoesNotKnowStopsTheReadingToo() {
        each(Reply.error(404, "not_found_error", "model: Body-Secret-Qx"), Outcome.Why.NOT_ACCEPTED, 1, 1);
    }

    @Test
    void anyOtherBadRequestCannotBeUsedAndIsNotAskedAgain() {
        each(Reply.error(400, "invalid_request_error", "Body-Secret-Qx"), Outcome.Why.CANNOT_BE_USED, 1, 1);
        each(Reply.error(413, "request_too_large", "Body-Secret-Qx"), Outcome.Why.CANNOT_BE_USED, 1, 1);
    }

    @Test
    void aReplyThatIsNotAMessageAtAllCannotBeUsed() {
        each(new Reply(200, "<html>a proxy's page</html>", false), Outcome.Why.CANNOT_BE_USED, 1, 1);
    }

    @Test
    void troubleThatPassesIsNotSeenOnce_theSdkRetriesAndTheNextCallAnswers() {
        service.answerNextCallsWith(Reply.error(529, "overloaded_error", "busy"), Reply.says(triageAnswer(true, 88), "claude-haiku-5-5", 700, 100));

        Outcome<MailTriage.Triaged> outcome = triage.triage(triageInput("A message."));

        assertThat(outcome).isEqualTo(new Outcome.Answered<>(new MailTriage.Triaged(true, 88), new Used("claude-haiku-5-5", 700, 100)));
        assertThat(service.calls()).hasSize(2);
    }

    @Test
    void anAnswerThatReachedTheServiceReportsWhatItUsedWhateverItCameTo() {
        service.answerNextCallsWith(Reply.refusal(), Reply.message("max_tokens", "{}", 700, 2000, "%MODEL%"), Reply.says("nonsense", "claude-haiku-5-5", 701, 11));

        Outcome<MailTriage.Triaged> declined = triage.triage(triageInput("A."));
        Outcome<MailTriage.Triaged> cut = triage.triage(triageInput("B."));
        Outcome<MailTriage.Triaged> unshaped = triage.triage(triageInput("C."));

        assertThat(declined.used()).isEqualTo(new Used("claude-haiku-5-5", 77, 0));
        assertThat(cut.used()).isEqualTo(new Used("claude-haiku-5-5", 700, 2000));
        assertThat(unshaped.used()).isEqualTo(new Used("claude-haiku-5-5", 701, 11));
    }
}
