package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Counts;
import io.github.trufelek124.weddingplanner.mail.MailReading.CopyOfOneRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.CouldNotBeRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotAboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Reason;
import io.github.trufelek124.weddingplanner.TestHttp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A letter is known by the identifier its sender gave it (S04 sections 2 and 6): the same letter in both mailboxes has
 * one key, a copy is recognised from what another message of the wedding was read as, and the new verdict is stored and
 * counted on its own. Made-up mail only; nothing goes to the real Google.
 */
class MailLetterKeysIntegrationTest extends MailLettersTestBase {

    static final String ID = "<letter-1.Qk7@vendor.example>";

    @BeforeEach
    void bothAreConnected() throws Exception {
        connectAnna();
        connectPiotr();
    }

    private void annaGets(String id, String thread, String messageIdHeader) {
        annasBox().writeWithMessageId(id, thread, daysAgo(2), JAN, "Rezerwacja", plain("Dzień dobry, potwierdzam rezerwację."), messageIdHeader, "INBOX");
    }

    private void piotrGets(String id, String thread, String messageIdHeader) {
        piotrsBox().writeWithMessageId(id, thread, daysAgo(2), JAN, "Rezerwacja", plain("Dzień dobry, potwierdzam rezerwację."), messageIdHeader, "INBOX");
    }

    private String keyOfAnnas(String id, String thread) {
        return letterOf(id, thread).key();
    }

    private String keyOfPiotrs(String id, String thread) {
        return ((Letter) reading.read(piotrs(id, thread))).key();
    }

    private void noteAll() {
        lookBack(piotr(), 90);
        noteEverything(anna());
    }

    private Map<String, Object> stored(String messageId) {
        return jdbc.queryForMap("SELECT verdict, unread_reason, proposals, letter_key, read_at FROM mail_message WHERE message_id = ?", messageId);
    }

    // ---------------------------------------------------------------- the key

    @Test
    void theSameLetterInBothMailboxesHasOneKeyThatSaysNothingReadable() {
        annaGets("a-1", "ta-1", ID);
        piotrGets("p-1", "tp-1", ID);

        String key = keyOfAnnas("a-1", "ta-1");

        assertThat(keyOfPiotrs("p-1", "tp-1")).isEqualTo(key);
        assertThat(key).matches("l1-[A-Za-z0-9_-]{43}").hasSizeLessThanOrEqualTo(100);
        assertThat(key).hasSize(reading.keysOf(annas("a-1", "ta-1")).message().length());
        assertThat(key).doesNotContain("letter-1").doesNotContain("Qk7").doesNotContain("vendor.example").doesNotContain(weddingId().toString());
        assertThat(keyOfAnnas("a-1", "ta-1")).as("the same for ever").isEqualTo(key);
    }

    @Test
    void theAnglesAndSpacesAroundTheIdentifierDoNotCountAndNothingElseIsChanged() {
        annaGets("a-bare", "t-1", "letter-1.Qk7@vendor.example");
        annaGets("a-angles", "t-2", "<letter-1.Qk7@vendor.example>");
        annaGets("a-spaces", "t-3", "  <letter-1.Qk7@vendor.example>  ");
        annaGets("a-inner", "t-4", "< letter-1.Qk7@vendor.example >");
        annaGets("a-case", "t-5", "<Letter-1.Qk7@vendor.example>");
        annaGets("a-other", "t-6", "<letter-2.Qk7@vendor.example>");

        String bare = keyOfAnnas("a-bare", "t-1");

        assertThat(keyOfAnnas("a-angles", "t-2")).isEqualTo(bare);
        assertThat(keyOfAnnas("a-spaces", "t-3")).isEqualTo(bare);
        assertThat(keyOfAnnas("a-inner", "t-4")).as("blanks inside the angles are blanks around the identifier").isEqualTo(bare);
        assertThat(keyOfAnnas("a-case", "t-5")).as("a different case is another letter").isNotEqualTo(bare);
        assertThat(keyOfAnnas("a-other", "t-6")).isNotEqualTo(bare);
    }

    @Test
    void aLetterWithoutTheHeaderOrWithABlankOneHasNoKey() {
        annasBox().writeWithMessageId("a-none", "t-1", daysAgo(2), JAN, "Hi", plain("Dzień dobry."), null, "INBOX");
        annaGets("a-blank", "t-2", "   ");
        annaGets("a-empty", "t-3", "");
        annaGets("a-angles-only", "t-4", " <> ");

        assertThat(keyOfAnnas("a-none", "t-1")).isEmpty();
        assertThat(keyOfAnnas("a-blank", "t-2")).isEmpty();
        assertThat(keyOfAnnas("a-empty", "t-3")).isEmpty();
        assertThat(keyOfAnnas("a-angles-only", "t-4")).isEmpty();
    }

