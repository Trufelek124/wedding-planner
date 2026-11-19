package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.StandInGoogle;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Rounds of every kind, the failing ones included, with the most talkative logging: the log holds no sender, no subject,
 * no word of a text, no title, no passage, no link, no mailbox's address and not the key. Nor does the count.
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
class ReadingLeavesNothingInTheLogIntegrationTest extends RoundTestBase {

    @Test
    void nothingOfAMessageOrAnAnswerOrTheKeyReachesTheLogAfterRoundsOfEveryKind(CapturedOutput output) throws Exception {
        connectAnna();
        connectPiotr();
        String name = "Sender-Secret-Qx";
        String address = "sender-secret@qx.example";
        String text = "Body-Secret-Qx the deposit of 100 PLN is due. Quoted-Secret-Qx words that a passage repeats.";
        String passage = "Quoted-Secret-Qx words that a passage repeats.";
        String good = with(with(with(with(proposal("PAYMENT", "Title-Secret-Qx", passage), "vendorName", "Vendor-Secret-Qx"), "notes", "Notes-Secret-Qx"),
                "vendorWebsite", "website-secret.qx.example"), "amount", "100");
        for (String id : List.of("m-ok", "m-no", "m-declined", "m-unusable", "m-many", "m-wait", "m-gone", "m-p")) {
            google.mailbox(ANNA_MAILBOX).write(id, "thread-" + id, daysAgo(2), name + " <" + address + ">", "Subject-Secret-Qx", Mime.plain(text), "INBOX");
        }
        google.mailbox(PIOTR_MAILBOX).write("m-piotr", "thread-piotr", daysAgo(2), name + " <" + address + ">", "Subject-Secret-Qx", Mime.plain(text), "INBOX");
        note();

        // Newest first: m-p (Piotr's look is later, so m-piotr first, refused), then Anna's from m-p backwards.
        google.answerEveryGmailCallOfWith(PIOTR_MAILBOX, StandInGoogle.Reply.json(429, "{\"error\":\"Error-Secret-Qx " + address + "\"}"));
        service.answerNextCallsWith(
                triageSays(true, 90), extractionSays(good),
                triageSays(false, 95),
                Reply.refusal(),
                Reply.says("Answer-Secret-Qx is not the shape"),
                triageSays(true, 90), extractionSays(good, good, good, good, good, good),
                Reply.error(529, "overloaded_error", "Error-Secret-Qx " + text));
        for (int round = 0; round < 3; round++) {
            this.round.round();
            TestClockAdvance.minutes(70);
        }
        service.reset();
        service.answerEveryCallWith(Reply.error(401, "authentication_error", "invalid x-api-key " + StandInAnthropic.KEY + " Error-Secret-Qx"));
        this.round.round();

        String log = output.getAll();
        assertThat(log).as("the round and the messages are in it").contains("A round of reading is over").contains("Read a message of member");
        List<String> secrets = List.of(name, address, "Subject-Secret-Qx", "Body-Secret-Qx", "Quoted-Secret-Qx", "Title-Secret-Qx", "Vendor-Secret-Qx",
                "Notes-Secret-Qx", "website-secret", "Error-Secret-Qx", "Answer-Secret-Qx", StandInAnthropic.KEY, "anna.mailbox", "piotr.mailbox",
                "mail.google.com", "authuser", "#all/");
        for (String secret : secrets) {
            assertThat(log).as("the log, a secret starting " + secret.substring(0, Math.min(6, secret.length()))).doesNotContain(secret);
        }
        assertThat(log).doesNotContain("-Qx").doesNotContain("sk-test");
        String counted = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM reading_usage t", String.class));
        assertThat(counted).doesNotContain("Qx").doesNotContain("m-ok");
    }
}
