package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** When Google refuses or the permission is gone: who is left alone, for how long, and what survives a restart. */
class MailSyncRefusalsIntegrationTest extends MailSyncTestBase {

    UUID anna;
    Mailbox box;

    @BeforeEach
    void annaIsConnectedToAMailboxWithOneMessage() throws Exception {
        anna = anna();
        box = annasBox();
        connectAnna();
        box.deliver("m-1", "thread-1", daysAgo(1), "INBOX");
        // Connecting notes nothing of what is in the mailbox (D-009): Anna has asked for the last ninety days.
        lookBack(anna, 90);
    }

    private static final Reply TOO_MANY = Reply.json(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}");

    private void assertPutOffFor(Duration expected) {
        Duration actual = Duration.between(TestClock.now(), putOffUntil(anna));
        assertThat(actual).isBetween(expected.minusSeconds(10), expected.plusSeconds(1));
    }

    @Test
    void tooManyRequestsPutTheMailboxOffForFiveMinutesAndThenForLongerAndLongerUpToAnHour() {
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);

        sync.lookAtAll();
        assertThat(refusals(anna)).isEqualTo(1);
        assertPutOffFor(Duration.ofMinutes(5));
        assertThat(noted(anna)).isEmpty();
        assertThat(marker(anna)).isNull();

