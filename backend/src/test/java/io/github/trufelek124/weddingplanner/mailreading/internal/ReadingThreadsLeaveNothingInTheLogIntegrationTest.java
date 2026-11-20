package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * A thread read as one (D-012), with a copy among its earlier messages, with the most talkative logging: the log holds no
 * word of a message, no name, no address and no identifier, only numbers and the member's id.
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
    "logging.level.com.sun.net.httpserver=INFO",
    "logging.level.jdk=INFO",
    "logging.level.io.netty=INFO"
})
class ReadingThreadsLeaveNothingInTheLogIntegrationTest extends RoundTestBase {

    @Test
    void nothingOfAThreadItsMessagesItsIdentifiersOrItsCopiesReachesTheLogAtTrace(CapturedOutput output) throws Exception {
        connectAnna();
        connectPiotr();
        String name = "Sender-Secret-Qx";
        String address = "sender-secret@qx.example";
        String from = name + " <" + address + ">";
        String passage = "Quoted-Secret-Qx words that a passage repeats.";
        gets(ANNA_MAILBOX, "m-1", "thread-x", 4, from, "Subject-Secret-Qx", "Body-One-Secret-Qx hello", "<id-one-Secret-Qx@qx.example>");
        gets(ANNA_MAILBOX, "m-2", "thread-x", 3, from, "Subject-Secret-Qx", "Body-Two-Secret-Qx hello", "<id-two-Secret-Qx@qx.example>");
        gets(ANNA_MAILBOX, "m-3", "thread-x", 2, from, "Subject-Secret-Qx", "Body-Three-Secret-Qx hello", "<id-three-Secret-Qx@qx.example>");
        gets(ANNA_MAILBOX, "m-4", "thread-x", 1, from, "Subject-Secret-Qx", "Body-Four-Secret-Qx " + passage, "<id-four-Secret-Qx@qx.example>");
        gets(PIOTR_MAILBOX, "p-2", "thread-p", 3, from, "Subject-Secret-Qx", "Body-Two-Secret-Qx hello", "<id-two-Secret-Qx@qx.example>");
        note();
        MessageHandle piotrs = new MessageHandle(weddingId(), piotr(), "p-2", "thread-p");
        assertThat(mail.mark(piotrs, new AboutTheWedding(0), ((Letter) mail.read(piotrs)).key())).isTrue();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Title-Secret-Qx", passage)));

        assertThat(round.round()).isEqualTo(4);

        String log = output.getAll();
        assertThat(log).contains("A round of reading is over").contains("earlier messages read with it");
        List<String> secrets = List.of(name, address, "Subject-Secret-Qx", "Body-", "Quoted-Secret-Qx", "Title-Secret-Qx", "id-one", "id-two", "id-three",
                "id-four", "Secret-Qx", StandInAnthropic.KEY, "anna.mailbox", "piotr.mailbox", "mail.google.com", "authuser", "#all/");
        for (String secret : secrets) {
            assertThat(log).as("the log, a secret starting " + secret.substring(0, Math.min(6, secret.length()))).doesNotContain(secret);
        }
        assertThat(log).doesNotContain("-Qx").doesNotContain("sk-test");
        String counted = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM reading_usage t", String.class));
        assertThat(counted).doesNotContain("Qx").doesNotContain("m-1");
        // The letters' own keys are in the table, never in the log.
        for (String key : jdbc.queryForList("SELECT letter_key FROM mail_message WHERE letter_key IS NOT NULL", String.class)) {
            assertThat(log).doesNotContain(key);
        }
    }
}
