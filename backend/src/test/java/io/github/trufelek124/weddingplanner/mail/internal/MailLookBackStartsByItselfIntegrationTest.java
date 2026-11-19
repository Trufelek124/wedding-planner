package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * The server as it runs for real: the sync runs by itself (here only its wake-up matters: the five-minute clock is far
 * off). Asking to look back starts a round within moments, once what was asked is stored, and asking while a round runs
 * does not start a second one at the same time but is done by the one that runs. Google is the stand-in.
 */
@TestPropertySource(properties = "app.mail.sync-automatic=true")
class MailLookBackStartsByItselfIntegrationTest extends MailSyncTestBase {

    private HttpResponse<String> ask(int days) throws Exception {
        return http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":" + days + "}");
    }

    private void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
        long waited = System.nanoTime();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - waited).as("waited for " + what).isLessThan(Duration.ofSeconds(20).toNanos());
            Thread.sleep(25);
        }
    }

    private boolean done(UUID owner) {
        return jdbc.queryForObject("SELECT look_back_done_at IS NOT NULL FROM mail_connection WHERE member_id = ?", Boolean.class, owner);
    }

    @Test
    void askingStartsALookWithinMomentsWithoutAnyoneCallingTheSync() throws Exception {
        UUID anna = anna();
        annasBox().markerIs(7_391_000);
        connectAnna();
        annasBox().deliver("m-1", "thread-1", daysAgo(2), "INBOX");
        annasBox().deliver("m-2", "thread-2", daysAgo(3), "INBOX");
        assertThat(noted(anna)).as("connecting noted nothing").isEmpty();

        assertThat(ask(30).statusCode()).isEqualTo(200);

        waitFor(() -> done(anna), "the look back");
        assertThat(noted(anna)).containsExactly("m-1", "m-2");
        assertThat(yours(mail(wedding.anna)).get("lookBack")).extracting("state", "found").containsExactly("DONE", 2);
        assertThat(marker(anna)).isEqualTo(annasBox().marker());
    }

    @Test
    void askingWhileARoundIsRunningDoesNotStartASecondAndTheRunningOneDoesTheNewAsk() throws Exception {
        UUID anna = anna();
        annasBox().markerIs(7_391_000);
        connectAnna();
        annasBox().deliver("m-50d", "thread-50d", daysAgo(50), "INBOX");
        annasBox().deliver("m-3d", "thread-3d", daysAgo(3), "INBOX");
        google.answerNextCallsToWith(MESSAGES, Reply.slow(1_000));

        assertThat(ask(7).statusCode()).isEqualTo(200);
        waitFor(() -> google.count(MESSAGES) >= 1, "the first round to be listing");
        assertThat(sync.lookAtAll()).as("a round is running: none is started a second time").isZero();
        assertThat(ask(90).statusCode()).isEqualTo(200);

        waitFor(() -> done(anna) && "90".equals(String.valueOf(daysOf(anna))), "the second ask to be done");
        assertThat(noted(anna)).containsExactly("m-3d", "m-50d");
        List<Integer> found = jdbc.queryForList("SELECT look_back_found FROM mail_connection WHERE member_id = ?", Integer.class, anna);
        assertThat(found).as("the replaced ask stored nothing: every message counted once").containsExactly(2);
    }

    private Integer daysOf(UUID owner) {
        return jdbc.queryForObject("SELECT look_back_days FROM mail_connection WHERE member_id = ?", Integer.class, owner);
    }
}