        for (long minutes : new long[] {10, 20, 40, 60, 60}) {
            // Not before its time: nothing is asked of Google.
            TestClock.advance(Duration.ofMinutes(4));
            int calls = google.calls().size();
            assertThat(sync.lookAtAll()).isZero();
            assertThat(google.calls()).hasSize(calls);
            // Then its turn comes, and Google still refuses.
            TestClock.advance(Duration.between(TestClock.now(), putOffUntil(anna)).plusSeconds(1));
            assertThat(sync.lookAtAll()).isEqualTo(1);
            assertPutOffFor(Duration.ofMinutes(minutes));
        }
        assertThat(refusals(anna)).isEqualTo(6);
    }

    @Test
    void googlesOwnFailuresPutTheMailboxOffToo() {
        for (int status : new int[] {500, 502, 503, 504}) {
            google.answerEveryGmailCallOfWith(ANNA_MAILBOX, Reply.json(status, "{\"error\":{\"code\":" + status + "}}"));
            int before = refusals(anna);
            Instant until = putOffUntil(anna);
            if (until != null) {
                TestClock.advance(Duration.between(TestClock.now(), until).plusSeconds(1));
            }

            sync.lookAtAll();

            assertThat(refusals(anna)).as("after a " + status).isEqualTo(before + 1);
            assertThat(putOffUntil(anna)).isAfter(TestClock.now());
        }
    }

    @Test
    void aRateLimitAnsweredAs403IsAlsoTooManyRequests() {
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, Reply.json(403,
                "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));

        sync.lookAtAll();

        assertThat(refusals(anna)).isEqualTo(1);
        assertThat(state(anna)).isEqualTo("CONNECTED");
    }

    @Test
    void theTimeToWaitIsKeptInTheDatabaseSoARestartDoesNotForgetIt() {
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);
        sync.lookAtAll();
        sync.lookAtAll();
        assertThat(refusals(anna)).as("the second look was not made").isEqualTo(1);
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        int calls = google.calls().size();

        // The server is restarted: a new service object, with only the database to go by.
        MailSync restarted = new MailSync(setup, connections, messageRepository, source, clock, new org.springframework.core.task.SyncTaskExecutor(), false);

        assertThat(restarted.lookAtAll()).as("still put off").isZero();
        assertThat(google.calls()).hasSize(calls);
        assertThat(refusals(anna)).isEqualTo(1);

        TestClock.advance(Duration.ofMinutes(5).plusSeconds(5));
        assertThat(restarted.lookAtAll()).isEqualTo(1);
        assertThat(noted(anna)).containsExactly("m-1");
    }

    @Test
    void aCompleteLookForgetsTheRefusalsInARow() {
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);
        sync.lookAtAll();
        TestClock.advance(Duration.ofMinutes(6));
        sync.lookAtAll();
        assertThat(refusals(anna)).isEqualTo(2);
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        TestClock.advance(Duration.ofMinutes(11));

        sync.lookAtAll();

        assertThat(refusals(anna)).isZero();
        assertThat(putOffUntil(anna)).isNull();
        assertThat(lastLook(anna)).isNotNull();
        // A refusal after that starts again at five minutes.
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        assertPutOffFor(Duration.ofMinutes(5));
    }

    @Test
    void aMailboxThatIsPutOffDoesNotStopTheOther() throws Exception {
        connectPiotr();
        piotrsBox().deliver("m-piotr", "thread-piotr", daysAgo(1), "INBOX");
        lookBack(piotr(), 90);
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);

        assertThat(sync.lookAtAll()).isEqualTo(2);

        assertThat(refusals(anna)).isEqualTo(1);
        assertThat(noted(piotr())).containsExactly("m-piotr");
        assertThat(refusals(piotr())).isZero();
        assertThat(lastLook(piotr())).isNotNull();
        // Next time only Piotr's is due.
        assertThat(sync.lookAtAll()).isEqualTo(1);
    }

    @Test
    void aCutOffIsNoRefusalAndTheMailboxIsLookedAtAgainNextTime() {
        google.answerNextCallsToWith(PROFILE, Reply.slow(2_600));

        sync.lookAtAll();

        assertThat(refusals(anna)).isZero();
        assertThat(putOffUntil(anna)).isNull();
        assertThat(sync.lookAtAll()).isEqualTo(1);
        assertThat(noted(anna)).containsExactly("m-1");
    }

    // ---------------------------------------------------------------- the access token

    private int refreshes() {
        return (int) google.calls("/token").stream().filter(call -> "refresh_token".equals(call.form().get("grant_type"))).count();
    }

    @Test
    void aRefusedAccessTokenIsAskedForOnceMoreAndTheCallTriedOnce() {
        google.answerNextCallsToWith(PROFILE, Reply.json(401, "{\"error\":{\"code\":401}}"));

        sync.lookAtAll();

        assertThat(refreshes()).as("the first token, and a new one after the 401").isEqualTo(2);
        assertThat(noted(anna)).containsExactly("m-1");
        assertThat(refusals(anna)).isZero();
        assertThat(state(anna)).isEqualTo("CONNECTED");
    }

    @Test
    void aTokenRefusedTwiceIsNotTriedAgainAndPutsTheMailboxOff() {
        google.answerNextCallsToWith(PROFILE, Reply.json(401, "{}"), Reply.json(401, "{}"));
        int profiles = google.count(PROFILE);

        sync.lookAtAll();

        assertThat(google.count(PROFILE) - profiles).as("one try, one more try, no third").isEqualTo(2);
        assertThat(refusals(anna)).isEqualTo(1);
        assertThat(noted(anna)).isEmpty();
        assertThat(state(anna)).as("the permission itself is not in question").isEqualTo("CONNECTED");
    }

    // ---------------------------------------------------------------- the permission is gone

    @Test
    void whenThePermissionIsGoneTheConnectionNeedsConnectingAgainAndTheSyncLeavesItAlone() throws Exception {
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));
        int gmail = gmailCalls();

        assertThat(sync.lookAtAll()).isEqualTo(1);

        assertThat(state(anna)).isEqualTo("NEEDS_RECONNECTING");
        assertThat(gmailCalls()).as("nothing was asked of the mailbox").isEqualTo(gmail);
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NEEDS_RECONNECTING");
        assertThat(partner(mail(wedding.piotr))).containsEntry("state", "NEEDS_RECONNECTING");
        assertThat(refusals(anna)).as("not a refusal to wait out").isZero();

        // From now on it is not looked at, and Google is not asked anything at all.
        int calls = google.calls().size();
        TestClock.advance(Duration.ofMinutes(10));
        assertThat(sync.lookAtAll()).isZero();
        assertThat(google.calls()).hasSize(calls);

        // Connecting again makes it a mailbox to look at once more.
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        assertThat(sync.lookAtAll()).isEqualTo(1);
        assertThat(noted(anna)).containsExactly("m-1");
        assertThat(state(anna)).isEqualTo("CONNECTED");
    }

    @Test
    void thePermissionWithdrawnWhileTheTokenWasStillGoodEndsTheNextLook() throws Exception {
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-1");
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));
        // The access token in memory runs out; the next one cannot be had.
        TestClock.advance(Duration.ofHours(2));
        box.deliver("m-2", "thread-2", TestClock.now(), "INBOX");

        sync.lookAtAll();

        assertThat(state(anna)).isEqualTo("NEEDS_RECONNECTING");
        assertThat(noted(anna)).containsExactly("m-1");
        assertThat(marker(anna)).as("the look did not count").isNotEqualTo(box.marker());
    }

    @Test
    void aConnectionThatNeedsConnectingAgainDoesNotStopTheOtherMailbox() throws Exception {
        connectPiotr();
        piotrsBox().deliver("m-piotr", "thread-piotr", daysAgo(1), "INBOX");
        lookBack(piotr(), 90);
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));

        sync.lookAtAll();

        assertThat(state(anna)).isEqualTo("NEEDS_RECONNECTING");
        assertThat(noted(piotr())).containsExactly("m-piotr");
    }
}
