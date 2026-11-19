package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.CopyOfOneRead;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * One letter in both mailboxes is read once (S04 section 2): a mail that went to both owners is two messages for Google and
 * one letter; the copy that is reached second is sent nowhere and proposes nothing. Made-up mail, some of it in Polish and
 * Ukrainian; the AI service is the stand-in, which counts every call it gets.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReadingOneLetterOnceIntegrationTest extends RoundTestBase {

    static final String LETTER = "<2026.11.reservation.Qk7@palace-on-the-hill.example>";
    static final String ANNA_FIRST = "anna-first";
    static final String PIOTR_FIRST = "piotr-first";

    @BeforeEach
    void bothAreConnected() throws Exception {
        connectAnna();
        connectPiotr();
    }

    private void annaGetsIt(String id, String header) {
        gets(ANNA_MAILBOX, id, "thread-" + id, 2, JAN, "Rezerwacja sali", POLISH, header);
    }

    private void piotrGetsIt(String id, String header) {
        gets(PIOTR_MAILBOX, id, "thread-" + id, 2, JAN, "Rezerwacja sali", POLISH, header);
    }

    /** The mailbox noted second is read first (newest look first): it is "the first copy". Returns who that is. */
    private UUID bothCopiesNoted(String whoIsNotedFirst, String annasHeader, String piotrsHeader) {
        if (whoIsNotedFirst.equals(ANNA_FIRST)) {
            annaGetsIt("m-anna", annasHeader);
            note();
            piotrGetsIt("m-piotr", piotrsHeader);
            note();
            return piotr();
        }
        piotrGetsIt("m-piotr", piotrsHeader);
        note();
        annaGetsIt("m-anna", annasHeader);
        note();
        return anna();
    }

    private String idOf(UUID member) {
        return member.equals(anna()) ? "m-anna" : "m-piotr";
    }

    private String idOfTheOther(UUID member) {
        return member.equals(anna()) ? "m-piotr" : "m-anna";
    }

    private void aboutTheWeddingWithOneProposal() {
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("PAYMENT", "Wpłacić zaliczkę-Qz", DEPOSIT_PASSAGE)));
    }

    private void theCopyWasNotSentAnywhere(UUID first) {
        String copy = idOfTheOther(first);
        assertThat(verdictOf(idOf(first))).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
        assertThat(verdictOf(copy)).containsEntry("verdict", "COPY").containsEntry("unread_reason", null).containsEntry("proposals", null);
        assertThat(service.calls()).as("the AI service saw one letter, not two").hasSize(2);
        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
        assertThat(suggestionRows()).as("nothing was proposed twice").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT inbox_member_id FROM suggestion", UUID.class)).as("it stands under the first copy's inbox").isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = ?", String.class, copy))
                .isEqualTo(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = ?", String.class, idOf(first)));
    }

    // ---------------------------------------------------------------- the same letter

    private void theSameLetterIsReadOnce(String whoIsNotedFirst) throws Exception {
        UUID first = bothCopiesNoted(whoIsNotedFirst, LETTER, LETTER);
        aboutTheWeddingWithOneProposal();

        assertThat(round.round()).as("both are read in this round, the copy as read").isEqualTo(2);

        theCopyWasNotSentAnywhere(first);
        assertThat(mail.counts(weddingId())).extracting("read", "aboutTheWedding", "couldNotBeRead", "copies", "waiting")
                .containsExactly(2, 1, 0, 1, 0);
    }

    @Test
    void theSameLetterInBothMailboxesIsReadOnceWhenPiotrsCopyIsReachedFirst() throws Exception {
        theSameLetterIsReadOnce(ANNA_FIRST);
    }

    @Test
    void theSameLetterInBothMailboxesIsReadOnceWhenAnnasCopyIsReachedFirst() throws Exception {
        theSameLetterIsReadOnce(PIOTR_FIRST);
    }

    @Test
    void theCopyNeedsNoLinkAndAsksNothingOfAnybodyButTheLetterItself() throws Exception {
        annaGetsIt("m-anna", LETTER);
        note();
        aboutTheWeddingWithOneProposal();
        assertThat(round.round()).isEqualTo(1);
        piotrGetsIt("m-piotr", LETTER);
        note();
        WatchedMail watched = new WatchedMail(mail);
        // A link that cannot be made leaves any other message as it is (see ReadingOutcomesIntegrationTest): the copy needs none.
        watched.linkFails = new IllegalStateException("no link is to be made for a copy");
        int callsBefore = service.calls().size();

        assertThat(roundOf(rules(), watched).round()).isEqualTo(1);

        assertThat(verdictOf("m-piotr")).containsEntry("verdict", "COPY");
        assertThat(watched.marked).hasSize(1).hasOnlyElementsOfType(CopyOfOneRead.class);
        assertThat(service.calls()).as("nothing was asked of the AI service for it").hasSize(callsBefore);
        assertThat(suggestionRows()).isEqualTo(1);
    }

    @Test
    void theFirstCopyWasNotAboutTheWeddingAndTheSecondIsACopyAllTheSame() throws Exception {
        UUID first = bothCopiesNoted(ANNA_FIRST, LETTER, LETTER);
        service.answerNextCallsWith(triageSays(false, 95));

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf(idOf(first))).containsEntry("verdict", "NOT_WEDDING");
        assertThat(verdictOf(idOfTheOther(first))).containsEntry("verdict", "COPY");
        assertThat(service.calls()).hasSize(1);
        assertThat(suggestionRows()).isZero();
    }

    @Test
    void theFirstCopyCouldNotBeReadAndTheSecondIsACopyAllTheSame() throws Exception {
        UUID first = bothCopiesNoted(PIOTR_FIRST, LETTER, LETTER);
        service.answerNextCallsWith(Reply.refusal());

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf(idOf(first))).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "DECLINED");
        assertThat(verdictOf(idOfTheOther(first))).containsEntry("verdict", "COPY");
        assertThat(service.calls()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT letter_key FROM mail_message WHERE message_id = ?", String.class, idOf(first)))
                .as("the key is kept also with a verdict of a message that could not be read").startsWith("l1-");
    }

    @Test
    void aCopyReachedInALaterRoundIsAlsoACopy() throws Exception {
        annaGetsIt("m-anna", LETTER);
        note();
        aboutTheWeddingWithOneProposal();
        assertThat(round.round()).isEqualTo(1);
        TestClockAdvance.days(3);
        piotrGetsIt("m-piotr", LETTER);
        note();

        assertThat(round.round()).isEqualTo(1);

        assertThat(verdictOf("m-piotr")).containsEntry("verdict", "COPY");
        assertThat(service.calls()).hasSize(2);
        assertThat(suggestionRows()).isEqualTo(1);
    }

    @Test
    void theAnglesAndSpacesAroundTheIdentifierDoNotCount() throws Exception {
        UUID first = bothCopiesNoted(ANNA_FIRST, LETTER, "   2026.11.reservation.Qk7@palace-on-the-hill.example  ");
        aboutTheWeddingWithOneProposal();

        assertThat(round.round()).isEqualTo(2);

        theCopyWasNotSentAnywhere(first);
    }

    @Test
    void aDifferentCaseIsAnotherLetter() throws Exception {
        bothCopiesNoted(ANNA_FIRST, LETTER, "<2026.11.RESERVATION.Qk7@palace-on-the-hill.example>");
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(), triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf("m-anna")).containsEntry("verdict", "WEDDING");
        assertThat(verdictOf("m-piotr")).containsEntry("verdict", "WEDDING");
        assertThat(service.calls()).as("each was read").hasSize(4);
    }

    // ---------------------------------------------------------------- not the same letter

    @Test
    void twoDifferentLettersOfOneThreadAreBothRead() throws Exception {
        gets(ANNA_MAILBOX, "m-one", "thread-same", 3, JAN, "Rezerwacja sali", POLISH, "<one.Qk7@palace-on-the-hill.example>");
        gets(ANNA_MAILBOX, "m-two", "thread-same", 2, JAN, "Re: Rezerwacja sali", POLISH, "<two.Qk7@palace-on-the-hill.example>");
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(), triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf("m-one")).containsEntry("verdict", "WEDDING");
        assertThat(verdictOf("m-two")).containsEntry("verdict", "WEDDING");
        assertThat(service.calls()).hasSize(4);
    }

    @Test
    void lettersWithoutTheHeaderOrWithABlankOneAreNeverCopiesOfEachOther() throws Exception {
        annaGetsIt("m-anna", null);
        piotrGetsIt("m-piotr", null);
        gets(ANNA_MAILBOX, "m-blank-a", "thread-ba", 2, JAN, "Rezerwacja sali", POLISH, "  ");
        gets(PIOTR_MAILBOX, "m-blank-p", "thread-bp", 2, JAN, "Rezerwacja sali", POLISH, "<>");
        note();
        service.answerEveryCallWith(Reply.says(Answers.triage(true, 90)));
        // Every triage says yes; the extraction's answer is the same text, which is not a proposal list: unusable, but read.

        round.round();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict = 'COPY'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE letter_key IS NOT NULL", Integer.class))
                .as("no header, no key").isZero();
        assertThat(triageCalls()).as("each of the four was triaged").isEqualTo(4);
    }

    @Test
    void aSecondMessageWithTheSameHeaderInTheSameMailboxIsACopy() throws Exception {
        gets(ANNA_MAILBOX, "m-one", "thread-1", 3, JAN, "Rezerwacja sali", POLISH, LETTER);
        gets(ANNA_MAILBOX, "m-two", "thread-2", 2, JAN, "Rezerwacja sali (ponownie)", POLISH, LETTER);
        note();
        aboutTheWeddingWithOneProposal();

        assertThat(round.round()).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict = 'COPY'", Integer.class)).isEqualTo(1);
        assertThat(service.calls()).hasSize(2);
        assertThat(suggestionRows()).isEqualTo(1);
    }

    @Test
    void aCopyInUkrainianIsACopyToo() throws Exception {
        String text = "Вітаю! Підтверджую бронювання зали на 19 червня 2027. Просимо внести завдаток до 1 грудня 2026.";
        gets(ANNA_MAILBOX, "m-anna", "thread-a", 2, JAN, "Бронювання зали", text, "<ua.Qk7@palace-on-the-hill.example>");
        note();
        gets(PIOTR_MAILBOX, "m-piotr", "thread-p", 2, JAN, "Бронювання зали", text, "<ua.Qk7@palace-on-the-hill.example>");
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf("m-piotr")).containsEntry("verdict", "WEDDING");
        assertThat(verdictOf("m-anna")).containsEntry("verdict", "COPY");
        assertThat(service.calls()).hasSize(2);
    }

    // ---------------------------------------------------------------- what the copy costs, shows and logs

    @Test
    void aCopyCountsAsReadInTheRoundAndAsNothingElseOnTheEmailPage() throws Exception {
        bothCopiesNoted(PIOTR_FIRST, LETTER, LETTER);
        aboutTheWeddingWithOneProposal();

        assertThat(round.round()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> shown = (Map<String, Object>) io.github.trufelek124.weddingplanner.TestHttp
                .json(http.getAs(wedding.anna, "/api/mail")).get("reading");
        assertThat(shown).containsEntry("waiting", 0).containsEntry("read", 2).containsEntry("aboutTheWedding", 1)
                .containsEntry("couldNotBeRead", 0).containsEntry("suggestions", 1);
        assertThat(triageCalls()).as("the copy was not triaged").isEqualTo(1);
    }

    @Test
    void theLogSaysThatTheCopyWasAndNamesTheMemberById(CapturedOutput output) throws Exception {
        UUID first = bothCopiesNoted(ANNA_FIRST, LETTER, LETTER);
        aboutTheWeddingWithOneProposal();

        round.round();

        UUID copyOwner = first.equals(anna()) ? piotr() : anna();
        assertThat(output.getAll()).contains("Read a message of member " + copyOwner + ": a copy of a letter that was read already");
        assertThat(output.getAll()).doesNotContain("Qk7").doesNotContain("palace-on-the-hill").doesNotContain("reservation");
    }

    @Test
    void theCopyIsMarkedWithTheCopyVerdict() throws Exception {
        bothCopiesNoted(ANNA_FIRST, LETTER, LETTER);
        aboutTheWeddingWithOneProposal();
        WatchedMail watched = new WatchedMail(mail);

        assertThat(roundOf(rules(), watched).round()).isEqualTo(2);

        assertThat(watched.marked).hasSize(2).hasAtLeastOneElementOfType(CopyOfOneRead.class);
    }
}
