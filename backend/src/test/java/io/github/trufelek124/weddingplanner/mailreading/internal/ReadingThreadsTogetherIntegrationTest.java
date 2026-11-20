package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestPdfs;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The waiting messages of one thread are read as one (S04 section 2, D-012): one triage, one extraction, the earlier ones done
 * with no proposals of their own, what the thread proposed under its newest message. Made-up mail in Polish, Ukrainian and
 * English, against the stand-ins for Google and for the AI service.
 */
class ReadingThreadsTogetherIntegrationTest extends RoundTestBase {

    static final String THREAD = "thread-t";
    static final String READ_NOW = "Read before: no - it is read now, with the message";
    static final String READ_BEFORE = "Read before: yes";
    static final String LAST_PASSAGE = "Prosimy o wybór menu do końca tygodnia.";
    static final String TEXT_1 = "Dzień dobry, wstępnie rezerwujemy salę na 19 czerwca 2027. ONE-Qz";
    static final String TEXT_2 = "Dziękujemy. Zaliczka wyniesie 2000 PLN do 1 grudnia 2026. TWO-Qz";
    static final String TEXT_3 = "Poprawka: zaliczka wyniesie 2500 PLN do 5 grudnia 2026. THREE-Qz";
    static final String TEXT_4 = "Wszystko potwierdzone. " + LAST_PASSAGE + " Pozdrawiamy. FOUR-Qz";
    static final String FACT_LINE = "the conversation is read as a whole, as it stands after its last message";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    /** Four messages of one thread in Anna's mailbox, t-1 the oldest, each with a Message-ID of its own. */
    private void aThreadOfFour() {
        thread("t-1", 4, TEXT_1);
        thread("t-2", 3, TEXT_2);
        thread("t-3", 2, TEXT_3);
        thread("t-4", 1, TEXT_4);
    }

