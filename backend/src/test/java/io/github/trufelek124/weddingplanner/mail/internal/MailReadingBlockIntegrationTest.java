package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.CouldNotBeRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotAboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Reason;
import io.github.trufelek124.weddingplanner.mail.ReadingReport;
import io.github.trufelek124.weddingplanner.mail.ReadingReport.State;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * What {@code GET /api/mail} says of the reading (S04 section 8): each state the module that reads can be in, the numbers
 * after some verdicts, the cost in whole cents, and that both owners get the same block with nothing of anybody's mail in
 * it. The module that reads is replaced here by a report that a test sets; the real one is tried in the module's own tests.
 */
@Import(MailReadingBlockIntegrationTest.SetByTheTest.class)
class MailReadingBlockIntegrationTest extends MailLettersTestBase {

    /** What the reading says, set by a test. */
    static volatile ReadingReport.Report said = new ReadingReport.Report(State.OFF, 0, 0, 2000);

    @TestConfiguration
    static class SetByTheTest {
        @Bean
        @Primary
        ReadingReport readingSetByTheTest() {
            return () -> said;
        }
    }

    @AfterEach
    void backToQuiet() {
        said = new ReadingReport.Report(State.OFF, 0, 0, 2000);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readingSeenBy(String token) throws Exception {
        return (Map<String, Object>) mail(token).get("reading");
    }

    @Test
    void everyStateIsSaidByItsName() throws Exception {
        for (State state : State.values()) {
            said = new ReadingReport.Report(state, 0, 0, 2000);

            assertThat(readingSeenBy(wedding.anna)).as(state.name()).containsEntry("state", state.name());
        }
    }

    @Test
    void beforeAnythingWasNotedEveryNumberIsThereAndZero() throws Exception {
        connectAnna();

        assertThat(readingSeenBy(wedding.anna)).containsOnly(
                Map.entry("state", "OFF"), Map.entry("waiting", 0), Map.entry("read", 0), Map.entry("aboutTheWedding", 0),
                Map.entry("couldNotBeRead", 0), Map.entry("suggestions", 0), Map.entry("costThisMonthCents", 0),
                Map.entry("costInAllCents", 0), Map.entry("monthlyLimitCents", 2000));
    }

    @Test
    void theNumbersFollowTheVerdictsOfBothMailboxesTogether() throws Exception {
        connectAnna();
        annasBox().deliver("a1", "ta1", daysAgo(3), "INBOX");
        annasBox().deliver("a2", "ta2", daysAgo(2), "INBOX");
        annasBox().deliver("a3", "ta3", daysAgo(1), "INBOX");
        sync.lookAtAll();
        connectPiotr();
        piotrsBox().deliver("p1", "tp1", daysAgo(5), "INBOX");
        piotrsBox().deliver("p2", "tp2", daysAgo(4), "INBOX");
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        assertThat(readingSeenBy(wedding.anna)).containsEntry("waiting", 5).containsEntry("read", 0);

        List<MessageHandle> waiting = reading.waiting(100);
        reading.mark(waiting.get(0), new NotAboutTheWedding());
        reading.mark(waiting.get(1), new AboutTheWedding(3));
        reading.mark(waiting.get(2), new AboutTheWedding(2));
        reading.mark(waiting.get(3), new CouldNotBeRead(Reason.DECLINED));
        said = new ReadingReport.Report(State.READING, 460, 1234, 2000);

        Map<String, Object> block = readingSeenBy(wedding.anna);
        assertThat(block).containsEntry("state", "READING").containsEntry("waiting", 1).containsEntry("read", 4)
                .containsEntry("aboutTheWedding", 2).containsEntry("couldNotBeRead", 1).containsEntry("suggestions", 5);
        assertThat(block).containsEntry("costThisMonthCents", 460).containsEntry("costInAllCents", 1234).containsEntry("monthlyLimitCents", 2000);
    }

    @Test
    void bothOwnersGetTheSameBlockAndItHoldsNothingOfAnybodysMail() throws Exception {
        connectAnna();
        connectPiotr();
        annasBox().deliver("a1", "ta1", daysAgo(1), "INBOX");
        sync.lookAtAll();
        reading.mark(reading.waiting(1).getFirst(), new AboutTheWedding(1));
        said = new ReadingReport.Report(State.MONTHLY_LIMIT, 2000, 2050, 2000);

        HttpResponse<String> annas = http.getAs(wedding.anna, "/api/mail");
        HttpResponse<String> piotrs = http.getAs(wedding.piotr, "/api/mail");

        Map<String, Object> blockAnna = readingSeenBy(wedding.anna);
        assertThat(blockAnna).isEqualTo(readingSeenBy(wedding.piotr));
        assertThat(blockAnna.keySet()).containsExactlyInAnyOrder(
                "state", "waiting", "read", "aboutTheWedding", "couldNotBeRead", "suggestions", "costThisMonthCents", "costInAllCents",
                "monthlyLimitCents");
        // Nothing of an address or of a message in what the partner is told; the one address that is there is the caller's own.
        assertThat(TestHttp.json(piotrs).get("reading").toString()).doesNotContain(ANNA_MAILBOX).doesNotContain(PIOTR_MAILBOX).doesNotContain("a1")
                .doesNotContain("ta1").doesNotContain("@");
        assertThat(piotrs.body()).doesNotContain(ANNA_MAILBOX);
        assertThat(annas.statusCode()).isEqualTo(200);
    }

    @Test
    void theCostIsWholeCentsAndNeverAFraction() throws Exception {
        said = new ReadingReport.Report(State.READING, 1, 99_999_999_999L, 2000);

        HttpResponse<String> response = http.getAs(wedding.anna, "/api/mail");

        assertThat(response.body()).contains("\"costThisMonthCents\":1,").contains("\"costInAllCents\":99999999999,").contains("\"monthlyLimitCents\":2000");
    }
}