    @Test
    void twoDifferentLettersOfOneThreadHaveTwoKeys() {
        annaGets("a-1", "t-same", "<one@vendor.example>");
        annaGets("a-2", "t-same", "<two@vendor.example>");

        assertThat(keyOfAnnas("a-1", "t-same")).isNotEqualTo(keyOfAnnas("a-2", "t-same"));
    }

    @Test
    void theSameIdentifierInAnotherWeddingsHandsIsAnotherKey() {
        annaGets("a-1", "t-1", ID);

        Letter ours = letterOf("a-1", "t-1");
        Letter theirs = (Letter) reading.read(new MessageHandle(UUID.randomUUID(), anna(), "a-1", "t-1"));

        assertThat(theirs.key()).isNotEqualTo(ours.key()).matches("l1-[A-Za-z0-9_-]{43}");
    }

    // ---------------------------------------------------------------- was a copy read

    @Test
    void aCopyIsRecognisedOnlyOnceAnotherMessageOfTheWeddingWithTheSameKeyHasAVerdict() {
        annaGets("a-1", "ta-1", ID);
        piotrGets("p-1", "tp-1", ID);
        noteAll();
        MessageHandle anna = annas("a-1", "ta-1");
        MessageHandle piotr = piotrs("p-1", "tp-1");
        String key = keyOfAnnas("a-1", "ta-1");

        assertThat(reading.copyWasRead(piotr, key)).as("nothing was read yet").isFalse();
        assertThat(reading.copyWasRead(anna, key)).isFalse();

        assertThat(reading.mark(anna, new AboutTheWedding(2), key)).isTrue();

        assertThat(reading.copyWasRead(piotr, key)).as("the other mailbox's copy").isTrue();
        assertThat(reading.copyWasRead(anna, key)).as("a message is not its own copy").isFalse();
        assertThat(reading.copyWasRead(piotr, "l1-" + "A".repeat(43))).as("another letter").isFalse();
        assertThat(reading.copyWasRead(piotr, "")).as("no key, no copy").isFalse();
        assertThat(reading.copyWasRead(new MessageHandle(UUID.randomUUID(), piotr(), "p-1", "tp-1"), key)).as("another wedding").isFalse();
    }

    @Test
    void whateverTheFirstCopyCameToTheSecondIsACopy() {
        annaGets("a-1", "t-1", "<about@vendor.example>");
        annaGets("a-2", "t-2", "<not-about@vendor.example>");
        annaGets("a-3", "t-3", "<unreadable@vendor.example>");
        piotrGets("p-1", "t-1", "<about@vendor.example>");
        piotrGets("p-2", "t-2", "<not-about@vendor.example>");
        piotrGets("p-3", "t-3", "<unreadable@vendor.example>");
        noteAll();

        reading.mark(annas("a-1", "t-1"), new AboutTheWedding(0), keyOfAnnas("a-1", "t-1"));
        reading.mark(annas("a-2", "t-2"), new NotAboutTheWedding(), keyOfAnnas("a-2", "t-2"));
        reading.mark(annas("a-3", "t-3"), new CouldNotBeRead(Reason.DECLINED), keyOfAnnas("a-3", "t-3"));

        assertThat(reading.copyWasRead(piotrs("p-1", "t-1"), keyOfPiotrs("p-1", "t-1"))).isTrue();
        assertThat(reading.copyWasRead(piotrs("p-2", "t-2"), keyOfPiotrs("p-2", "t-2"))).isTrue();
        assertThat(reading.copyWasRead(piotrs("p-3", "t-3"), keyOfPiotrs("p-3", "t-3"))).isTrue();
    }

    @Test
    void aSecondMessageWithTheSameHeaderInTheSameMailboxIsACopyToo() {
        annaGets("a-1", "t-1", ID);
        annaGets("a-2", "t-2", ID);
        noteAll();
        String key = keyOfAnnas("a-1", "t-1");
        reading.mark(annas("a-1", "t-1"), new NotAboutTheWedding(), key);

        assertThat(keyOfAnnas("a-2", "t-2")).isEqualTo(key);
        assertThat(reading.copyWasRead(annas("a-2", "t-2"), key)).isTrue();
    }

    @Test
    void aMessageMarkedWithoutALetterHasNoKeyAndIsNeverTheOriginalOfACopy() {
        annaGets("a-1", "t-1", ID);
        piotrGets("p-1", "t-1", ID);
        noteAll();

        assertThat(reading.mark(annas("a-1", "t-1"), new CouldNotBeRead(Reason.GONE))).isTrue();

        assertThat(stored("a-1")).containsEntry("letter_key", null).containsEntry("verdict", "UNREADABLE");
        assertThat(reading.copyWasRead(piotrs("p-1", "t-1"), keyOfPiotrs("p-1", "t-1"))).isFalse();
    }

