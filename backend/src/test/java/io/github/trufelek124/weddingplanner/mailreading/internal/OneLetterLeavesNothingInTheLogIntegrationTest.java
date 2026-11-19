package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * A letter in both mailboxes, read once and then recognised as a copy, with the most talkative logging: the identifier its
 * sender gave it is in no line of the log and in no table. Only the key worked out from it is kept.
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
    // The other side of the conversation is the tests' own stand-ins, which write the request lines they are asked.
    "logging.level.com.sun.net.httpserver=INFO",
    "logging.level.jdk=INFO",
    "logging.level.io.netty=INFO"
})
class OneLetterLeavesNothingInTheLogIntegrationTest extends RoundTestBase {

    @Test
    void theIdentifierTheSenderGaveIsNowhereButTheKeyWorkedOutFromItIsKept(CapturedOutput output) throws Exception {
        connectAnna();
        connectPiotr();
        String header = "<Identifier-Secret-Qx@sender-secret.qx.example>";
        gets(ANNA_MAILBOX, "m-anna-qx", "thread-anna-qx", 2, "Sender-Secret-Qx <sender-secret@qx.example>", "Subject-Secret-Qx",
                "Body-Secret-Qx " + POLISH, header);
        note();
        gets(PIOTR_MAILBOX, "m-piotr-qx", "thread-piotr-qx", 2, "Sender-Secret-Qx <sender-secret@qx.example>", "Subject-Secret-Qx",
                "Body-Secret-Qx " + POLISH, header);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("PAYMENT", "Title-Secret-Qx", DEPOSIT_PASSAGE)));

        assertThat(round.round()).isEqualTo(2);

        assertThat(verdictOf("m-anna-qx")).containsEntry("verdict", "COPY");
        List<String> kept = jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_message t", String.class);
        String tables = String.join("\n", kept) + String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM reading_usage t", String.class));
        assertThat(tables).contains("\"letter_key\":\"l1-");
        String log = output.getAll();
        assertThat(log).contains("a copy of a letter that was read already");
        for (String secret : List.of("Identifier-Secret-Qx", "sender-secret.qx.example", "Sender-Secret-Qx", "Subject-Secret-Qx", "Body-Secret-Qx",
                "m-anna-qx", "m-piotr-qx", "thread-anna-qx", "thread-piotr-qx")) {
            assertThat(log).as("the log, a secret starting " + secret.substring(0, 6)).doesNotContain(secret);
        }
        assertThat(tables).doesNotContain("Identifier-Secret").doesNotContain("sender-secret.qx");
        assertThat(log).doesNotContain("Title-Secret");
    }
}
