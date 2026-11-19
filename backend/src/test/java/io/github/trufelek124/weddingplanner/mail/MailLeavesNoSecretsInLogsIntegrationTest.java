package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * A mailbox's tokens and its address are the key to somebody's mail. With the most talkative logging, a full round of
 * connecting, looking, being refused, failing and disconnecting must leave none of them in the log: not the code, the
 * state, the cookie, either token, the client's secret, the key, nor the mailbox's address. At TRACE the web framework
 * prints what every endpoint is called with, and the request line with its query, which is where the return address
 * would give them away.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.root=TRACE",
    // The test framework prints the settings the test was given when it keeps its context (a test's own business).
    "logging.level.org.springframework.test=INFO",
    // Not the pool's, the database driver's and the embedded server's own chatter about themselves.
    "logging.level.com.zaxxer=INFO",
    "logging.level.io.zonky=INFO",
    "logging.level.org.postgresql=INFO",
    "logging.level.org.flywaydb=INFO",
    "logging.level.sun=INFO",
    "logging.level.jdk=INFO",
    "logging.level.io.netty=INFO"
})
class MailLeavesNoSecretsInLogsIntegrationTest extends MailTestBase {

    private static final String KEY_AS_GIVEN = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void nothingSecretOrPersonalReachesTheLog(CapturedOutput output) throws Exception {
        List<String> secrets = new ArrayList<>();

        // A full connection, then looking at it.
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        secrets.addAll(List.of(attempt.state(), attempt.cookie(), code));
        assertThat(location(comeBack(attempt, code))).isEqualTo(YES);
        mail(wedding.anna);
        mail(wedding.piotr);

        // Another owner's, and a replacement with another mailbox.
        Attempt piotrs = start(wedding.piotr);
        String piotrsCode = google.consent(piotrs.url(), PIOTR_MAILBOX);
        secrets.addAll(List.of(piotrs.state(), piotrs.cookie(), piotrsCode));
        assertThat(location(comeBack(piotrs, piotrsCode))).isEqualTo(YES);
        Attempt again = start(wedding.anna);
        String againCode = google.consent(again.url(), "anna.other@planner.example");
        secrets.addAll(List.of(again.state(), again.cookie(), againCode));
        assertThat(location(comeBack(again, againCode))).isEqualTo(YES);

        // A person saying no, a replay, a return with nothing, one from another browser, and Google failing.
        Attempt refused = start(wedding.anna);
        secrets.addAll(List.of(refused.state(), refused.cookie()));
        assertThat(location(http.get(RETURN_PATH + "?state=" + refused.state() + "&error=access_denied",
                Map.of("Cookie", COOKIE + "=" + refused.cookie())))).isEqualTo(REFUSED);
        assertThat(location(comeBack(again, againCode))).isEqualTo(FAILED);
        assertThat(location(comeBack((String) null, null, null))).isEqualTo(FAILED);
        Attempt failing = start(wedding.anna);
        String failingCode = google.consent(failing.url(), ANNA_MAILBOX);
        secrets.addAll(List.of(failing.state(), failing.cookie(), failingCode));
        google.answerNextTokenCallWith(Reply.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}"));
        assertThat(location(comeBack(failing, failingCode))).isEqualTo(FAILED);

        // Disconnecting, with Google up and then down.
        HttpResponse<String> disconnect = http.deleteAs(wedding.anna, "/api/mail/gmail/connection");
        assertThat(disconnect.statusCode()).isEqualTo(204);
        google.answerEveryRevokeCallWith(Reply.dropped());
        assertThat(http.deleteAs(wedding.piotr, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        secrets.addAll(google.issuedSecrets());
        secrets.addAll(google.verifiersSeen());
        secrets.addAll(List.of(StandInGoogle.CLIENT_SECRET, KEY_AS_GIVEN, ANNA_MAILBOX, PIOTR_MAILBOX, "anna.other@planner.example"));
        // What Google answered at the profile address: the mailbox is a person's, its address is private.
        String log = output.getAll();
        assertThat(log).as("the log was captured, and holds what the endpoints were called with")
                .contains("/api/mail/gmail/return")
                .contains("/api/mail/gmail/connection");
        for (String secret : secrets) {
            assertThat(log).as("a secret of " + secret.length() + " characters starting " + secret.substring(0, Math.min(6, secret.length())))
                    .doesNotContain(secret);
        }
        assertThat(log)
                .doesNotContain("planner.example")
                .doesNotContain("test-refresh-not-real")
                .doesNotContain("test-access-not-real")
                .doesNotContain("test-code-not-real")
                .doesNotContain("authorizationUrl")
                .doesNotContain("code_verifier");
    }
}
