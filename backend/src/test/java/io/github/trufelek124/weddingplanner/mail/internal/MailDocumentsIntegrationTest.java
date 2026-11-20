package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestPdfs;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Document;
import io.github.trufelek124.weddingplanner.mail.MailReading.DocumentWhy;
import io.github.trufelek124.weddingplanner.mail.MailReading.Fetched;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotAboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotFetched;
import io.github.trufelek124.weddingplanner.mail.MailReading.Pdf;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The PDF documents that come with a message (S04 sections 3 and 5, D-011): a letter says which came with it and fetches
 * nothing; a document is fetched when the reader asks, within its limits; one try per message, also after a restart. Everything
 * is made up and nothing goes to the real Google.
 */
class MailDocumentsIntegrationTest extends MailLettersTestBase {

    static final long MEGABYTE = 1024 * 1024;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    @org.springframework.beans.factory.annotation.Autowired
    GmailAccess access;

    Mailbox box;

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
        box = annasBox();
    }

    private final Instant base = TestClock.now().truncatedTo(ChronoUnit.MILLIS);

    private Instant at(long daysAgo) {
        return base.minus(Duration.ofDays(daysAgo));
    }

    /** A message from Jan with one PDF; the document has these bytes (a real one made for the test). */
    private Document oneOffer(String message, byte[] bytes, long listedSize) {
        box.write(message, "t-" + message, at(1), JAN, "Oferta", Mime.multipart("mixed", plain("Oferta w załączniku."),
                Mime.file("oferta.pdf", "application/pdf", "att-" + message, listedSize)), "INBOX");
        box.attach("att-" + message, bytes);
        List<Document> documents = letterOf(message, "t-" + message).documents();
        assertThat(documents).hasSize(1);
        return documents.get(0);
    }

    private Fetched fetch(String message, Document document) {
        return reading.document(annas(message, "t-" + message), document);
    }

    private static byte[] bigPdf(long size) {
        byte[] bytes = new byte[(int) size];
        System.arraycopy("%PDF-1.7\n".getBytes(), 0, bytes, 0, 9);
        return bytes;
    }

    // ---------------------------------------------------------------- what a letter lists

    @Test
    void aLetterListsTheMessagesPdfDocumentsAndNothingElseAndFetchesNothing() {
        Mime body = Mime.multipart("mixed",
                Mime.multipart("alternative", plain("See the offer."), Mime.html("<p>See the offer.</p>")),
                Mime.file("oferta-sala.pdf", "application/pdf", "att-1", 120_000),
                Mime.multipart("mixed", Mime.file("Umowa.PDF", "application/octet-stream", "att-2", 5_000)),
                Mime.file("photo.jpg", "image/jpeg", "att-3", 900),
                Mime.file("contract.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "att-4", 800),
                Mime.file("", "application/pdf", "att-5", 70),
                Mime.file("sheet.xlsx", "application/vnd.ms-excel", "att-6", 100),
                Mime.file("not-a-pdf.pdf.txt", "text/plain", "att-7", 100),
                Mime.inlineFile("inline.pdf", "application/pdf", "no attachment identifier: nothing to fetch it by"));
        box.write("m-1", "t-1", at(1), JAN, "Offer", body, "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.documents()).extracting(Document::name, Document::size)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("oferta-sala.pdf", 120_000L), org.assertj.core.groups.Tuple.tuple("Umowa.PDF", 5_000L),
                        org.assertj.core.groups.Tuple.tuple("", 70L));
        assertThat(letter.documents()).allSatisfy(document -> assertThat(document.reference()).isNotBlank());
        assertThat(letter.text().text()).isEqualTo("See the offer.");
        assertThat(google.count(BELOW_A_MESSAGE)).as("listing fetches nothing").isZero();
        assertThat(google.calls(THREADS)).hasSize(1);
    }

    @Test
    void aDocumentOfAnEarlierMessageOfTheThreadIsNotListed() {
        box.write("m-1", "t-1", at(3), JAN, "Offer", Mime.multipart("mixed", plain("First."), Mime.file("first.pdf", "application/pdf", "att-1", 10)), "INBOX");
        box.write("m-2", "t-1", at(1), JAN, "Re: Offer", Mime.multipart("mixed", plain("Second."), Mime.file("second.pdf", "application/pdf", "att-2", 20)), "INBOX");

        assertThat(letterOf("m-2", "t-1").documents()).extracting(Document::name).containsExactly("second.pdf");
        assertThat(letterOf("m-1", "t-1").documents()).extracting(Document::name).containsExactly("first.pdf");
    }

    @Test
    void aMessageWithoutDocumentsListsNone() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");

        assertThat(letterOf("m-1", "t-1").documents()).isEmpty();
    }

    @Test
    void anAttachmentIdentifierThatIsNotGooglesKindIsNotListed() {
        box.write("m-1", "t-1", at(1), JAN, "Offer",
                Mime.multipart("mixed", plain("Words."), Mime.file("a.pdf", "application/pdf", "../profile", 10),
                        Mime.file("b.pdf", "application/pdf", "att/../x", 10), Mime.file("c.pdf", "application/pdf", "ok-1_x", 10)), "INBOX");

        assertThat(letterOf("m-1", "t-1").documents()).extracting(Document::name).containsExactly("c.pdf");
    }

    @Test
    void aMessageThatListsMoreThanTwentyDocumentsListsTwenty() {
        Mime[] parts = new Mime[31];
        parts[0] = plain("Words.");
        for (int i = 1; i < parts.length; i++) {
            parts[i] = Mime.file("d" + i + ".pdf", "application/pdf", "att-" + i, 10);
        }
        box.write("m-1", "t-1", at(1), JAN, "Offers", Mime.multipart("mixed", parts), "INBOX");

        assertThat(letterOf("m-1", "t-1").documents()).hasSize(20);
    }

    // ---------------------------------------------------------------- fetching one

    @Test
    void aDocumentIsFetchedWithTheRightCallAndComesBackAsItWas() {
        byte[] file = TestPdfs.withPages("Zaliczka 2500 zł do 1 grudnia 2026");
        Document document = oneOffer("m-1", file, file.length);

        Fetched fetched = fetch("m-1", document);

        assertThat(fetched).isInstanceOf(Pdf.class);
        assertThat(((Pdf) fetched).bytes()).isEqualTo(file);
        assertThat(google.calls(BELOW_A_MESSAGE)).hasSize(1);
        assertThat(google.calls(BELOW_A_MESSAGE).getFirst().method()).isEqualTo("GET");
        assertThat(google.calls(BELOW_A_MESSAGE).getFirst().form()).containsEntry("message", "m-1").containsEntry("attachment", "att-m-1");
        assertThat(google.calls(BELOW_A_MESSAGE).getFirst().authorization()).startsWith("Bearer ");
        assertThat(google.count(MESSAGES)).as("nothing else of Gmail was used").isZero();
    }

    @Test
    void aDocumentThatGmailSaysIsLargerThanTenMegabytesIsNotFetchedAtAll() {
        Document document = oneOffer("m-1", bigPdf(100), 10 * MEGABYTE + 1);

        assertThat(fetch("m-1", document)).isEqualTo(new NotFetched(DocumentWhy.TOO_LARGE));
        assertThat(google.count(BELOW_A_MESSAGE)).isZero();
    }

    @Test
    void aDocumentOfExactlyTenMegabytesIsTakenAndOneByteMoreByTheAnswerIsNot() {
        Document exactly = oneOffer("m-1", bigPdf(10 * MEGABYTE), 10 * MEGABYTE);
        assertThat(fetch("m-1", exactly)).isInstanceOf(Pdf.class);

        // Gmail says it is small and the answer is larger: the limit on the answer holds.
        Document liar = oneOffer("m-2", bigPdf(10 * MEGABYTE + 1), 500);
        assertThat(fetch("m-2", liar)).isEqualTo(new NotFetched(DocumentWhy.TOO_LARGE));
        // A very much larger one is cut off while it comes, not read to its end.
        Document huge = oneOffer("m-3", bigPdf(30 * MEGABYTE), 0);
        assertThat(fetch("m-3", huge)).isEqualTo(new NotFetched(DocumentWhy.TOO_LARGE));
    }

    @Test
    void whatDoesNotBeginLikeAPdfIsNotADocument() {
        Document html = oneOffer("m-1", "<html>not a document</html>".getBytes(), 100);
        Document empty = oneOffer("m-2", new byte[0], 100);
        Document short4 = oneOffer("m-3", "%PDF".getBytes(), 100);
        Document late = oneOffer("m-4", " %PDF-1.7 starts one byte too late".getBytes(), 100);

        for (String message : List.of("m-1", "m-2", "m-3", "m-4")) {
            Document document = switch (message) {
                case "m-1" -> html;
                case "m-2" -> empty;
                case "m-3" -> short4;
                default -> late;
            };
            assertThat(fetch(message, document)).as(message).isEqualTo(new NotFetched(DocumentWhy.NOT_A_DOCUMENT));
        }
    }

    @Test
    void anAnswerThatIsNotWhatAnAttachmentLooksLikeIsNotADocument() {
        Document document = oneOffer("m-1", bigPdf(50), 50);
        for (String body : List.of("{\"size\":3}", "{\"data\":\"***not base64***\"}", "{\"data\":12}", "{\"data\":null}")) {
            google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.json(200, body));

            assertThat(fetch("m-1", document)).as(body).isEqualTo(new NotFetched(DocumentWhy.NOT_A_DOCUMENT));
        }
    }

    @Test
    void aDocumentThatIsGoneIsGone() {
        Document document = oneOffer("m-1", bigPdf(50), 50);
        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.json(404, "{\"error\":{\"code\":404}}"));

        assertThat(fetch("m-1", document)).isEqualTo(new NotFetched(DocumentWhy.GONE));
        // An attachment that Gmail has never had.
        assertThat(fetch("m-1", new Document("x.pdf", 10, "att-nobody-has"))).isEqualTo(new NotFetched(DocumentWhy.GONE));
        // A reference that is not Gmail's kind is never sent on.
        int before = google.count(BELOW_A_MESSAGE);
        assertThat(fetch("m-1", new Document("x.pdf", 10, "../profile"))).isEqualTo(new NotFetched(DocumentWhy.GONE));
        assertThat(fetch("m-1", new Document("x.pdf", 10, ""))).isEqualTo(new NotFetched(DocumentWhy.GONE));
        assertThat(reading.document(annas("m 1/../x", "t-m-1"), document)).isEqualTo(new NotFetched(DocumentWhy.GONE));
        assertThat(google.count(BELOW_A_MESSAGE)).isEqualTo(before);
    }

    @Test
    void whenGoogleIsNotWellADocumentIsRefusedForNowOrInterrupted() {
        Document document = oneOffer("m-1", bigPdf(50), 50);

        for (int status : new int[] {429, 500, 503}) {
            google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.json(status, "{\"error\":{\"code\":" + status + "}}"));
            assertThat(fetch("m-1", document)).as("status " + status).isEqualTo(new NotFetched(DocumentWhy.REFUSED_FOR_NOW));
        }
        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.json(401, "{}"), Reply.json(401, "{}"));
        assertThat(fetch("m-1", document)).as("a token refused twice").isEqualTo(new NotFetched(DocumentWhy.REFUSED_FOR_NOW));
        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.json(401, "{}"), Reply.normally());
        assertThat(fetch("m-1", document)).as("a token made anew once").isInstanceOf(Pdf.class);

        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.slow(2_600));
        assertThat(fetch("m-1", document)).as("slow").isEqualTo(new NotFetched(DocumentWhy.INTERRUPTED));
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, Reply.dropped());
        assertThat(fetch("m-1", document)).as("cut off").isEqualTo(new NotFetched(DocumentWhy.INTERRUPTED));
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.text(200, "<html>not json</html>"));
        assertThat(fetch("m-1", document)).as("not JSON").isEqualTo(new NotFetched(DocumentWhy.INTERRUPTED));
        google.answerNextCallsToWith(BELOW_A_MESSAGE, Reply.redirectTo(google.elsewhere()));
        assertThat(fetch("m-1", document)).as("a redirect is not followed").isEqualTo(new NotFetched(DocumentWhy.INTERRUPTED));
        assertThat(google.count("/elsewhere")).isZero();
    }

    @Test
    void aMailboxThatIsNotConnectedOrWhosePermissionIsGoneGivesNoDocument() {
        Document document = oneOffer("m-1", bigPdf(50), 50);

        assertThat(reading.document(piotrs("m-1", "t-m-1"), document)).isEqualTo(new NotFetched(DocumentWhy.NOT_CONNECTED));
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));
        // The access token that was made for the letter is no longer there to be used.
        access.forget(anna());
        assertThat(fetch("m-1", document)).isEqualTo(new NotFetched(DocumentWhy.NEEDS_RECONNECTING));
        assertThat(state(anna())).isEqualTo("NEEDS_RECONNECTING");
        assertThat(google.count(BELOW_A_MESSAGE)).isZero();
    }

    // ---------------------------------------------------------------- one try per message

    private MessageHandle notedMessage(String id) {
        box.write(id, "t-" + id, at(1), JAN, "Oferta", Mime.multipart("mixed", plain("Oferta."), Mime.file("o.pdf", "application/pdf", "att-" + id, 10)), "INBOX");
        noteEverything(anna());
        return annas(id, "t-" + id);
    }

    private String documentsOf(String id) {
        return jdbc.queryForObject("SELECT documents FROM mail_message WHERE message_id = ?", String.class, id);
    }

    @Test
    void theFirstTryOfAMessagesDocumentsIsTheOnlyOneAlsoAfterARestart() {
        MessageHandle message = notedMessage("m-1");
        MessageHandle other = notedMessage("m-2");
        assertThat(documentsOf("m-1")).isEqualTo("PENDING");

        assertThat(reading.beginDocuments(message)).as("the first time").isTrue();
        assertThat(documentsOf("m-1")).as("written at once").isEqualTo("BEGUN");
        assertThat(reading.beginDocuments(message)).as("the second time").isFalse();
        assertThat(reading.beginDocuments(message)).isFalse();

        // A restart: nothing is remembered but what the table holds.
        MailReadingService afterARestart = new MailReadingService(setup, source, messageRepository, connections, clock);
        assertThat(afterARestart.beginDocuments(message)).as("after a restart").isFalse();
        assertThat(afterARestart.beginDocuments(other)).as("another message has its own try").isTrue();
        assertThat(documentsOf("m-2")).isEqualTo("BEGUN");
    }

    @Test
    void theTryIsWrittenInATransactionOfItsOwnBeforeAnythingIsFetched() {
        MessageHandle message = notedMessage("m-1");
        org.springframework.transaction.support.TransactionTemplate outer =
                new org.springframework.transaction.support.TransactionTemplate(transactions);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            assertThat(reading.beginDocuments(message)).isTrue();
            throw new IllegalStateException("the work around it fails and rolls back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(documentsOf("m-1")).as("it stands though the transaction around it was rolled back").isEqualTo("BEGUN");
        assertThat(reading.beginDocuments(message)).isFalse();
    }

    @Test
    void aMessageThatIsNotThereOrIsNotOfThatWeddingHasNoTry() {
        MessageHandle message = notedMessage("m-1");

        assertThat(reading.beginDocuments(annas("nobody", "t-nobody"))).isFalse();
        assertThat(reading.beginDocuments(new MessageHandle(UUID.randomUUID(), message.memberId(), "m-1", "t-m-1"))).isFalse();
        assertThat(documentsOf("m-1")).isEqualTo("PENDING");
    }

    @Test
    void aMessageThatWasMarkedHasItsDocumentsDoneAndNoTryIsLeft() {
        MessageHandle message = notedMessage("m-1");

        reading.mark(message, new NotAboutTheWedding());

        assertThat(documentsOf("m-1")).isEqualTo("DONE");
        assertThat(reading.beginDocuments(message)).isFalse();
    }

    // ---------------------------------------------------------------- the second look's queue

    @Test
    void theMessagesWaitingForTheirDocumentsAreTheOnesAboutTheWeddingNeverLookedAt() {
        MessageHandle wedding1 = notedMessage("m-1");
        MessageHandle notWedding = notedMessage("m-2");
        MessageHandle waiting = notedMessage("m-3");
        MessageHandle begun = notedMessage("m-4");
        MessageHandle wedding5 = notedMessage("m-5");
        // As the messages were read before documents could be: about the wedding, and the documents still to be looked at.
        reading.mark(wedding1, new AboutTheWedding(2));
        reading.mark(notWedding, new NotAboutTheWedding());
        reading.mark(begun, new AboutTheWedding(1));
        reading.mark(wedding5, new AboutTheWedding(0));
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id IN ('m-1', 'm-4', 'm-5')");
        assertThat(reading.beginDocuments(begun)).isTrue();

        List<MessageHandle> queue = reading.waitingForDocuments(10, List.of());

        // The newest first: the order of noting, the last noted is the newest here (m-5, then m-1).
        assertThat(queue).extracting(MessageHandle::messageId).containsExactlyInAnyOrder("m-1", "m-5");
        assertThat(queue).extracting(MessageHandle::messageId).as("only what waits").doesNotContain("m-2", "m-3", "m-4");
        assertThat(reading.waitingForDocuments(1, List.of())).hasSize(1);
        assertThat(reading.waitingForDocuments(10, List.of(anna()))).as("a mailbox that is left out").isEmpty();
        assertThatThrownBy(() -> reading.waitingForDocuments(0, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(waiting.messageId()).isEqualTo("m-3");
    }

    @Test
    void theDocumentsOfAMessageAreDoneWithOnceAndTheProposalsTheyBroughtAreAddedToItsCount() {
        MessageHandle message = notedMessage("m-1");
        reading.mark(message, new AboutTheWedding(2));
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 'm-1'");
        assertThat(reading.counts(weddingId()).proposals()).isEqualTo(2);

        assertThat(reading.documentsDone(message, 3)).isTrue();

        assertThat(documentsOf("m-1")).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT proposals FROM mail_message WHERE message_id = 'm-1'", Integer.class)).isEqualTo(5);
        assertThat(reading.counts(weddingId()).proposals()).isEqualTo(5);
        assertThat(reading.documentsDone(message, 3)).as("only once").isFalse();
        assertThat(jdbc.queryForObject("SELECT proposals FROM mail_message WHERE message_id = 'm-1'", Integer.class)).isEqualTo(5);
        assertThat(reading.waitingForDocuments(10, List.of())).isEmpty();
        assertThatThrownBy(() -> reading.documentsDone(message, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageThatIsNotAboutTheWeddingHasNoDocumentsToBeDoneWith() {
        MessageHandle message = notedMessage("m-1");
        reading.mark(message, new NotAboutTheWedding());

        assertThat(reading.documentsDone(message, 1)).isFalse();
        assertThat(reading.documentsDone(annas("nobody", "t-nobody"), 0)).isFalse();
    }

    @Test
    void whatTheTableHoldsOfDocumentsIsAStateAndNothingOfADocument() {
        MessageHandle message = notedMessage("m-1");
        reading.beginDocuments(message);

        String row = jdbc.queryForObject("SELECT row_to_json(t)::text FROM mail_message t", String.class);

        assertThat(row).contains("\"documents\":\"BEGUN\"").doesNotContain("o.pdf").doesNotContain("att-m-1");
        assertThatThrownBy(() -> jdbc.update("UPDATE mail_message SET documents = 'MAYBE' WHERE message_id = 'm-1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE mail_message SET documents = NULL WHERE message_id = 'm-1'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE mail_message SET verdict = 'NOT_WEDDING', read_at = now(), documents = 'PENDING' WHERE message_id = 'm-1'"))
                .as("a message that is not about the wedding has no documents still to look at")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- taking the mark back, copies, and the key

    @Test
    void theMarkOfTheTryIsTakenBackOnceTheTextIsOutAndOnlyThen() {
        MessageHandle message = notedMessage("m-1");
        assertThat(reading.endDocuments(message)).as("nothing was begun").isFalse();

        assertThat(reading.beginDocuments(message)).isTrue();
        assertThat(documentsOf("m-1")).isEqualTo("BEGUN");
        assertThat(reading.endDocuments(message)).isTrue();
        assertThat(documentsOf("m-1")).isEqualTo("PENDING");
        assertThat(reading.endDocuments(message)).as("only once").isFalse();
        assertThat(reading.beginDocuments(message)).as("it may be begun again").isTrue();

        reading.mark(message, new AboutTheWedding(0));
        assertThat(reading.endDocuments(message)).as("a message that is done with has no mark to take back").isFalse();
        assertThat(documentsOf("m-1")).isEqualTo("DONE");
        assertThat(reading.endDocuments(annas("nobody", "t-nobody"))).isFalse();
    }

    @Test
    void takingTheMarkBackIsInATransactionOfItsOwnToo() {
        MessageHandle message = notedMessage("m-1");
        reading.beginDocuments(message);
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(transactions);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            assertThat(reading.endDocuments(message)).isTrue();
            throw new IllegalStateException("rolled back");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(documentsOf("m-1")).isEqualTo("PENDING");
    }

    @Test
    void aMessageMarkedAboutTheWeddingCanKeepItsDocumentsStillToBeLookedAtAndNoOtherVerdictCan() {
        MessageHandle wedding = notedMessage("m-1");
        MessageHandle other = notedMessage("m-2");
        MessageHandle copy = notedMessage("m-3");

        reading.mark(wedding, new AboutTheWedding(1), "", true);
        reading.mark(other, new NotAboutTheWedding(), "", true);
        reading.mark(copy, new MailReading.CopyOfOneRead(), "l1-" + "D".repeat(43), true);

        assertThat(documentsOf("m-1")).isEqualTo("PENDING");
        assertThat(documentsOf("m-2")).isEqualTo("DONE");
        assertThat(documentsOf("m-3")).isEqualTo("DONE");
        assertThat(reading.waitingForDocuments(10, List.of())).extracting(MessageHandle::messageId).containsExactly("m-1");
    }

    @Test
    void theLettersKeyIsKeptWhereTheRowHasNoneAndNeverReplacedAndAnEmptyOneKeepsNone() {
        MessageHandle one = notedMessage("m-1");
        MessageHandle two = notedMessage("m-2");
        MessageHandle three = notedMessage("m-3");
        for (MessageHandle message : List.of(one, two, three)) {
            reading.mark(message, new AboutTheWedding(0));
            jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = ?", message.messageId());
        }
        String key = "l1-" + "E".repeat(43);

        assertThat(reading.documentsDone(one, 0, key)).isTrue();
        jdbc.update("UPDATE mail_message SET letter_key = ? WHERE message_id = 'm-2'", "l1-" + "F".repeat(43));
        assertThat(reading.documentsDone(two, 0, key)).isTrue();
        assertThat(reading.documentsDone(three, 0, "")).isTrue();

        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = 'm-1'", String.class)).isEqualTo(key);
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = 'm-2'", String.class)).as("not replaced").isEqualTo("l1-" + "F".repeat(43));
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = 'm-3'", String.class)).isNull();
        assertThatThrownBy(() -> reading.documentsDone(one, 0, "not-a-key")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCopyWhoseDocumentsWereLookedAtIsOnlyAnotherMessageAboutTheWeddingWithTheKeyAndItsDocumentsDone() {
        MessageHandle looked = notedMessage("m-1");
        MessageHandle asked = notedMessage("m-2");
        MessageHandle marked = notedMessage("m-3");
        MessageHandle pending = notedMessage("m-4");
        String key = "l1-" + "G".repeat(43);
        reading.mark(looked, new AboutTheWedding(1), key);
        reading.mark(marked, new MailReading.CopyOfOneRead(), key);
        reading.mark(pending, new AboutTheWedding(1), key, true);

        assertThat(reading.documentsOfACopyWereLookedAt(asked, key)).as("m-1 is about the wedding and done with").isTrue();
        assertThat(reading.documentsOfACopyWereLookedAt(looked, key)).as("not its own copy, but m-3 is a COPY and m-4 is pending").isFalse();
        jdbc.update("UPDATE mail_message SET documents = 'DONE' WHERE message_id = 'm-4'");
        assertThat(reading.documentsOfACopyWereLookedAt(looked, key)).isTrue();
        assertThat(reading.documentsOfACopyWereLookedAt(asked, "")).isFalse();
        assertThat(reading.documentsOfACopyWereLookedAt(asked, "l1-" + "H".repeat(43))).isFalse();
        // Another wedding's letter with the same key is not a copy.
        assertThat(reading.documentsOfACopyWereLookedAt(new MessageHandle(UUID.randomUUID(), asked.memberId(), "m-2", "t-m-2"), key)).isFalse();
    }

    @Test
    void theQuestionOfACopyIsServedByTheIndexOnTheKey() {
        jdbc.execute("SET enable_seqscan = off");
        try {
            String plan = String.join("\n", jdbc.queryForList(
                    "EXPLAIN SELECT 1 FROM mail_message m WHERE m.letter_key = 'l1-" + "A".repeat(43) + "' AND m.verdict = 'WEDDING' AND m.documents = 'DONE'", String.class));
            assertThat(plan).contains("mail_message_letter_key");
        } finally {
            jdbc.execute("RESET enable_seqscan");
        }
    }

    // ---------------------------------------------------------------- a reference is never a step of a path

    @Test
    void aReferenceOfDotsAloneIsNotListedAndNotAskedFor() {
        box.write("m-1", "t-1", at(1), JAN, "Offer", Mime.multipart("mixed", plain("Words."),
                Mime.file("a.pdf", "application/pdf", "..", 10), Mime.file("b.pdf", "application/pdf", ".", 10),
                Mime.file("c.pdf", "application/pdf", "....", 10), Mime.file("d.pdf", "application/pdf", ".d", 10),
                Mime.file("e.pdf", "application/pdf", "e..", 10)), "INBOX");

        assertThat(letterOf("m-1", "t-1").documents()).extracting(Document::name).containsExactly("d.pdf", "e.pdf");
        for (String reference : List.of("..", ".", "...")) {
            assertThat(reading.document(annas("m-1", "t-1"), new Document("x.pdf", 10, reference))).as(reference).isEqualTo(new NotFetched(DocumentWhy.GONE));
        }
        assertThat(google.count(BELOW_A_MESSAGE)).as("no call was made").isZero();
    }
}
