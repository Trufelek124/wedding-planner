package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.Unavailable;
import io.github.trufelek124.weddingplanner.mail.MailReading.Why;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mail.ReadingReport.State;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.trufelek124.weddingplanner.TestClock;

/** Each outcome of each step, and of fetching, comes to what S04 section 2 says: a verdict and its reason, or left as it is. */
class ReadingOutcomesIntegrationTest extends RoundTestBase {

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    /** One message in Anna's mailbox, noted, and a round with these answers. */
    private int readOne(String id, Reply... answers) {
        annaGets(id, "A question", POLISH);
        note();
        service.reset();
        service.answerNextCallsWith(answers);
        return round.round();
    }

    // ---------------------------------------------------------------- fetching

    @Test
    void aMessageGoneWithNoTextOrTooLargeCouldNotBeReadAndNothingIsSentAnywhere() {
        box().write("m-empty", "t-empty", daysAgo(2), JAN, "Empty", Mime.html("<html><body> </body></html>"), "INBOX");
        box().write("m-huge", "t-huge", daysAgo(2), JAN, "Huge", Mime.plain("y".repeat(2_200_000)), "INBOX");
        annaGets("m-gone", "Gone", POLISH);
        note();
        // Gmail no longer has the newest one, which is read first, by the time it is read.
        google.answerNextCallsToWith("/gmail/v1/users/me/threads", io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply.json(404, "{}"));
        service.reset();

        assertThat(round.round()).isEqualTo(3);

        assertThat(verdictOf("m-gone")).containsEntry("verdict", "UNREADABLE");
        assertThat(java.util.List.of(verdictOf("m-gone").get("unread_reason"), verdictOf("m-empty").get("unread_reason"), verdictOf("m-huge").get("unread_reason")))
                .containsExactlyInAnyOrder("GONE", "NO_TEXT", "TOO_LARGE");
        assertThat(verdictOf("m-empty")).containsEntry("unread_reason", "NO_TEXT");
        assertThat(verdictOf("m-huge")).containsEntry("unread_reason", "TOO_LARGE");
        assertThat(service.calls()).as("nothing was sent to the AI service").isEmpty();
    }

    // ---------------------------------------------------------------- triage

