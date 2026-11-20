package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.EarlierMessage;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a letter says of the earlier messages of its thread (D-012): which of them wait in this same mailbox, what each is
 * known by across mailboxes, and whether it lists a PDF document. One question to the table for the whole letter. Made-up mail.
 */
class MailEarlierMessagesIntegrationTest extends MailLettersTestBase {

    static final String THREAD = "thread-e";

    @BeforeEach
    void bothAreConnected() throws Exception {
        connectAnna();
        connectPiotr();
    }

    private void annaWrites(String id, long daysAgo, String messageId, Mime body, String... labels) {
        annasBox().writeWithMessageId(id, THREAD, daysAgo(daysAgo), JAN, "Rezerwacja sali", body, messageId, labels);
    }

    private static Mime withAPdf(String id) {
        return Mime.multipart("mixed", Mime.plain("Dzień dobry, oferta w załączniku."), Mime.file("oferta.pdf", "application/pdf", "att-" + id, 100));
    }

    private List<EarlierMessage> earlierOfTheNewest() {
        return letterOf("e-new", THREAD).earlier();
    }

    private EarlierMessage earlier(String text) {
        return earlierOfTheNewest().stream().filter(message -> message.text().text().contains(text)).findFirst().orElseThrow();
    }

    @Test
    void anEarlierMessageThatIsNotedAndHasNoVerdictWaitsAndTheOneThatWasReadDoesNot() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-2", 4, "<two@vendor.example>", plain("SECOND message"), "INBOX");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        noteEverything(anna());
        assertThat(mailReading().mark(annas("e-2", THREAD), new AboutTheWedding(1))).isTrue();

        assertThat(earlier("FIRST").waiting()).contains(annas("e-1", THREAD));
        assertThat(earlier("SECOND").waiting()).as("it was read").isEmpty();
        assertThat(earlierOfTheNewest()).hasSize(2);
    }

    @Test
    void theOwnersReplyAndAMessageTheSyncNeverNotedAreNotWaiting() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-reply", 4, "<reply@planner.example>", plain("OWN reply"), "SENT");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        noteEverything(anna());
        // Arrived in the thread after the look: Gmail shows it, the table has never heard of it.
        annaWrites("e-late", 3, "<late@vendor.example>", plain("UNNOTED message"), "INBOX");

        assertThat(noted(anna())).containsExactlyInAnyOrder("e-1", "e-new");
        assertThat(earlier("FIRST").waiting()).isPresent();
        assertThat(earlier("OWN").waiting()).isEmpty();
        assertThat(earlier("UNNOTED").waiting()).isEmpty();
    }

    @Test
    void theSameThreadInTheOtherMailboxIsNotThisMailboxsMessage() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        // The other mailbox noted a message that has the same identifiers: it is Piotr's, not Anna's.
        piotrsBox().writeWithMessageId("e-1", THREAD, daysAgo(5), JAN, "Rezerwacja sali", plain("FIRST message"), "<one@vendor.example>", "INBOX");
        piotrsBox().writeWithMessageId("e-mine", THREAD, daysAgo(4), JAN, "Rezerwacja sali", plain("PIOTR only"), "<mine@vendor.example>", "INBOX");
        lookBack(piotr(), 90);
        sync.lookAtAll();
        // Anna's own notes of those two are gone: only Piotr has them.
        jdbc.update("DELETE FROM mail_message WHERE member_id = ?", anna());
        annasBox().writeWithMessageId("e-mine", THREAD, daysAgo(4), JAN, "Rezerwacja sali", plain("PIOTR only"), "<mine@vendor.example>", "INBOX");

        assertThat(noted(piotr())).contains("e-1", "e-mine");
        assertThat(earlier("FIRST").waiting()).as("noted in Piotr's mailbox only").isEmpty();
        assertThat(earlier("PIOTR").waiting()).isEmpty();
    }

    @Test
    void anEarlierMessageIsKnownByTheSameKeyAsItsOwnLetterAndTheKeyIsEmptyWithoutAMessageId() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-2", 4, null, plain("SECOND message"), "INBOX");
        annaWrites("e-3", 3, "  ", plain("THIRD message"), "INBOX");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        noteEverything(anna());

        assertThat(earlier("FIRST").key()).isEqualTo(letterOf("e-1", THREAD).key()).matches("l1-[A-Za-z0-9_-]{43}")
                .doesNotContain("one@vendor");
        assertThat(earlier("SECOND").key()).isEmpty();
        assertThat(earlier("THIRD").key()).isEmpty();
    }

    @Test
    void anEarlierMessageSaysWhetherItListsAPdfDocumentAndNothingIsFetchedToKnowIt() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-2", 4, "<two@vendor.example>", withAPdf("e-2"), "INBOX");
        annaWrites("e-3", 3, "<three@vendor.example>", Mime.multipart("mixed", plain("THIRD message"),
                Mime.file("a.docx", "application/msword", "att-e-3", 100)), "INBOX");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        noteEverything(anna());

        assertThat(earlier("FIRST").documents()).isFalse();
        assertThat(earlier("oferta").documents()).isTrue();
        assertThat(earlier("THIRD").documents()).as("a Word file is not a document here").isFalse();
        assertThat(google.count(BELOW_A_MESSAGE)).as("no attachment was fetched").isZero();
    }

    @Test
    void whatAnEarlierMessageHoldsIsNotPrintedAndOneLetterAsksTheTableOnce() {
        annaWrites("e-1", 5, "<one@vendor.example>", plain("FIRST message"), "INBOX");
        annaWrites("e-2", 4, "<two@vendor.example>", plain("SECOND message"), "INBOX");
        annaWrites("e-new", 1, "<new@vendor.example>", plain("NEWEST message"), "INBOX");
        noteEverything(anna());

        Letter letter = letterOf("e-new", THREAD);

        assertThat(letter.earlier()).allSatisfy(message -> assertThat(message.toString()).isEqualTo("EarlierMessage[]"));
        assertThat(letter.earlier().stream().map(EarlierMessage::waiting).filter(java.util.Optional::isPresent).map(java.util.Optional::get).toList())
                .extracting(MessageHandle::messageId).containsExactly("e-1", "e-2");
        assertThat(messageRepository.waitingAmong(anna(), THREAD, List.of())).isEmpty();
    }

    private io.github.trufelek124.weddingplanner.mail.MailReading mailReading() {
        return reading;
    }
}
