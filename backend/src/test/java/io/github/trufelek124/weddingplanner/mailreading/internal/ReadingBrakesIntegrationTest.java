package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.StandInGoogle;
import io.github.trufelek124.weddingplanner.mail.ReadingReport.State;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The brakes of S04 sections 7 and 8, and the mailboxes that cannot be read: nothing is lost, and the reading goes on by itself. */
class ReadingBrakesIntegrationTest extends RoundTestBase {

    static final String PASSAGE = "Prosimy o wybór menu do końca tygodnia.";
    static final String TEXT = "Dzień dobry, potwierdzamy termin degustacji. " + PASSAGE + " Pozdrawiamy.";
    static final String THREADS = "/gmail/v1/users/me/threads";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private static Reply notAboutTheWedding() {
        return triageSays(false, 95);
    }

    private Rules with(java.util.function.UnaryOperator<Rules> change) {
        return change.apply(rules());
    }

    private static Rules withDailyMessages(Rules r, int daily) {
        return new Rules(r.on(), r.interval(), r.automatic(), r.batch(), r.triageSure(), r.documents(), r.documentsSure(), r.monthlyLimitUsd(), daily, r.dailySuggestionsPerMailbox(), r.triagePrice(), r.extractionPrice());
    }

    private static Rules withSuggestionsPerMailbox(Rules r, int most, int batch) {
        return new Rules(r.on(), r.interval(), r.automatic(), batch, r.triageSure(), r.documents(), r.documentsSure(), r.monthlyLimitUsd(), r.dailyMessages(), most, r.triagePrice(), r.extractionPrice());
    }

    private static Rules withTriagePrice(Rules r, Rules.Price price) {
        return new Rules(r.on(), r.interval(), r.automatic(), r.batch(), r.triageSure(), r.documents(), r.documentsSure(), r.monthlyLimitUsd(), r.dailyMessages(), r.dailySuggestionsPerMailbox(), price, r.extractionPrice());
    }

    // ---------------------------------------------------------------- the month

    @Test
    void whenTheMonthsEstimateHasReachedTheLimitTheReadingPausesUntilNextMonth() {
        annaGets("m-new", "new", TEXT);
        annaGets("m-newest", "newest", TEXT);
        // Every token of the small model costs a dollar: the first call is $160 against a limit of $20.
        ReadingRound mine = roundOf(withTriagePrice(rules(), price("1000000", "1000000")));
        annaGets("m-last", "last", TEXT);
        note();
        service.answerNextCallsWith(notAboutTheWedding(), notAboutTheWedding(), notAboutTheWedding());

        assertThat(mine.round()).as("the first message is read, and before the second the limit is found reached").isEqualTo(1);

        assertThat(triageCalls()).isEqualTo(1);
        assertThat(mine.report().state()).isEqualTo(State.MONTHLY_LIMIT);
        assertThat(mine.report().costThisMonthCents()).isEqualTo(16000L);
        assertThat(mine.report().monthlyLimitCents()).isEqualTo(2000L);
        assertThat(mine.round()).isZero();
        assertThat(triageCalls()).as("a further round asks nothing").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict IS NULL", Integer.class)).isEqualTo(2);

        // Next month it goes on.
        TestClockAdvance.days(40);
        assertThat(mine.report().state()).isEqualTo(State.READING);
        assertThat(mine.report().costThisMonthCents()).isEqualTo(0L);
        assertThat(mine.report().costInAllCents()).isEqualTo(16000L);
        assertThat(mine.round()).as("one more is read, and then the new month's limit is reached again").isEqualTo(1);
        assertThat(triageCalls()).isEqualTo(2);
    }

    @Test
    void theLimitIsReachedWhenTheEstimateEqualsIt() {
        usage.count(java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC), UsageRepository.Step.EXTRACTION, "claude-opus-5-5", 5_000_000, 0);
        // 5,000,000 tokens at $4 a million is exactly $20.