    private void thread(String id, long daysAgo, String text) {
        gets(ANNA_MAILBOX, id, THREAD, daysAgo, JAN, "Rezerwacja sali", text, "<" + id + "@palace-on-the-hill.example>");
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static String withoutMarker(String turn) {
        return turn.replaceAll("BOUNDARY-[0-9A-F]{16}", "MARKER");
    }

    private MessageHandle annas(String id) {
        return new MessageHandle(weddingId(), anna(), id, THREAD);
    }

    private ReadingRound roundOfOne() {
        Rules r = rules();
        return roundOf(new Rules(r.on(), r.interval(), r.automatic(), 1, r.triageSure(), r.documents(), r.documentsSure(), r.monthlyLimitUsd(),
                r.dailyMessages(), r.dailySuggestionsPerMailbox(), r.triagePrice(), r.extractionPrice()));
    }

    private String documentsOf(String id) {
        return jdbc.queryForObject("SELECT documents FROM mail_message WHERE message_id = ?", String.class, id);
    }

    private List<String> sourceKeys() {
        return jdbc.queryForList("SELECT source_key FROM suggestion ORDER BY source_key", String.class);
    }

    // ---------------------------------------------------------------- the thread as one

    @Test
    void fourMessagesOfOneThreadAreReadWithOneTriageAndOneExtractionAndTheThreadProposesUnderTheNewest() {
        aThreadOfFour();
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Choose-the-menu-Qz", LAST_PASSAGE)));

        assertThat(round.round()).as("every message got its verdict").isEqualTo(4);

        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        String turn = extractionTurns().getFirst();
        assertThat(count(turn, READ_NOW)).as("the three earlier ones are read now").isEqualTo(3);
        assertThat(count(turn, READ_BEFORE)).isZero();
        assertThat(count(turn, FACT_LINE)).as("the planner says so, once").isEqualTo(1);
        assertThat(turn).contains("At most 8 proposals in all").contains(TEXT_1).contains(TEXT_2).contains(TEXT_3);
        assertThat(turn.indexOf(FACT_LINE)).as("said among the planner's own facts, before any marked part")
                .isLessThan(turn.indexOf("begins: already proposed"));
        // The earlier messages go oldest first.
        assertThat(turn.indexOf("ONE-Qz")).isLessThan(turn.indexOf("TWO-Qz"));
        assertThat(turn.indexOf("TWO-Qz")).isLessThan(turn.indexOf("THREE-Qz"));
        // What the thread proposed stands under the newest message.
        var keys = mail.keysOf(annas("t-4"));
        assertThat(sourceKeys()).containsExactly(keys.thread() + "/" + keys.message() + "/1");
        for (String id : List.of("t-1", "t-2", "t-3")) {
            assertThat(verdictOf(id)).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
            assertThat(documentsOf(id)).isEqualTo("DONE");
        }
        assertThat(verdictOf("t-4")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
        // Nothing waits any more: the next rounds ask nothing.
        service.reset();
        assertThat(round.round()).isZero();
        TestClockAdvance.minutes(10);
        assertThat(round.round()).isZero();
        assertThat(service.calls()).isEmpty();
        assertThat(suggestionRows()).isEqualTo(1);
    }

    @Test
    void aMessageThatTriageCallsASureNoIsDoneAloneAndTheNextOfTheThreadIsReadWithWhatIsBeforeIt() {
        aThreadOfFour();
        note();
        service.answerNextCallsWith(triageSays(false, 95), triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(4);

        assertThat(verdictOf("t-4")).containsEntry("verdict", "NOT_WEDDING");
        assertThat(triageCalls()).as("the newest, then the one before it").isEqualTo(2);
        assertThat(extractionCalls()).isEqualTo(1);
        String turn = extractionTurns().getFirst();
        assertThat(turn).as("the 'no' message is not sent with the thread").doesNotContain("FOUR-Qz").contains("THREE-Qz");
        assertThat(count(turn, READ_NOW)).isEqualTo(2);
        for (String id : List.of("t-1", "t-2")) {
            assertThat(verdictOf(id)).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
        }
        assertThat(verdictOf("t-3")).containsEntry("verdict", "WEDDING");
    }

    @Test
    void aNoThatIsNotSureEnoughIsReadOnWithTheThreadAsAYes() {
        aThreadOfFour();
        note();
        service.answerNextCallsWith(triageSays(false, 30), extractionSays());

        assertThat(round.round()).isEqualTo(4);

        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        assertThat(count(extractionTurns().getFirst(), READ_NOW)).isEqualTo(3);
    }

    @Test
    void anEarlierMessageThatWasReadBeforeSaysSoAndItsRowIsUntouchedAndTheOwnersReplyIsBackgroundAndNeverMarked() {
        aThreadOfFour();
        // The owner's own reply is labelled as sent, so that the sync never notes it.
        google.mailbox(ANNA_MAILBOX).writeWithMessageId("t-own", THREAD, daysAgo(2).plusSeconds(3_600), "Anna <anna.mailbox@planner.example>",
                "Re: Rezerwacja sali", Mime.plain("OWN-SENT-Qz"), "<own@planner.example>", "SENT");
        note();
        assertThat(mail.mark(annas("t-2"), new AboutTheWedding(1))).isTrue();
        Map<String, Object> before = jdbc.queryForMap("SELECT verdict, proposals, read_at, documents FROM mail_message WHERE message_id = 't-2'");
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        round.round();

        String turn = extractionTurns().getFirst();
        assertThat(turn).contains("OWN-SENT-Qz");
        assertThat(count(turn, READ_BEFORE)).as("the one that was read, and the owner's own reply").isEqualTo(2);
        assertThat(count(turn, READ_NOW)).as("t-1 and t-3").isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT verdict, proposals, read_at, documents FROM mail_message WHERE message_id = 't-2'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE message_id = 't-own'", Integer.class)).isZero();
        assertThat(verdictOf("t-1")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
        assertThat(verdictOf("t-3")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
    }

    @Test
    void aMessageThatArrivesAloneGoesAsBeforeAndItsRequestSaysReadBeforeForTheEarlierOnes() {
        thread("t-1", 3, TEXT_1);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        assertThat(round.round()).isEqualTo(1);
        String first = extractionTurns().getFirst();
        assertThat(first).doesNotContain("Read before").doesNotContain(FACT_LINE).contains("At most 5 proposals in all");

        thread("t-2", 1, TEXT_2);
        TestClockAdvance.minutes(10);
        note();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(1);

        String second = extractionTurns().getFirst();
        assertThat(count(second, READ_BEFORE)).isEqualTo(1);
        assertThat(second).doesNotContain(READ_NOW).doesNotContain(FACT_LINE).contains("At most 5 proposals in all");
    }

    // ---------------------------------------------------------------- what does not fit

    @Test
    void aThreadTooLongToFitLeavesTheOldestWaitingAndTheRequestSaysHowManyWereLeftOut() {
        thread("t-1", 4, "OLDEST-Qz " + "o".repeat(25_000));
        thread("t-2", 3, "MIDDLE-Qz " + "m".repeat(25_000));
        thread("t-3", 2, "NEXT-Qz " + "n".repeat(25_000));
        thread("t-4", 1, TEXT_4);
        note();
        ReadingRound mine = roundOfOne();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(mine.round()).as("the newest, and the two that were sent").isEqualTo(3);

        String turn = extractionTurns().getFirst();
        assertThat(turn).contains("The thread was cut: the 1 oldest earlier message(s) were left out").doesNotContain("OLDEST-Qz")
                .contains("MIDDLE-Qz").contains("NEXT-Qz");
        assertThat(count(turn, READ_NOW)).isEqualTo(2);
        assertThat(isRead("t-1")).as("it was not sent: it waits").isFalse();
        assertThat(isRead("t-2")).isTrue();
        assertThat(isRead("t-3")).isTrue();
        assertThat(isRead("t-4")).isTrue();

        // Later it is read the same way, with what is before it (nothing).
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        assertThat(mine.round()).isEqualTo(1);
        assertThat(verdictOf("t-1")).containsEntry("verdict", "WEDDING");
        assertThat(extractionTurns().getFirst()).contains("OLDEST-Qz").doesNotContain(READ_NOW).doesNotContain("Earlier message");
    }

    @Test
    void whatWasLeftOutIsReadLaterInTheSameRoundWhenTheBatchReachesIt() {
        thread("t-1", 4, "OLDEST-Qz " + "o".repeat(25_000));
        thread("t-2", 3, "MIDDLE-Qz " + "m".repeat(25_000));
        thread("t-3", 2, "NEXT-Qz " + "n".repeat(25_000));
        thread("t-4", 1, TEXT_4);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(), triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(4);

        assertThat(triageCalls()).isEqualTo(2);
        assertThat(extractionCalls()).isEqualTo(2);
        assertThat(extractionTurns().get(1)).contains("OLDEST-Qz").doesNotContain("Earlier message");
    }

    // ---------------------------------------------------------------- copies

    @Test
    void aWaitingEarlierMessageThatIsACopyOfALetterReadInTheOtherMailboxIsBackgroundAndMarkedAsACopyWithNothingAskedForIt() throws Exception {
        connectPiotr();
        aThreadOfFour();
        gets(PIOTR_MAILBOX, "p-2", "thread-p", 3, JAN, "Rezerwacja sali", TEXT_2, "<t-2@palace-on-the-hill.example>");
        note();
        MessageHandle piotrs = new MessageHandle(weddingId(), piotr(), "p-2", "thread-p");
        String key = ((Letter) mail.read(piotrs)).key();
        assertThat(mail.mark(piotrs, new AboutTheWedding(0), key)).isTrue();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(round.round()).as("t-4, t-1, t-3 and the copy t-2").isEqualTo(4);

        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        String turn = extractionTurns().getFirst();
        assertThat(count(turn, READ_BEFORE)).as("the copy").isEqualTo(1);
        assertThat(count(turn, READ_NOW)).isEqualTo(2);
        assertThat(turn).contains(TEXT_2);
        assertThat(verdictOf("t-2")).containsEntry("verdict", "COPY");
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = 't-2'", String.class)).isEqualTo(key);
        assertThat(verdictOf("t-1")).containsEntry("verdict", "WEDDING");
        assertThat(verdictOf("t-3")).containsEntry("verdict", "WEDDING");
    }

    @Test
    void aCopyThatDoesNotFitIntoTheRequestIsStillMarkedAsACopy() throws Exception {
        connectPiotr();
        thread("t-1", 4, "OLDEST-Qz " + "o".repeat(25_000));
        thread("t-2", 3, "MIDDLE-Qz " + "m".repeat(25_000));
        thread("t-3", 2, "NEXT-Qz " + "n".repeat(25_000));
        thread("t-4", 1, TEXT_4);
        gets(PIOTR_MAILBOX, "p-1", "thread-p", 4, JAN, "Rezerwacja sali", "OLDEST-Qz " + "o".repeat(25_000), "<t-1@palace-on-the-hill.example>");
        note();
        MessageHandle piotrs = new MessageHandle(weddingId(), piotr(), "p-1", "thread-p");
        assertThat(mail.mark(piotrs, new AboutTheWedding(0), ((Letter) mail.read(piotrs)).key())).isTrue();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        round.round();

        assertThat(extractionTurns().getFirst()).contains("The thread was cut").doesNotContain("OLDEST-Qz");
        assertThat(verdictOf("t-1")).as("sent or not, a copy is a copy").containsEntry("verdict", "COPY");
    }

    @Test
    void theSameConversationInBothMailboxesIsReadAsAThreadInTheFirstAndEveryMessageOfTheSecondIsACopyWithNothingAskedForThem() throws Exception {
        connectPiotr();
        String[] texts = {TEXT_1, TEXT_2, TEXT_3, TEXT_4};
        for (int n = 1; n <= 4; n++) {
            // The same letters, every one to both: each has the identifier its sender gave it.
            gets(ANNA_MAILBOX, "a-" + n, "thread-a", 5 - n, JAN, "Rezerwacja sali", texts[n - 1], "<letter-" + n + "@palace-on-the-hill.example>");
            gets(PIOTR_MAILBOX, "p-" + n, "thread-p", 5 - n, JAN, "Rezerwacja sali", texts[n - 1], "<letter-" + n + "@palace-on-the-hill.example>");
        }
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Choose-the-menu-Qz", LAST_PASSAGE)));

        assertThat(round.round()).isEqualTo(8);

        assertThat(service.calls()).as("one triage and one extraction in all").hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict = 'WEDDING'", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict = 'COPY'", Integer.class)).isEqualTo(4);
        assertThat(suggestionRows()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- documents

    @Test
    void anEarlierMessageWithAPdfEndsWithItsDocumentsStillToBeLookedAtAndTheSecondLookReadsThePdfUnderItsOwnKeysOneWithoutIsDoneAtOnce() {
        WatchedMail watched = new WatchedMail(mail);
        ReadingRound mine = roundOf(rules(), watched);
        thread("t-1", 4, TEXT_1);
        annaGetsOffer("t-2", 3, "Oferta w załączniku. TWO-Qz");
        thread("t-4", 1, TEXT_4);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(),
                // The second look at t-2: triage again, then the extraction with the document.
                triageSays(true, 90), extractionSays(with(with(with(proposal("PAYMENT", "Second-payment-Qz", ReadingDocumentsIntegrationTest.SECOND_LINE),
                        "amount", "5000.00"), "currency", "PLN"), "paymentKind", "INSTALMENT")));

        assertThat(mine.round()).as("three read, and the second look at t-2").isEqualTo(3);

        assertThat(watched.markedHandles).extracting(MessageHandle::messageId).containsExactly("t-1", "t-2", "t-4");
        assertThat(watched.markedStillToLookAt).as("only the one with a PDF").containsExactly(false, true, false);
        assertThat(documentsOf("t-1")).isEqualTo("DONE");
        assertThat(documentsOf("t-2")).as("looked at afterwards, in the same round").isEqualTo("DONE");
        var keys = mail.keysOf(annas("t-2"));
        assertThat(sourceKeys()).containsExactly(keys.thread() + "/" + keys.message() + "/d1");
        assertThat(verdictOf("t-2")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
        assertThat(attachmentsFetched()).as("the PDF of t-2, once").isEqualTo(1);
        assertThat(extractionTurns().get(1)).contains("document 1 of 1 that came with the message").doesNotContain(READ_NOW);
        assertThat(triageCalls()).isEqualTo(2);
    }

    @Test
    void withDocumentsOffAnEarlierMessageWithAPdfIsDoneAtOnce() {
        WatchedMail watched = new WatchedMail(mail);
        ReadingRound mine = roundOf(rulesForDocuments(false, 80), watched);
        annaGetsOffer("t-2", 3, "Oferta w załączniku. TWO-Qz");
        thread("t-4", 1, TEXT_4);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(mine.round()).isEqualTo(2);

        assertThat(watched.markedStillToLookAt).containsExactly(false, false);
        assertThat(documentsOf("t-2")).isEqualTo("DONE");
        assertThat(attachmentsFetched()).isZero();
    }

    private void annaGetsOffer(String id, long daysAgo, String text) {
        String attachment = "att-" + id;
        google.mailbox(ANNA_MAILBOX).attach(attachment, TestPdfs.withPages(ReadingDocumentsIntegrationTest.OFFER));
        google.mailbox(ANNA_MAILBOX).writeWithMessageId(id, THREAD, daysAgo(daysAgo), JAN, "Rezerwacja sali",
                Mime.multipart("mixed", Mime.plain(text), Mime.file("oferta.pdf", "application/pdf", attachment,
                        TestPdfs.withPages(ReadingDocumentsIntegrationTest.OFFER).length)),
                "<" + id + "@palace-on-the-hill.example>", "INBOX");
    }

    // ---------------------------------------------------------------- no answer

    @Test
    void whenTheServiceIsBusyAtExtractionNothingIsMarkedAndTheNextTryAsksTheSameThing() {
        aThreadOfFour();
        note();
        service.answerNextCallsWith(triageSays(true, 90), Reply.error(529, "overloaded_error", "x"), Reply.error(529, "overloaded_error", "x"),
                Reply.error(529, "overloaded_error", "x"));

        assertThat(round.round()).isZero();

        for (String id : List.of("t-1", "t-2", "t-3", "t-4")) {
            assertThat(isRead(id)).as(id).isFalse();
        }
        String asked = withoutMarker(extractionTurns().getFirst());
        TestClockAdvance.minutes(2);
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(4);

        assertThat(withoutMarker(extractionTurns().getFirst())).as("the same thread is asked").isEqualTo(asked);
    }

    @Test
    void whenTheKeyIsNotAcceptedNothingIsMarkedAndWhenTheAnswerCannotBeUsedOnlyTheNewestIsMarkedAsCouldNotBeRead() {
        aThreadOfFour();
        note();
        service.answerEveryCallWith(Reply.error(401, "authentication_error", "invalid x-api-key"));
        assertThat(round.round()).isZero();
        for (String id : List.of("t-1", "t-2", "t-3", "t-4")) {
            assertThat(isRead(id)).as(id).isFalse();
        }

        round.forget();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), Reply.says("Here are some ideas."));
        assertThat(roundOfOne().round()).isEqualTo(1);

        assertThat(verdictOf("t-4")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "UNUSABLE_ANSWER");
        for (String id : List.of("t-1", "t-2", "t-3")) {
            assertThat(isRead(id)).as(id + " waits").isFalse();
        }
    }

    @Test
    void whenTheAnswerHasMoreProposalsThanTheRequestAllowsNoneIsHandedInAndOnlyTheNewestIsMarked() {
        aThreadOfFour();
        note();
        String[] nine = new String[9];
        for (int n = 0; n < 9; n++) {
            nine[n] = proposal("TASK", "Task-" + n + "-Qz", LAST_PASSAGE);
        }
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(nine));

        assertThat(roundOfOne().round()).isEqualTo(1);

        assertThat(verdictOf("t-4")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "TOO_MANY_PROPOSALS");
        assertThat(suggestionRows()).isZero();
        assertThat(isRead("t-3")).isFalse();
    }

    @Test
    void eightProposalsAreTakenForAThreadReadLikeThis() {
        aThreadOfFour();
        note();
        String[] eight = new String[8];
        for (int n = 0; n < 8; n++) {
            eight[n] = proposal("TASK", "Task-" + n + "-Qz", LAST_PASSAGE);
        }
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(eight));

        assertThat(round.round()).isEqualTo(4);

        assertThat(suggestionRows()).isEqualTo(8);
        assertThat(verdictOf("t-4")).containsEntry("proposals", 8);
    }

    @Test
    void fiveProposalsIsTheMostForAThreadWhoseEarlierMessagesWereAllReadBefore() {
        thread("t-1", 3, TEXT_1);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        round.round();
        thread("t-2", 1, TEXT_2);
        TestClockAdvance.minutes(10);
        note();
        service.reset();
        String[] six = new String[6];
        for (int n = 0; n < 6; n++) {
            six[n] = proposal("TASK", "Task-" + n + "-Qz", "Zaliczka wyniesie 2000 PLN");
        }
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(six));

        round.round();

        assertThat(verdictOf("t-2")).containsEntry("unread_reason", "TOO_MANY_PROPOSALS");
    }

