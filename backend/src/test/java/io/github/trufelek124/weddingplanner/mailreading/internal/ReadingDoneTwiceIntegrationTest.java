package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Call;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A message that is done twice proposes nothing twice, and the model is told what was proposed from the thread, not from itself. */
class ReadingDoneTwiceIntegrationTest extends RoundTestBase {

    static final String TEXT = "Dzień dobry, potwierdzamy termin degustacji. Prosimy o wybór menu do końca tygodnia. Pozdrawiamy.";
    static final String PASSAGE = "Prosimy o wybór menu do końca tygodnia.";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private Call lastExtraction() {
        List<Call> calls = service.calls().stream().filter(call -> "claude-opus-5-5".equals(call.json().get("model"))).toList();
        return calls.get(calls.size() - 1);
    }

    @Test
    void theServerStoppingBetweenHandingInAndMarkingProposesNothingTwiceDoesNotTellTheModelItsOwnTitlesAndMarks() throws Exception {
        annaGets("m-1", "Degustacja", TEXT);
        note();
        WatchedMail watched = new WatchedMail(mail);
        watched.marksToFail = 1;
        ReadingRound mine = roundOf(rules(), watched);
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(
                proposal("TASK", "Choose-the-menu-Qz", PASSAGE), proposal("TASK", "Phone-the-caterer-Qz", PASSAGE)));

        assertThat(mine.round()).as("the marking failed: nothing was read").isZero();

        assertThat(isRead("m-1")).isFalse();
        assertThat(suggestionRows()).isEqualTo(2);
        // The server is started again: nothing is remembered. The message is read again from the start.
        mine.forget();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(
                proposal("TASK", "Choose-the-menu-Qz", PASSAGE), proposal("TASK", "Phone-the-caterer-Qz", PASSAGE)));

        assertThat(mine.round()).isEqualTo(1);

        assertThat(suggestionRows()).as("nothing was proposed twice").isEqualTo(2);
        assertThat(verdictOf("m-1")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 2);
        assertThat(lastExtraction().body()).as("the message's own proposals are not told to the model").doesNotContain("Qz");
        assertThat(watched.marked).hasSize(1);
    }

    @Test
    void theTitlesAlreadyProposedFromTheThreadReachTheExtractionOfALaterMessageOfTheSameThreadAndThoseOfAnotherThreadDoNot() throws Exception {
        annaGets("m-first", "thread-menu", 3, "Degustacja", TEXT);
        annaGets("m-elsewhere", "thread-flowers", 3, "Kwiaty", TEXT);
        note();
        service.answerNextCallsWith(
                triageSays(true, 90), extractionSays(proposal("TASK", "Flowers-Title-Qz", PASSAGE)),
                triageSays(true, 90), extractionSays(proposal("TASK", "Menu-Title-Qz", PASSAGE)));
        assertThat(round.round()).isEqualTo(2);
        assertThat(suggestionRows()).isEqualTo(2);

        // A reply arrives in the thread of the menu, and one in a third thread.
        annaGets("m-reply", "thread-menu", 1, "Re: Degustacja", "Dziękujemy za odpowiedź. " + TEXT);
        annaGets("m-third", "thread-third", 1, "Inne", TEXT);
        TestClockAdvance.minutes(10);
        note();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(), triageSays(true, 90), extractionSays());

        assertThat(round.round()).isEqualTo(2);

        List<Call> extractions = service.calls().stream().filter(call -> "claude-opus-5-5".equals(call.json().get("model"))).toList();
        assertThat(extractions).hasSize(2);
        Call menuReply = extractions.stream().filter(call -> call.body().contains("Dziękujemy za odpowiedź")).findFirst().orElseThrow();
        assertThat(menuReply.body()).contains("Menu-Title-Qz").doesNotContain("Flowers-Title-Qz");
        Call third = extractions.stream().filter(call -> !call.body().contains("Dziękujemy za odpowiedź")).findFirst().orElseThrow();
        assertThat(third.body()).doesNotContain("Menu-Title-Qz").doesNotContain("Flowers-Title-Qz");
    }

    @Test
    void theKeysOfProposalsAreThreadMessageAndNumberInTheOrderTheModelAnswered() throws Exception {
        annaGets("m-1", "Degustacja", TEXT);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Second-in-title-Qz", PASSAGE), proposal("TASK", "A-Qz", PASSAGE)));
        round.round();

        MessageHandle handle = new MessageHandle(weddingId(), anna(), "m-1", "thread-m-1");
        var keys = mail.keysOf(handle);
        List<String> found = suggestions.proposedUnder(weddingId(), keys.thread() + "/", 10).stream().map(Suggestions.Proposed::sourceKey).sorted().toList();

        assertThat(found).containsExactly(keys.thread() + "/" + keys.message() + "/1", keys.thread() + "/" + keys.message() + "/2");
        assertThat(jdbc.queryForObject("SELECT title FROM suggestion WHERE source_key = ?", String.class, keys.thread() + "/" + keys.message() + "/1"))
                .isEqualTo("Second-in-title-Qz");
    }
}