        assertThat(round.report().state()).isEqualTo(State.MONTHLY_LIMIT);
        assertThat(round.report().costThisMonthCents()).isEqualTo(2000L);
    }

    // ---------------------------------------------------------------- the day

    @Test
    void whenTheMessagesOfTheDayAreTriagedTheReadingPausesUntilTomorrow() {
        annaGets("m-1", "one", TEXT);
        annaGets("m-2", "two", TEXT);
        annaGets("m-3", "three", TEXT);
        note();
        ReadingRound mine = roundOf(withDailyMessages(rules(), 2));
        service.answerNextCallsWith(notAboutTheWedding(), notAboutTheWedding(), notAboutTheWedding());

        assertThat(mine.round()).isEqualTo(2);

        assertThat(mine.report().state()).isEqualTo(State.DAILY_LIMIT);
        assertThat(mine.round()).isZero();
        assertThat(triageCalls()).isEqualTo(2);
        TestClockAdvance.days(1);
        assertThat(mine.report().state()).isEqualTo(State.READING);
        assertThat(mine.round()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict IS NULL", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- one mailbox in a day

    private void twoMailboxesAnnasMessagesNewer() throws Exception {
        connectPiotr();
        piotrGets("p-1", "p one", TEXT);
        piotrGets("p-2", "p two", TEXT);
        note();
        annaGets("a-1", "a one", TEXT);
        annaGets("a-2", "a two", TEXT);
        annaGets("a-3", "a three", TEXT);
        // Anna's look is the later one: her messages are the newest.
        TestClockAdvance.minutes(10);
        note();
    }

    private Reply[] onePerMessage(int messages, String prefix) {
        Reply[] replies = new Reply[messages * 2];
        for (int i = 0; i < messages; i++) {
            replies[i * 2] = triageSays(true, 90);
            replies[i * 2 + 1] = extractionSays(proposal("TASK", prefix + i + "-Qz", PASSAGE));
        }
        return replies;
    }

    @Test
    void aMailboxThatHasBroughtItsLimitOfSuggestionsIsLeftOutUntilTomorrowAndTheOtherGoesOn() throws Exception {
        twoMailboxesAnnasMessagesNewer();
        ReadingRound mine = roundOf(withSuggestionsPerMailbox(rules(), 2, 20));
        service.answerNextCallsWith(onePerMessage(4, "first-"));

        // Anna's a-3 and a-2 bring two; a-1 is not read; Piotr's two are.
        assertThat(mine.round()).isEqualTo(4);

        assertThat(isRead("a-1")).as("the third of Anna's waits").isFalse();
        assertThat(isRead("a-2")).isTrue();
        assertThat(isRead("a-3")).isTrue();
        assertThat(isRead("p-1")).isTrue();
        assertThat(isRead("p-2")).isTrue();
        assertThat(suggestionRows()).isEqualTo(4);

        // The same day, a later round: still nothing of Anna's.
        assertThat(mine.round()).isZero();

        // Tomorrow the first goes on.
        TestClockAdvance.days(1);
        service.reset();
        service.answerNextCallsWith(onePerMessage(1, "second-"));
        assertThat(mine.round()).isEqualTo(1);
        assertThat(isRead("a-1")).isTrue();
        assertThat(suggestionRows()).isEqualTo(5);
    }

    @Test
    void aMailboxAtItsLimitDoesNotTakeThePlacesOfTheOthersMessagesInTheBatch() throws Exception {
        twoMailboxesAnnasMessagesNewer();
        // One suggestion a day from a mailbox, two messages in a round: Anna's newest two fill the first round.
        ReadingRound mine = roundOf(withSuggestionsPerMailbox(rules(), 1, 2));
        service.answerNextCallsWith(onePerMessage(1, "one-"));

        assertThat(mine.round()).as("a-3 is read, a-2 finds Anna at her limit").isEqualTo(1);
        assertThat(isRead("a-3")).isTrue();
        assertThat(isRead("a-2")).isFalse();

        service.reset();
        service.answerNextCallsWith(onePerMessage(2, "two-"));
        assertThat(mine.round()).as("the second round asks for the others: Piotr's newest is not kept out by Anna's two").isEqualTo(1);
        assertThat(isRead("p-2")).isTrue();
        assertThat(isRead("p-1")).as("Piotr's limit is one too").isFalse();
        assertThat(isRead("a-2")).isFalse();
        assertThat(isRead("a-1")).isFalse();
    }

    // ---------------------------------------------------------------- the service asks to wait

    @Test
    void theServiceAskingToWaitPausesTheReadingForAGrowingTimeAndNothingIsMarkedOrLost() {
        annaGets("m-1", "one", TEXT);
        note();
        service.answerEveryCallWith(Reply.error(529, "overloaded_error", "Overloaded"));

        assertThat(round.round()).isZero();
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);
        long calls = service.calls().size();
        assertThat(round.round()).as("while it waits, nothing is asked").isZero();
        assertThat(service.calls()).hasSize((int) calls);

        // One minute for the first time ...
        TestClockAdvance.seconds(61);
        assertThat(round.report().state()).isEqualTo(State.READING);
        assertThat(round.round()).isZero();
        assertThat(service.calls().size()).isGreaterThan((int) calls);
        calls = service.calls().size();
        // ... two for the second ...
        TestClockAdvance.seconds(90);
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);
        TestClockAdvance.seconds(31);
        assertThat(round.report().state()).isEqualTo(State.READING);
        assertThat(round.round()).isZero();
        // ... four for the third ...
        TestClockAdvance.seconds(3 * 60 + 50);
        assertThat(round.report().state()).as("not yet four minutes").isEqualTo(State.WAITING_FOR_THE_SERVICE);
        TestClockAdvance.seconds(15);
        assertThat(round.report().state()).isEqualTo(State.READING);

        // One good answer sets it back: the next failure waits a minute only.
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        assertThat(round.round()).isEqualTo(1);
        assertThat(isRead("m-1")).as("nothing was lost").isTrue();
    }

    @Test
    void theWaitIsAtMostAnHourAndOneGoodAnswerStartsItFromAMinuteAgain() {
        annaGets("m-1", "one", TEXT);
        annaGets("m-2", "two", TEXT);
        note();
        service.answerEveryCallWith(Reply.error(529, "overloaded_error", "Overloaded"));
        for (int time = 0; time < 9; time++) {
            round.round();
            TestClockAdvance.minutes(61);
        }
        // Nine failures in a row: 1, 2, 4 ... would be 256 minutes: it is an hour.
        round.round();
        TestClockAdvance.minutes(59);
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);
        TestClockAdvance.minutes(2);
        assertThat(round.report().state()).isEqualTo(State.READING);

        // m-2 is read at last, and the next failure is the first again: a minute.
        service.reset();
        service.answerNextCallsWith(triageSays(false, 95), Reply.error(529, "overloaded_error", "x"), Reply.error(529, "overloaded_error", "x"), Reply.error(529, "overloaded_error", "x"));
        assertThat(round.round()).isEqualTo(1);
        assertThat(round.report().state()).isEqualTo(State.WAITING_FOR_THE_SERVICE);
        TestClockAdvance.seconds(61);
        assertThat(round.report().state()).isEqualTo(State.READING);
    }

    // ---------------------------------------------------------------- the key is not accepted

    @Test
    void whenTheKeyIsNotAcceptedTheReadingStopsAndAskedNothingAgainOfAnybody() throws Exception {
        annaGets("m-1", "one", TEXT);
        annaGets("m-2", "two", TEXT);
        note();
        service.answerEveryCallWith(Reply.error(401, "authentication_error", "invalid x-api-key"));

        assertThat(round.round()).isZero();

        assertThat(round.report().state()).isEqualTo(State.KEY_NOT_ACCEPTED);
        assertThat(mail(wedding.piotr).get("reading")).as("what the e-mail page is told, whoever asks")
                .isInstanceOfSatisfying(java.util.Map.class, block -> assertThat(block).containsEntry("state", "KEY_NOT_ACCEPTED")
                        .containsEntry("waiting", 2).containsEntry("read", 0));
        assertThat(isRead("m-1") || isRead("m-2")).isFalse();
        int aiCalls = service.calls().size();
        int googleCalls = google.calls(THREADS).size();
        assertThat(aiCalls).as("one message was tried, no other").isPositive();
        TestClockAdvance.days(3);
        service.reset();
        service.answerEveryCallWith(triageSays(false, 95));
        assertThat(round.round()).isZero();
        assertThat(round.report().state()).isEqualTo(State.KEY_NOT_ACCEPTED);
        assertThat(service.calls()).as("the service is not asked again").isEmpty();
        assertThat(google.calls(THREADS)).as("Gmail is not asked again").hasSize(googleCalls);
    }

    // ---------------------------------------------------------------- mailboxes that cannot be read

    @Test
    void aMailboxThatHasToBeConnectedAgainIsLeftAloneAndTheOtherIsRead() throws Exception {
        connectPiotr();
        annaGets("a-1", "a", TEXT);
        piotrGets("p-1", "p one", TEXT);
        piotrGets("p-2", "p two", TEXT);
        note();
        google.withdraw(google.refreshTokenOf(PIOTR_MAILBOX));
        // The access token Piotr's last look was given has to be too old to be used again.
        TestClockAdvance.minutes(90);
        service.answerNextCallsWith(notAboutTheWedding());

        assertThat(round.round()).isEqualTo(1);

        assertThat(isRead("a-1")).isTrue();
        assertThat(isRead("p-1") || isRead("p-2")).isFalse();
        assertThat(google.calls(THREADS)).as("Google was not asked for Piotr's thread").hasSize(1);
        assertThat(round.round()).as("later rounds read nothing of it either").isZero();
    }

    @Test
    void aMailboxGoogleRefusesForNowIsLeftOutOfTheRoundWithoutAskingForEachOfItsMessages() throws Exception {
        connectPiotr();
        annaGets("a-1", "a", TEXT);
        piotrGets("p-1", "p one", TEXT);
        piotrGets("p-2", "p two", TEXT);
        piotrGets("p-3", "p three", TEXT);
        note();
        google.answerEveryGmailCallOfWith(PIOTR_MAILBOX, StandInGoogle.Reply.json(429, "{\"error\":{\"code\":429}}"));
        service.answerNextCallsWith(notAboutTheWedding());

        assertThat(round.round()).isEqualTo(1);

        assertThat(isRead("a-1")).isTrue();
        assertThat(google.calls(THREADS)).as("Anna's thread, and one of Piotr's, which was refused").hasSize(2);
        google.stopAnsweringGmailCallsOf(PIOTR_MAILBOX);
    }

    // ---------------------------------------------------------------- a message that cannot be had for now

    @Test
    void aMessageThatIsInterruptedIsPutOffForGrowingTimesAndTheNextOneIsRead() throws Exception {
        connectPiotr();
        annaGets("a-1", "a", TEXT);
        piotrGets("p-1", "p", TEXT);
        note();
        google.answerEveryGmailCallOfWith(PIOTR_MAILBOX, StandInGoogle.Reply.dropped());
        service.answerNextCallsWith(notAboutTheWedding());

        assertThat(round.round()).as("Piotr's cut off, Anna's read").isEqualTo(1);

        assertThat(isRead("a-1")).isTrue();
        assertThat(isRead("p-1")).isFalse();
        google.stopAnsweringGmailCallsOf(PIOTR_MAILBOX);
        int asked = google.calls(THREADS).size();
        assertThat(round.round()).isZero();
        assertThat(google.calls(THREADS)).as("put off: not asked for again at once").hasSize(asked);
        TestClockAdvance.minutes(4);
        round.round();
        assertThat(google.calls(THREADS)).as("still put off after four minutes").hasSize(asked);

        // After five minutes it is asked for again; it is cut off again, and now it is put off for ten.
        google.answerEveryGmailCallOfWith(PIOTR_MAILBOX, StandInGoogle.Reply.dropped());
        TestClockAdvance.minutes(2);
        round.round();
        int afterTheSecond = google.calls(THREADS).size();
        assertThat(afterTheSecond).isGreaterThan(asked);
        google.stopAnsweringGmailCallsOf(PIOTR_MAILBOX);
        TestClockAdvance.minutes(9);
        round.round();
        assertThat(google.calls(THREADS)).as("ten minutes the second time").hasSize(afterTheSecond);
        service.reset();
        service.answerNextCallsWith(notAboutTheWedding());
        TestClockAdvance.minutes(2);
        assertThat(round.round()).isEqualTo(1);
        assertThat(isRead("p-1")).isTrue();
    }

    @Test
    void messagesThatArePutOffDoNotHoldTheOthersUp() throws Exception {
        connectPiotr();
        annaGets("a-1", "a one", TEXT);
        annaGets("a-2", "a two", TEXT);
        piotrGets("p-1", "p one", TEXT);
        piotrGets("p-2", "p two", TEXT);
        note();
        // Piotr's two are the newest or not, either way: with room for two in a round, both of his would fill it.
        TestClockAdvance.minutes(10);
        google.answerEveryGmailCallOfWith(PIOTR_MAILBOX, StandInGoogle.Reply.dropped());
        ReadingRound mine = roundOf(withSuggestionsPerMailbox(rules(), 50, 2));
        service.answerNextCallsWith(notAboutTheWedding(), notAboutTheWedding(), notAboutTheWedding(), notAboutTheWedding());

        mine.round();
        mine.round();
        mine.round();

        assertThat(isRead("a-1") && isRead("a-2")).as("Anna's are read although Piotr's came first").isTrue();
        assertThat(isRead("p-1") || isRead("p-2")).isFalse();
        google.stopAnsweringGmailCallsOf(PIOTR_MAILBOX);
    }

    @Test
    void theNotesOfMessagesPutOffAreAtMostAThousandAndTheOldestGoFirst() {
        ReadingRound mine = roundOf(rules());
        for (int i = 0; i < 1_100; i++) {
            mine.putOff(new io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle(weddingId(), anna(), "id-" + i, "thread-" + i));
        }

        assertThat(mine.putOffNotes()).isEqualTo(1_000);
    }
}
