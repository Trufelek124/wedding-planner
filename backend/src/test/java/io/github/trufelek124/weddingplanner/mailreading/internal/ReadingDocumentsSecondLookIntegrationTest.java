package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposals;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestPdfs;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Messages that were read before their documents could be (S04 section 3, "Messages that were read before their documents
 * could be"): each is read once more for its documents only, by the round, after the messages that wait and under the same
 * brakes, a few per round. All made-up, against the stand-ins.
 */
class ReadingDocumentsSecondLookIntegrationTest extends RoundTestBase {

    static final String MAIL = ReadingDocumentsIntegrationTest.MAIL;
    static final String OFFER = ReadingDocumentsIntegrationTest.OFFER;
    static final String THREADS = "/gmail/v1/users/me/threads";
    static final String MAIL_PASSAGE = "Zaliczkę prosimy wpłacić do 1 grudnia 2026.";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private static String payment(String title, String passage, String amount, String day, String kind) {
        return with(with(with(with(proposal("PAYMENT", title, passage), "amount", amount), "currency", "PLN"), "dueDay", day), "paymentKind", kind);
    }

    /**
     * A message as one that was read before documents could be: it was about the wedding, its own proposal (the deposit the mail
     * names) was handed in, and its documents are still to be looked at - which is what the migration leaves.
     */
    private void anOldMessage(String id, Attached... files) throws Exception {
        annaGetsWithFiles(id, "Rezerwacja sali " + id, MAIL, files);
        note();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 95),
                extractionSays(payment("Wpłacić zaliczkę", MAIL_PASSAGE, "2500.00", "2026-12-01", "DEPOSIT")));
        assertThat(roundOf(rulesForDocuments(false, 80)).round()).isEqualTo(1);
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = ?", id);
        service.reset();
        round.forget();
        assertThat(attachmentsFetched()).as("documents were switched off for the first reading").isZero();
    }

    private String documentsOf(String id) {
        return jdbc.queryForObject("SELECT documents FROM mail_message WHERE message_id = ?", String.class, id);
    }

    private int threadCalls() {
        return google.count(THREADS);
    }

    private List<String> sourceKeys() {
        return jdbc.queryForList("SELECT source_key FROM suggestion ORDER BY created_at, source_key", String.class);
    }

    // ---------------------------------------------------------------- what comes of it

    @Test
    void anOldMessageAboutTheWeddingWithAPdfGetsFurtherSuggestionsAndNoneTwiceAndItsCountGoesUp() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        assertThat(suggestionRows()).isEqualTo(1);
        service.answerNextCallsWith(triageSays(true, 90), Reply.says(proposals(
                payment("Wpłacić drugą ratę", ReadingDocumentsIntegrationTest.SECOND_LINE, "5000.00", "2027-01-15", "INSTALMENT"),
                payment("Wpłacić płatność końcową", ReadingDocumentsIntegrationTest.FINAL_LINE, "7500.00", "2027-06-01", "FINAL"))));

        assertThat(round.round()).as("nobody waits to be read: the round only looks again").isZero();

        assertThat(suggestionRows()).isEqualTo(3);
        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        assertThat(attachmentsFetched()).isEqualTo(1);
        String turn = extractionTurns().getFirst();
        assertThat(turn).contains("document 1 of 1 that came with the message")
                .as("its own proposal is told to the model: it is what must not come twice").contains("PAYMENT | Wpłacić zaliczkę | due 2026-12-01 | 2500.00 PLN");
        List<String> keys = sourceKeys();
        assertThat(keys).hasSize(3).doesNotHaveDuplicates();
        assertThat(keys.stream().filter(key -> key.endsWith("/1"))).hasSize(1);
        assertThat(keys.stream().filter(key -> key.endsWith("/d1") || key.endsWith("/d2"))).hasSize(2);
        assertThat(keys.stream().filter(key -> key.contains("/d")).map(key -> key.substring(0, key.lastIndexOf("/d"))).distinct())
                .as("the same message and thread").containsExactly(keys.stream().filter(key -> key.endsWith("/1")).findFirst().orElseThrow()
                        .substring(0, keys.stream().filter(key -> key.endsWith("/1")).findFirst().orElseThrow().length() - 2));
        assertThat(verdictOf("m-old")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 3);
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(jdbc.queryForList("SELECT state FROM suggestion", String.class)).containsOnly("WAITING");
    }

    @Test
    void whatAMessageProposedIsToldInFullEvenWhenThereAreMoreThanTheThreadsUsualShare() throws Exception {
        annaGetsWithFiles("m-old", "Rezerwacja", MAIL, Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        note();
        service.reset();
        List<String> own = new java.util.ArrayList<>();
        for (int n = 1; n <= 5; n++) {
            own.add(payment("Płatność własna " + n, MAIL_PASSAGE, "10.00", "2026-12-0" + n, "INSTALMENT"));
        }
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(own.toArray(String[]::new))));
        roundOf(rulesForDocuments(false, 80)).round();
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 'm-old'");
        service.reset();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays());

        round.round();

        String turn = extractionTurns().getFirst();
        for (int n = 1; n <= 5; n++) {
            assertThat(turn).contains("Płatność własna " + n);
        }
    }

    @Test
    void aMessageWithoutAPdfCostsOneLookAtGmailAndNoCallToTheAiService() throws Exception {
        anOldMessage("m-old", Attached.other("a.docx", "application/msword", "x".getBytes()));
        int before = threadCalls();

        round.round();

        assertThat(threadCalls()).as("one look at Gmail").isEqualTo(before + 1);
        assertThat(service.calls()).as("nothing asked of the AI service").isEmpty();
        assertThat(attachmentsFetched()).isZero();
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(verdictOf("m-old")).containsEntry("proposals", 1);
    }

    @Test
    void aMessageWhoseOnlyPdfsAreTooLargeCostsNoCallToTheAiServiceEither() throws Exception {
        anOldMessage("m-old", Attached.pdf("big.pdf", TestPdfs.withPages("Words"), 10L * 1024 * 1024 + 1));

        round.round();

        assertThat(service.calls()).isEmpty();
        assertThat(attachmentsFetched()).isZero();
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
    }

    @Test
    void whereTriageIsNowLessSureThanTheShareOrSaysNoNothingIsReadAndTheLookIsDoneWith() throws Exception {
        anOldMessage("m-unsure", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        anOldMessage("m-no", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        service.answerNextCallsWith(triageSays(true, 79), triageSays(false, 99));

        round.round();

        assertThat(triageCalls()).isEqualTo(2);
        assertThat(extractionCalls()).isZero();
        assertThat(attachmentsFetched()).isZero();
        assertThat(documentsOf("m-unsure")).isEqualTo("DONE");
        assertThat(documentsOf("m-no")).isEqualTo("DONE");
        assertThat(suggestionRows()).as("only the two deposits of the first readings").isEqualTo(2);
    }

    @Test
    void eachMessageIsLookedAtOnce() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        round.round();
        int threads = threadCalls();
        int calls = service.calls().size();

        round.round();
        round.round();

        assertThat(threadCalls()).isEqualTo(threads);
        assertThat(service.calls()).hasSize(calls);
        assertThat(attachmentsFetched()).isEqualTo(1);
    }

    @Test
    void aMessageWhoseDocumentsWereBegunAndTheServerStoppedIsNotLookedAtAgain() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        jdbc.update("UPDATE mail_message SET documents = 'BEGUN' WHERE message_id = 'm-old'");
        int threads = threadCalls();

        round.round();

        assertThat(threadCalls()).isEqualTo(threads);
        assertThat(service.calls()).isEmpty();
        assertThat(documentsOf("m-old")).isEqualTo("BEGUN");
    }

    @Test
    void messagesThatWereNotAboutTheWeddingOrWereCopiesAreNeverLookedAt() throws Exception {
        annaGetsWithFiles("m-news", "Newsletter", "Nasz katalog.", Attached.pdf("katalog.pdf", TestPdfs.withPages("Katalog")));
        note();
        service.answerNextCallsWith(triageSays(false, 99));
        round.round();
        assertThat(documentsOf("m-news")).as("done with when it was read").isEqualTo("DONE");
        int threads = threadCalls();
        service.reset();

        round.round();

        assertThat(threadCalls()).isEqualTo(threads);
        assertThat(service.calls()).isEmpty();
    }

    @Test
    void whereDocumentsAreSwitchedOffNoMessageIsLookedAtAgainAndTheyWaitForTheDayItIsSwitchedOn() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));

        int threads = threadCalls();

        roundOf(rulesForDocuments(false, 80)).round();

        assertThat(threadCalls()).isEqualTo(threads);
        assertThat(service.calls()).isEmpty();
        assertThat(documentsOf("m-old")).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- the same brakes, and a few per round

    @Test
    void aFewAreLookedAtInARoundAndTheOthersInTheNext() throws Exception {
        for (int n = 1; n <= 5; n++) {
            anOldMessage("m-" + n, Attached.other("a.docx", "application/msword", "x".getBytes()));
        }

        round.round();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE documents = 'PENDING'", Integer.class)).isEqualTo(2);
        round.round();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE documents = 'PENDING'", Integer.class)).isZero();
    }

    @Test
    void theNewestOfTheOldMessagesAreLookedAtFirst() throws Exception {
        for (int n = 1; n <= 4; n++) {
            anOldMessage("m-" + n, Attached.other("a.docx", "application/msword", "x".getBytes()));
            // Each one is noted by a later look than the one before: m-4 is the newest.
            TestClockAdvance.minutes(10);
        }

        round.round();

        assertThat(jdbc.queryForList("SELECT message_id FROM mail_message WHERE documents = 'PENDING'", String.class)).containsExactly("m-1");
    }

    @Test
    void theDaysLimitOfMessagesHoldsForTheSecondLooksToo() throws Exception {
        anOldMessage("m-1", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        anOldMessage("m-2", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        Rules one = new Rules(true, rules().interval(), false, 20, 60, true, 80, rules().monthlyLimitUsd(), 1, 50, rules().triagePrice(), rules().extractionPrice());
        // What the first readings used of the day is not what this is about.
        jdbc.update("DELETE FROM reading_usage");
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(), triageSays(true, 90), extractionSays());

        roundOf(one).round();

        assertThat(triageCalls()).as("the second would be the second triage of the day").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE documents = 'PENDING'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theMonthsLimitAndAMailboxsDayLimitHoldToo() throws Exception {
        anOldMessage("m-1", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        Rules dear = new Rules(true, rules().interval(), false, 20, 60, true, 80, new BigDecimal("0"), 2000, 50, rules().triagePrice(), rules().extractionPrice());
        Rules fullMailbox = new Rules(true, rules().interval(), false, 20, 60, true, 80, rules().monthlyLimitUsd(), 2000, 0, rules().triagePrice(), rules().extractionPrice());

        roundOf(dear).round();
        roundOf(fullMailbox).round();

        assertThat(service.calls()).isEmpty();
        assertThat(documentsOf("m-1")).isEqualTo("PENDING");
    }

    @Test
    void aMailboxGoogleRefusesForNowIsLeftAloneAndTheMessageIsLookedAtLater() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        google.answerNextCallsToWith(THREADS, StandInGoogle.Reply.json(429, "{}"));

        round.round();

        assertThat(documentsOf("m-old")).isEqualTo("PENDING");
        assertThat(service.calls()).isEmpty();

        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        round.forget();
        round.round();
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(attachmentsFetched()).isEqualTo(1);
    }

    @Test
    void theServiceBeingBusyEndsTheRoundBeforeTheDocumentsAreTouched() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        Reply busy = Reply.error(529, "overloaded_error", "Overloaded");
        service.answerNextCallsWith(busy, busy, busy);

        round.round();

        assertThat(attachmentsFetched()).isZero();
        assertThat(documentsOf("m-old")).as("still to look at: nothing was begun").isEqualTo("PENDING");
    }

    @Test
    void anExtractionThatCannotBeUsedIsNotAskedAgainAndTheMessageIsDoneWith() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        service.answerNextCallsWith(triageSays(true, 90), Reply.says("not the shape"));

        round.round();

        assertThat(extractionCalls()).as("without documents there would be nothing new to find").isEqualTo(1);
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(verdictOf("m-old")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
    }

    @Test
    void aPasswordProtectedPdfOfAnOldMessageDoesNotStopTheRound() throws Exception {
        anOldMessage("m-1", Attached.pdf("zablokowany.pdf", TestPdfs.locked("Secret-Qx", "LOCKED-WORDS")));
        anOldMessage("m-2", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        // The newer one, m-2, is looked at first.
        service.answerNextCallsWith(triageSays(true, 90),
                Reply.says(proposals(payment("Wpłacić drugą ratę", ReadingDocumentsIntegrationTest.SECOND_LINE, "5000.00", "2027-01-15", "INSTALMENT"))),
                triageSays(true, 90), extractionSays());

        round.round();

        assertThat(documentsOf("m-1")).isEqualTo("DONE");
        assertThat(documentsOf("m-2")).isEqualTo("DONE");
        assertThat(verdictOf("m-2")).containsEntry("proposals", 2);
    }

    // ---------------------------------------------------------------- a passing failure costs no message its documents

    @Test
    void aBusyServiceOrARefusedKeyAtExtractionLeavesTheMessageStillToBeLookedAt() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        Reply busy = Reply.error(529, "overloaded_error", "Overloaded");
        service.answerNextCallsWith(triageSays(true, 90), busy, busy, busy);

        round.round();

        assertThat(attachmentsFetched()).isEqualTo(1);
        assertThat(documentsOf("m-old")).as("the documents were read, the service was busy: still to be looked at").isEqualTo("PENDING");
        service.reset();
        round.forget();
        service.answerNextCallsWith(triageSays(true, 90), Reply.error(401, "authentication_error", "invalid x-api-key"));
        round.round();
        assertThat(documentsOf("m-old")).as("a key that is not accepted: the same").isEqualTo("PENDING");

        service.reset();
        round.forget();
        service.answerNextCallsWith(triageSays(true, 90), Reply.says(proposals(
                payment("Wpłacić drugą ratę", ReadingDocumentsIntegrationTest.SECOND_LINE, "5000.00", "2027-01-15", "INSTALMENT"))));
        round.round();

        assertThat(attachmentsFetched()).as("read again each time").isEqualTo(3);
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(verdictOf("m-old")).containsEntry("proposals", 2);
    }

    @Test
    void aMailboxGoogleRefusesWhenAskedForTheDocumentLeavesTheMessageStillToBeLookedAtNotDoneWithNothing() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        google.answerNextCallsToWith(ATTACHMENTS, StandInGoogle.Reply.json(429, "{}"));
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        round.round();

        assertThat(extractionCalls()).isZero();
        assertThat(documentsOf("m-old")).isEqualTo("PENDING");
        assertThat(verdictOf("m-old")).containsEntry("proposals", 1);
        service.reset();
        round.forget();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());
        round.round();
        assertThat(documentsOf("m-old")).isEqualTo("DONE");
        assertThat(attachmentsFetched()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- a copy of the letter

    private void writeWithPdf(String mailbox, String id, String messageId, byte[] pdf) {
        google.mailbox(mailbox).attach("att-" + id, pdf);
        google.mailbox(mailbox).writeWithMessageId(id, "thread-" + id, daysAgo(2), JAN, "Rezerwacja sali", StandInGoogle.Mime.multipart("mixed",
                StandInGoogle.Mime.plain(MAIL), StandInGoogle.Mime.file("oferta.pdf", "application/pdf", "att-" + id, pdf.length)), messageId, "INBOX");
    }

    /** Everything noted and waiting is marked as a message that was read before documents (and keys) were: about the wedding, no key. */
    private void markAllAsOld() {
        for (MailReading.MessageHandle handle : mail.waiting(100)) {
            mail.mark(handle, new MailReading.AboutTheWedding(1), "");
        }
        jdbc.update("UPDATE mail_message SET documents = 'PENDING'");
        assertThat(jdbc.queryForObject("SELECT count(letter_key) FROM mail_message", Integer.class)).isZero();
        service.reset();
        round.forget();
    }

    private void theOwnersCopiesOfOneLetter(boolean annaNoted) throws Exception {
        connectPiotr();
        byte[] pdf = TestPdfs.withPages(OFFER);
        String first = annaNoted ? ANNA_MAILBOX : PIOTR_MAILBOX;
        String second = annaNoted ? PIOTR_MAILBOX : ANNA_MAILBOX;
        writeWithPdf(first, "copy-1", "<one-letter@vendor.example>", pdf);
        note();
        TestClockAdvance.minutes(10);
        writeWithPdf(second, "copy-2", "<one-letter@vendor.example>", pdf);
        note();
        markAllAsOld();
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(
                payment("Wpłacić drugą ratę", ReadingDocumentsIntegrationTest.SECOND_LINE, "5000.00", "2027-01-15", "INSTALMENT"))));
        int before = suggestionRows();

        round.round();

        assertThat(triageCalls()).as("the AI service saw the steps once").isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        assertThat(attachmentsFetched()).as("the documents were read once").isEqualTo(1);
        assertThat(suggestionRows()).as("one set of proposals").isEqualTo(before + 1);
        assertThat(jdbc.queryForList("SELECT documents FROM mail_message", String.class)).containsExactly("DONE", "DONE");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT letter_key) FROM mail_message", Integer.class)).as("both keep the key").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(letter_key) FROM mail_message", Integer.class)).isEqualTo(2);
        // A second round asks nothing.
        service.reset();
        round.round();
        assertThat(service.calls()).isEmpty();
    }

    @Test
    void theDocumentsOfALetterInBothMailboxesAreReadOnceWhicheverCopyIsReachedFirst() throws Exception {
        theOwnersCopiesOfOneLetter(true);
    }

    @Test
    void theSameWhenTheOtherMailboxIsTheNewer() throws Exception {
        theOwnersCopiesOfOneLetter(false);
    }

    @Test
    void aMessageWhoseOtherCopyIsMarkedAsACopyIsLookedAtForItIsTheOneThatWasRead() throws Exception {
        byte[] pdf = TestPdfs.withPages(OFFER);
        writeWithPdf(ANNA_MAILBOX, "read-one", "<letter@vendor.example>", pdf);
        writeWithPdf(ANNA_MAILBOX, "copy-one", "<letter@vendor.example>", pdf);
        note();
        MailReading.MessageHandle read = new MailReading.MessageHandle(weddingId(), anna(), "read-one", "thread-read-one");
        MailReading.MessageHandle copy = new MailReading.MessageHandle(weddingId(), anna(), "copy-one", "thread-copy-one");
        String key = ((MailReading.Letter) mail.read(read)).key();
        assertThat(key).isNotEmpty();
        mail.mark(read, new MailReading.AboutTheWedding(1), "");
        mail.mark(copy, new MailReading.CopyOfOneRead(), key);
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 'read-one'");
        service.reset();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays());

        round.round();

        assertThat(attachmentsFetched()).isEqualTo(1);
        assertThat(documentsOf("read-one")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = 'read-one'", String.class)).isEqualTo(key);
    }

    @Test
    void letterWithoutAMessageIdIsLookedAtAsBefore() throws Exception {
        byte[] pdf = TestPdfs.withPages(OFFER);
        writeWithPdf(ANNA_MAILBOX, "no-id-1", null, pdf);
        writeWithPdf(ANNA_MAILBOX, "no-id-2", null, pdf);
        note();
        markAllAsOld();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays(), triageSays(true, 95), extractionSays());

        round.round();

        assertThat(attachmentsFetched()).isEqualTo(2);
        assertThat(triageCalls()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(letter_key) FROM mail_message", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT documents FROM mail_message", String.class)).containsExactly("DONE", "DONE");
    }

    // ---------------------------------------------------------------- while the reader is held

    @Test
    void aSecondLookWhileTheReaderIsHeldTakesNothingAndMarksNothing() throws Exception {
        anOldMessage("m-old", Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        PdfText held = new PdfText(java.time.Duration.ofMillis(100), (pdf, stop) -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    // A stuck library does not look.
                }
            }
            return java.util.Optional.empty();
        });
        assertThat(held.read(new byte[] {1})).isEmpty();
        assertThat(held.busy()).isTrue();
        int threads = threadCalls();

        new ReadingRound(steps, rules(), mail, suggestions, weddings, maker, usage, held, clock).round();

        assertThat(threadCalls()).as("no message was taken").isEqualTo(threads);
        assertThat(service.calls()).isEmpty();
        assertThat(documentsOf("m-old")).isEqualTo("PENDING");
        release.countDown();
    }
}