    // ---------------------------------------------------------------- the server stops in between

    @Test
    void theNewestHandedInAndTheEarlierOnesMarkedButItsOwnMarkNotMadeIsDoneAgainAndProposesNothingTwice() {
        aThreadOfFour();
        note();
        WatchedMail watched = new WatchedMail(mail);
        watched.markFailsFor = annas("t-4");
        ReadingRound mine = roundOf(rules(), watched);
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Choose-the-menu-Qz", LAST_PASSAGE)));

        assertThat(mine.round()).as("the three earlier ones got their verdicts").isEqualTo(3);

        assertThat(isRead("t-4")).isFalse();
        assertThat(service.calls()).as("the thread was asked once: one triage, one extraction").hasSize(2);
        for (String id : List.of("t-1", "t-2", "t-3")) {
            assertThat(verdictOf(id)).as(id + " was done with the newest").containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
        }
        assertThat(suggestionRows()).isEqualTo(1);
        assertThat(watched.markedHandles).as("the earlier ones were marked, not the newest").extracting(MessageHandle::messageId)
                .containsExactlyInAnyOrder("t-1", "t-2", "t-3");

        // The server starts again: nothing is remembered.
        mine.forget();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Choose-the-menu-Qz", LAST_PASSAGE)));
        ReadingRound again = roundOf(rules());

        assertThat(again.round()).isEqualTo(1);

        assertThat(suggestionRows()).as("nothing was proposed twice").isEqualTo(1);
        assertThat(verdictOf("t-4")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
        assertThat(count(extractionTurns().getFirst(), READ_NOW)).as("the earlier ones were read already").isZero();
        assertThat(count(extractionTurns().getFirst(), READ_BEFORE)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- the second look never reads "now"

    @Test
    void theSecondLookNeverReadsEarlierMessagesNow() {
        annaGetsOffer("t-2", 3, "Oferta w załączniku. TWO-Qz");
        note();
        // t-2 is read on its own first, with documents off, as a message that was read before documents could be.
        service.answerNextCallsWith(triageSays(true, 95), extractionSays());
        assertThat(roundOf(rulesForDocuments(false, 80)).round()).isEqualTo(1);
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 't-2'");
        // A message of the thread that came before it and was never read waits.
        thread("t-1", 4, TEXT_1);
        TestClockAdvance.minutes(10);
        note();
        assertThat(isRead("t-1")).isFalse();
        WatchedMail watched = new WatchedMail(mail);
        watched.nothingWaits = true;
        service.reset();
        round.forget();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays());

        assertThat(roundOf(rules(), watched).round()).as("only a look again").isZero();

        String turn = extractionTurns().getFirst();
        assertThat(turn).contains("document 1 of 1 that came with the message").contains(READ_BEFORE).doesNotContain(READ_NOW).doesNotContain(FACT_LINE);
        assertThat(isRead("t-1")).as("the second look reads documents only: t-1 still waits").isFalse();
        assertThat(documentsOf("t-2")).isEqualTo("DONE");
    }

    @Test
    void theDaysLimitOfMessagesCountsAThreadReadLikeThisOnce() {
        aThreadOfFour();
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        round.round();

        assertThat(usage.triagedOn(java.time.LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)))).isEqualTo(1);
    }
}