    @Test
    void aMessageThatHasNoVerdictYetIsNoOriginal() {
        annaGets("a-1", "t-1", ID);
        piotrGets("p-1", "t-1", ID);
        noteAll();

        assertThat(reading.copyWasRead(piotrs("p-1", "t-1"), keyOfAnnas("a-1", "t-1"))).isFalse();
    }

    // ---------------------------------------------------------------- the verdict

    @Test
    @SuppressWarnings("unchecked")
    void aCopyIsStoredWithTheKeyAndCountedOnItsOwn() throws Exception {
        annaGets("a-1", "t-1", ID);
        piotrGets("p-1", "t-1", ID);
        annaGets("a-2", "t-2", "<two@vendor.example>");
        annaGets("a-3", "t-3", "<three@vendor.example>");
        annaGets("a-4", "t-4", "<four@vendor.example>");
        noteAll();
        String key = keyOfAnnas("a-1", "t-1");
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(5, 0, 0, 0, 0, 0));

        reading.mark(annas("a-1", "t-1"), new AboutTheWedding(2), key);
        assertThat(reading.mark(piotrs("p-1", "t-1"), new CopyOfOneRead(), key)).isTrue();
        reading.mark(annas("a-2", "t-2"), new NotAboutTheWedding(), keyOfAnnas("a-2", "t-2"));
        reading.mark(annas("a-3", "t-3"), new CouldNotBeRead(Reason.DECLINED), keyOfAnnas("a-3", "t-3"));

        assertThat(stored("p-1")).containsEntry("verdict", "COPY").containsEntry("letter_key", key)
                .containsEntry("unread_reason", null).containsEntry("proposals", null);
        assertThat(stored("p-1").get("read_at")).isNotNull();
        assertThat(stored("a-1")).containsEntry("letter_key", key);
        // Read includes the copy; it is neither about the wedding nor one that could not be read; its proposals are not added.
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(1, 4, 1, 1, 2, 1));
        Map<String, Object> shown = (Map<String, Object>) TestHttp.json(http.getAs(wedding.anna, "/api/mail")).get("reading");
        assertThat(shown).containsEntry("waiting", 1).containsEntry("read", 4).containsEntry("aboutTheWedding", 1)
                .containsEntry("couldNotBeRead", 1).containsEntry("suggestions", 2);
        assertThat((int) shown.get("waiting") + (int) shown.get("read")).as("it still adds up to what was noted").isEqualTo(5);
    }

    @Test
    void aCopyNeedsItsKeyAndAKeyHasTheFormOfOne() {
        annaGets("a-1", "t-1", ID);
        noteAll();
        MessageHandle message = annas("a-1", "t-1");

        assertThatThrownBy(() -> reading.mark(message, new CopyOfOneRead(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reading.mark(message, new CopyOfOneRead())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reading.mark(message, new NotAboutTheWedding(), "<letter-1.Qk7@vendor.example>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reading.mark(message, new NotAboutTheWedding(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stored("a-1")).containsEntry("verdict", null);
    }

    @Test
    void theTableRefusesWhatIsNotWhole() {
        annaGets("a-1", "t-1", ID);
        noteAll();
        for (String update : java.util.List.of(
                "verdict = 'COPY', read_at = now()",
                "verdict = 'COPY', letter_key = 'not-a-key', read_at = now()",
                "verdict = 'COPY', proposals = 1, letter_key = 'l1-" + "A".repeat(43) + "', read_at = now()",
                "verdict = 'COPY', unread_reason = 'GONE', letter_key = 'l1-" + "A".repeat(43) + "', read_at = now()",
                "letter_key = 'l1-" + "A".repeat(43) + "'",
                "verdict = 'NOT_WEDDING', letter_key = 'l1-" + "A".repeat(42) + "', read_at = now()",
                "verdict = 'NOT_WEDDING', letter_key = '<letter-1.Qk7@vendor.example>', read_at = now()",
                "verdict = 'NOT_WEDDING', letter_key = 'l1-" + "A".repeat(43) + "!', read_at = now()")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE mail_message SET " + update + " WHERE message_id = 'a-1'"))
                    .as(update).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("UPDATE mail_message SET verdict = 'COPY', letter_key = 'l1-" + "A".repeat(43) + "', read_at = now() WHERE message_id = 'a-1'");
    }

    @Test
    void theKeyIsKeptAndTheIdentifierIsNot() {
        annaGets("a-1", "t-1", "<Table-Secret-Qx@vendor.example>");
        noteAll();
        reading.mark(annas("a-1", "t-1"), new AboutTheWedding(1), keyOfAnnas("a-1", "t-1"));

        String table = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_message t", String.class));

        assertThat(table).contains("\"letter_key\":\"l1-").doesNotContain("Table-Secret").doesNotContain("Qx@vendor");
    }
}
