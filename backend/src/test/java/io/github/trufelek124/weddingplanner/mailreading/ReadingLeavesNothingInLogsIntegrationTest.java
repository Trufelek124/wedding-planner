package io.github.trufelek124.weddingplanner.mailreading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Calls of every kind, the failing ones included, with the most talkative logging: the log holds no sender, no subject,
 * no word of a text, no title, no passage and not the key. Nor does anything a person might print of an input or an
 * outcome.
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
    // The service's side of the conversation is the test's own stand-in, which writes the request lines it is asked: not the planner.
    "logging.level.com.sun.net.httpserver=INFO",
    "logging.level.jdk=INFO",
    "logging.level.io.netty=INFO"
})
class ReadingLeavesNothingInLogsIntegrationTest extends ReadingTestBase {

    @Test
    void nothingOfAMessageOrAnAnswerOrTheKeyReachesTheLog(CapturedOutput output) {
        String senderName = "Sender-Secret-Qx";
        String senderAddress = "sender-secret@qx.example";
        String subject = "Subject-Secret-Qx";
        String text = "Body-Secret-Qx the deposit of 100 PLN is due.";
        String earlier = "Earlier-Secret-Qx we talked.";
        String title = "Title-Secret-Qx";
        String passage = "Body-Secret-Qx the deposit";
        List<String> secrets = List.of(senderName, senderAddress, subject, "Body-Secret-Qx", "Earlier-Secret-Qx", title, "Vendor-Secret-Qx",
                "Notes-Secret-Qx", StandInAnthropic.KEY, "Error-Secret-Qx", "Answer-Secret-Qx");
        Sender from = new Sender(senderName, senderAddress);
        MailTriage.Input triageInput = new MailTriage.Input(from, subject, Instant.parse("2026-11-18T09:30:00Z"), text);
        MailExtraction.Input extractionInput = new MailExtraction.Input(from, subject, Instant.parse("2026-11-18T09:30:00Z"), text,
                List.of(new MailExtraction.EarlierMessage(from, Instant.parse("2026-11-10T09:30:00Z"), earlier)), TODAY, WEDDING, List.of(title));
        String good = with(with(proposal("TASK", title, passage), "vendorName", "\"Vendor-Secret-Qx\""), "notes", "\"Notes-Secret-Qx\"");

        List<Outcome<?>> outcomes = new ArrayList<>();
        // Answers of every kind.
        service.answerNextCallsWith(
                Reply.says(triageAnswer(true, 90)), Reply.says(proposals(good)), Reply.says(triageAnswer(false, 3)),
                Reply.refusal(), Reply.refusal(),
                Reply.message("max_tokens", "Answer-Secret-Qx", 10, 10, "%MODEL%"), Reply.message("max_tokens", "Answer-Secret-Qx", 10, 10, "%MODEL%"),
                Reply.says("Answer-Secret-Qx is not the shape"), Reply.says("Answer-Secret-Qx is not the shape"),
                Reply.says(proposals(good, good, good, good, good, good)));
        outcomes.add(triage.triage(triageInput));
        outcomes.add(extraction.extract(extractionInput));
        outcomes.add(triage.triage(triageInput));
        outcomes.add(triage.triage(triageInput));
        outcomes.add(extraction.extract(extractionInput));
        outcomes.add(triage.triage(triageInput));
        outcomes.add(extraction.extract(extractionInput));
        outcomes.add(triage.triage(triageInput));
        outcomes.add(extraction.extract(extractionInput));
        outcomes.add(extraction.extract(extractionInput));
        // Failures of every kind, whose bodies carry the secrets as a careless service might echo them.
        for (Reply failure : List.of(
                Reply.error(429, "rate_limit_error", "Error-Secret-Qx " + text + " " + subject),
                Reply.error(529, "overloaded_error", "Error-Secret-Qx " + senderAddress),
                Reply.error(500, "api_error", "Error-Secret-Qx " + senderName),
                Reply.error(401, "authentication_error", "invalid x-api-key " + StandInAnthropic.KEY + " Error-Secret-Qx"),
                Reply.error(403, "permission_error", "Error-Secret-Qx " + earlier),
                Reply.error(402, "billing_error", "Error-Secret-Qx"),
                Reply.error(400, "invalid_request_error", "Error-Secret-Qx " + text + " " + title),
                Reply.dropped(),
                new Reply(200, "Error-Secret-Qx " + text + " not json", false))) {
            service.reset();
            service.answerEveryCallWith(failure);
            outcomes.add(triage.triage(triageInput));
            outcomes.add(extraction.extract(extractionInput));
        }

        String log = output.getAll();
        assertThat(log).as("the log was captured and the steps are in it").contains("Mail reading, triage: answered").contains("Mail reading, extraction: answered")
                .contains("the service failed");
        String printed = outcomes + " " + triageInput + " " + extractionInput + " " + from + " " + extractionInput.earlier();
        for (String secret : secrets) {
            assertThat(log).as("the log, a secret starting " + secret.substring(0, 6)).doesNotContain(secret);
            assertThat(printed).as("toString, a secret starting " + secret.substring(0, 6)).doesNotContain(secret);
        }
        assertThat(log).doesNotContain("-Qx").doesNotContain("sk-test");
    }
}
