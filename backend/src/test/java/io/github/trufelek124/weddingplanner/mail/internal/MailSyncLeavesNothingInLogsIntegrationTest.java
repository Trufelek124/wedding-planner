package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * The sync says that a look happened, for which member, how many messages it noted, how long it took and when a
 * mailbox was put off. After looks of every kind, with the most talkative logging, the log holds no address, no token,
 * no marker and no identifier of a message or of its thread.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.root=TRACE",
    "logging.level.org.springframework.test=INFO",
    "logging.level.com.zaxxer=INFO",
    "logging.level.io.zonky=INFO",
    "logging.level.org.postgresql=INFO",
    "logging.level.org.flywaydb=INFO",
    "logging.level.sun=INFO",
    // Google's side of the conversation is the test's own stand-in, which writes the request lines it is asked: not the planner.
    "logging.level.com.sun.net.httpserver=INFO",
    "logging.level.jdk=INFO",
    "logging.level.io.netty=INFO"
})
class MailSyncLeavesNothingInLogsIntegrationTest extends MailSyncTestBase {

    @Test
    void nothingOfAMailboxOrAMessageReachesTheLog(CapturedOutput output) throws Exception {
        connectAnna();
        connectPiotr();
        annasBox().markerIs(7_391_000);
        piotrsBox().markerIs(8_472_000);
        List<String> secrets = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            annasBox().deliver("msg-anna-" + i + "-zq", "thr-anna-" + i + "-zq", daysAgo(i), "INBOX", "Label_Wedding_Secret");
        }
        piotrsBox().deliver("msg-piotr-1-zq", "thr-piotr-1-zq", daysAgo(1), "INBOX");
        annasBox().pageSize(2);

        // The first look of both, then a later one, one that is cut off, and a marker that has grown too old.
        assertThat(http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":90}").statusCode()).isEqualTo(200);
        noteEverything(piotr());
        secrets.add(marker(anna()));
        secrets.add(marker(piotr()));
        TestClock.advance(Duration.ofMinutes(5));
        annasBox().deliver("msg-anna-6-zq", "thr-anna-6-zq", hoursAgo(1), "INBOX");
        sync.lookAtAll();
        secrets.add(marker(anna()));
        annasBox().deliver("msg-anna-7-zq", "thr-anna-7-zq", hoursAgo(1), "INBOX");
        annasBox().deliver("msg-anna-8-zq", "thr-anna-8-zq", hoursAgo(1), "INBOX");
        annasBox().deliver("msg-anna-9-zq", "thr-anna-9-zq", hoursAgo(1), "INBOX");
        google.answerNextCallsToWith(HISTORY, Reply.normally(), Reply.slow(2_600));
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        annasBox().forgetHistory();
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        secrets.add(marker(anna()));

        // Google refusing, a token refused, and a permission withdrawn.
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, Reply.json(503, "{\"error\":{\"message\":\"msg-anna-1-zq\"}}"));
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        google.answerNextCallsToWith(PROFILE, Reply.json(401, "{}"));
        TestClock.advance(Duration.ofHours(1));
        sync.lookAtAll();
        google.withdraw(google.refreshTokenOf(PIOTR_MAILBOX));
        TestClock.advance(Duration.ofHours(2));
        sync.lookAtAll();
        assertThat(state(piotr())).isEqualTo("NEEDS_RECONNECTING");

        for (int i = 1; i <= 9; i++) {
            secrets.add("msg-anna-" + i + "-zq");
            secrets.add("thr-anna-" + i + "-zq");
        }
        secrets.addAll(List.of("msg-piotr-1-zq", "thr-piotr-1-zq", "Label_Wedding_Secret", ANNA_MAILBOX, PIOTR_MAILBOX));
        secrets.addAll(google.issuedSecrets());

        String log = output.getAll();
        assertThat(log).as("the log was captured, and the looks are in it")
                .contains("Looked at the mailbox of member " + anna())
                .contains("Member " + anna() + " asked to look back 90 days")
                .contains("The look back of member " + anna() + " over 90 days is done: 5 messages noted")
                .contains("is put off until")
                .contains("has to be connected again");
        for (String secret : secrets) {
            assertThat(log).as("a secret of " + secret.length() + " characters starting " + secret.substring(0, Math.min(6, secret.length())))
                    .doesNotContain(secret);
        }
        assertThat(log).doesNotContain("planner.example").doesNotContain("test-access-not-real").doesNotContain("test-refresh-not-real");
        assertThat(secrets.subList(0, 4)).as("the markers were real ones").allMatch(marker -> marker.matches("[78]\\d{6}"));
    }
}