    @Test
    void triageDecliningIsCouldNotBeReadDeclinedAndExtractionIsNotAsked() {
        assertThat(readOne("m-1", Reply.refusal())).isEqualTo(1);

        assertThat(verdictOf("m-1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "DECLINED");
        assertThat(extractionCalls()).isZero();
    }

    @Test
    void triageAnsweringWhatCannotBeUsedIsUnusableAnswer() {
        assertThat(readOne("m-1", Reply.says("I think so, but I am not sure."))).isEqualTo(1);

        assertThat(verdictOf("m-1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "UNUSABLE_ANSWER");
    }

    @Test
    void triageSayingTryLaterLeavesTheMessageAsItIsAndTheReadingWaits() {
        service.answerEveryCallWith(Reply.error(529, "overloaded_error", "Overloaded"));
        annaGets("m-1", "A question", POLISH);
        note();

        assertThat(round.round()).isZero();

        assertThat(isRead("m-1")).isFalse();
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);
    }

    @Test
    void triageNotAcceptingTheKeyLeavesTheMessageAsItIsAndTheReadingStops() {
        service.answerEveryCallWith(Reply.error(401, "authentication_error", "invalid x-api-key"));
        annaGets("m-1", "A question", POLISH);
        note();

        assertThat(round.round()).isZero();

        assertThat(isRead("m-1")).isFalse();
        assertThat(round.report().state()).isEqualTo(State.KEY_NOT_ACCEPTED);
    }

    // ---------------------------------------------------------------- extraction

    @Test
    void extractionDecliningIsDeclined() {
        assertThat(readOne("m-1", triageSays(true, 90), Reply.refusal(), Reply.refusal())).isEqualTo(1);

        assertThat(verdictOf("m-1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "DECLINED");
    }

    @Test
    void extractionAnsweringWhatCannotBeUsedIsUnusableAnswer() {
        assertThat(readOne("m-1", triageSays(true, 90), Reply.says("Here are some ideas."))).isEqualTo(1);

        assertThat(verdictOf("m-1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "UNUSABLE_ANSWER");
    }

    @Test
    void extractionAnsweringSixProposalsIsTooManyProposalsAndNoneIsHandedIn() {
        String one = proposal("TASK", "Send the guest list", "w imieniu Pałacu na Wzgórzu potwierdzam");

        assertThat(readOne("m-1", triageSays(true, 90), extractionSays(one, one, one, one, one, one))).isEqualTo(1);

        assertThat(verdictOf("m-1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "TOO_MANY_PROPOSALS");
        assertThat(suggestionRows()).isZero();
    }

    @Test
    void extractionSayingTryLaterLeavesTheMessageAsItIsAndItsTriageIsPaidForAgain() {
        service.answerNextCallsWith(triageSays(true, 90));
        service.answerNextCallsWith(
                Reply.error(529, "overloaded_error", "x"), Reply.error(529, "overloaded_error", "x"), Reply.error(529, "overloaded_error", "x"));
        annaGets("m-1", "A question", POLISH);
        note();

        assertThat(round.round()).isZero();
        assertThat(isRead("m-1")).isFalse();
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);

        TestClock.advance(Duration.ofMinutes(2));
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        assertThat(round.round()).isEqualTo(1);
        assertThat(triageCalls()).as("triage was asked again").isEqualTo(1);
        assertThat(verdictOf("m-1")).containsEntry("verdict", "WEDDING");
    }

    @Test
    void extractionNotAcceptingTheKeyLeavesTheMessageAsItIsAndTheReadingStops() {
        service.answerNextCallsWith(triageSays(true, 90), Reply.error(402, "billing_error", "no credit"));
        annaGets("m-1", "A question", POLISH);
        note();

        assertThat(round.round()).isZero();

        assertThat(isRead("m-1")).isFalse();
        assertThat(round.report().state()).isEqualTo(State.KEY_NOT_ACCEPTED);
    }

    // ---------------------------------------------------------------- the unexpected

    @Test
    void anythingUnexpectedLeavesTheMessageAsItIsPutsItOffAndTheRoundGoesOn() {
        annaGets("m-old", "old", POLISH);
        annaGets("m-new", "new", POLISH);
        note();
        WatchedMail watched = new WatchedMail(mail);
        // The newest one's link cannot be made: an exception of a kind nobody expected.
        int[] asked = {0};
        watched.readsAs = handle -> {
            if (asked[0]++ == 0) {
                throw new IllegalStateException("Secret-Qx");
            }
            return null;
        };
        service.answerNextCallsWith(triageSays(false, 95));
        ReadingRound mine = roundOf(rules(), watched);

        assertThat(mine.round()).isEqualTo(1);

        assertThat(watched.read).hasSize(2);
        assertThat(isRead("m-new")).as("the one that failed").isFalse();
        assertThat(isRead("m-old")).as("the next one was read").isTrue();
        // It is put off: the next round does not even fetch it.
        watched.read.clear();
        assertThat(mine.round()).isZero();
        assertThat(watched.read).isEmpty();
    }

    @Test
    void aMessageWhoseLinkCannotBeMadePaysForNothingIsPutOffItsMailboxIsLeftOutAndTheOtherIsRead() throws Exception {
        connectPiotr();
        annaGets("m-anna", "A question", POLISH);
        piotrGets("m-piotr", "A question", POLISH);
        note();
        WatchedMail watched = new WatchedMail(mail);
        watched.noLinkFor = piotr();
        service.answerNextCallsWith(triageSays(false, 95));
        ReadingRound mine = roundOf(rules(), watched);

        assertThat(mine.round()).isEqualTo(1);

        assertThat(isRead("m-piotr")).isFalse();
        assertThat(isRead("m-anna")).isTrue();
        assertThat(service.calls()).as("only Anna's message was triaged").hasSize(1);
        // A second round right after asks nothing for that message either: not Gmail, not the service.
        watched.read.clear();
        service.reset();
        assertThat(mine.round()).isZero();
        assertThat(watched.read).isEmpty();
        assertThat(service.calls()).isEmpty();
    }

    @Test
    void aMessageOfAWeddingThatIsNotThereAsksNothingOfTheService() throws Exception {
        annaGets("m-1", "A question", POLISH);
        note();
        io.github.trufelek124.weddingplanner.wedding.Weddings none = org.mockito.Mockito.mock(io.github.trufelek124.weddingplanner.wedding.Weddings.class);
        ReadingRound mine = new ReadingRound(steps, rules(), mail, suggestions, none, maker, usage, clock);

        assertThat(mine.round()).isZero();

        assertThat(isRead("m-1")).isFalse();
        assertThat(service.calls()).isEmpty();
    }

    @Test
    void aMailboxThatIsNotConnectedAnyMoreIsLeftAsItIsAndTheOtherIsRead() throws Exception {
        connectPiotr();
        annaGets("m-anna", "A question", POLISH);
        piotrGets("m-piotr", "A question", POLISH);
        note();
        WatchedMail watched = new WatchedMail(mail);
        watched.readsAs = handle -> handle.memberId().equals(piotr()) ? new Unavailable(Why.NOT_CONNECTED) : null;
        service.answerNextCallsWith(triageSays(false, 95));

        assertThat(roundOf(rules(), watched).round()).isEqualTo(1);

        assertThat(isRead("m-piotr")).isFalse();
        assertThat(isRead("m-anna")).isTrue();
    }

    private io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox box() {
        return google.mailbox(ANNA_MAILBOX);
    }
}
