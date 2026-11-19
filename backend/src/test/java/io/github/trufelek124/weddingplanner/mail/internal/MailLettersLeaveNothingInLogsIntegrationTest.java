package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Content;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
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
 * Reading messages of every kind, with the most talkative logging: the log holds no sender, no subject, no word of a
 * text and no identifier of a message or a thread. Neither does the table of noted messages, which holds the two
 * identifiers, the order of noting and the verdict, and nothing of what a message says.
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
class MailLettersLeaveNothingInLogsIntegrationTest extends MailLettersTestBase {

    @Test
    void nothingOfAMessageReachesTheLogOrTheTable(CapturedOutput output) throws Exception {
        connectAnna();
        connectPiotr();
        List<String> secrets = new ArrayList<>(List.of(
                "Sender-Secret-Qx", "sender-secret@qx.example", "Subject-Secret-Qx", "Body-Secret-Qx", "Earlier-Secret-Qx",
                "Html-Secret-Qx", "Hidden-Script-Qx", "Attachment-Secret-Qx", "Polish-Secret-Qx", "Ukrainian-Secret-Qx"));
        String sender = "Sender-Secret-Qx <sender-secret@qx.example>";
        annasBox().write("msg-plain-qx", "thr-plain-qx", daysAgo(3), sender, "Subject-Secret-Qx", plain("Earlier-Secret-Qx"), "INBOX");
        annasBox().write("msg-now-qx", "thr-plain-qx", daysAgo(2), sender, "Re: Subject-Secret-Qx", plain("Body-Secret-Qx"), "INBOX");
        annasBox().write("msg-html-qx", "thr-html-qx", daysAgo(2), sender, "Subject-Secret-Qx",
                Mime.html("<p>Html-Secret-Qx</p><script>Hidden-Script-Qx</script>"), "INBOX");
        annasBox().write("msg-att-qx", "thr-att-qx", daysAgo(2), sender, "Subject-Secret-Qx",
                Mime.multipart("mixed", plain("Body-Secret-Qx"), Mime.inlineFile("n.txt", "text/plain", "Attachment-Secret-Qx")), "INBOX");
        annasBox().write("msg-pl-qx", "thr-pl-qx", daysAgo(2), word("ISO-8859-2", "Polish-Secret-Qx Łódź") + " <sender-secret@qx.example>",
                word("UTF-8", "Subject-Secret-Qx ślub"), Mime.plain("Polish-Secret-Qx złożymy", "ISO-8859-2"), "INBOX");
        piotrsBox().write("msg-ua-qx", "thr-ua-qx", daysAgo(2), sender, "Subject-Secret-Qx",
                Mime.plain("Ukrainian-Secret-Qx вітаємо", "windows-1251"), "INBOX");
        annasBox().write("msg-empty-qx", "thr-empty-qx", daysAgo(2), sender, "Subject-Secret-Qx", Mime.html("<div></div>"), "INBOX");
        annasBox().write("msg-big-qx", "thr-big-qx", daysAgo(2), sender, "Subject-Secret-Qx", plain("Body-Secret-Qx ".repeat(160_000)), "INBOX");
        annasBox().write("msg-long-qx", "thr-long-qx", daysAgo(2), sender, "Subject-Secret-Qx", plain("Body-Secret-Qx ".repeat(10_000)), "INBOX");
        List<String> identifiers = List.of("msg-plain-qx", "thr-plain-qx", "msg-now-qx", "msg-html-qx", "thr-html-qx", "msg-att-qx", "thr-att-qx",
                "msg-pl-qx", "thr-pl-qx", "msg-ua-qx", "thr-ua-qx", "msg-empty-qx", "thr-empty-qx", "msg-big-qx", "thr-big-qx", "msg-long-qx", "thr-long-qx",
                "msg-gone-qx");
        secrets.addAll(identifiers);

        lookBack(piotr(), 90);
        noteEverything(anna());
        List<MessageHandle> handles = reading.waiting(100);
        assertThat(handles).hasSize(9);
        // Every kind of message, then every kind of trouble, with the answers themselves carrying the secrets.
        List<Content> contents = new ArrayList<>();
        for (MessageHandle handle : handles) {
            contents.add(reading.read(handle));
        }
        contents.add(reading.read(annas("msg-gone-qx", "thr-gone-qx")));
        google.answerNextCallsToWith(THREADS, Reply.json(429, "{\"error\":{\"message\":\"Body-Secret-Qx msg-now-qx Sender-Secret-Qx\"}}"));
        contents.add(reading.read(annas("msg-now-qx", "thr-plain-qx")));
        google.answerNextCallsToWith(THREADS, Reply.json(401, "{}"), Reply.normally());
        contents.add(reading.read(annas("msg-now-qx", "thr-plain-qx")));
        google.answerNextCallsToWith(THREADS, Reply.json(200, "{\"messages\":[{\"id\":\"msg-now-qx\",\"internalDate\":\"Body-Secret-Qx\",\"payload\":7}]}"));
        contents.add(reading.read(annas("msg-now-qx", "thr-plain-qx")));
        google.answerNextCallsToWith(THREADS, Reply.slow(2_600));
        contents.add(reading.read(annas("msg-now-qx", "thr-plain-qx")));
        google.withdraw(google.refreshTokenOf(PIOTR_MAILBOX));
        TestClock.advance(Duration.ofHours(2));
        contents.add(reading.read(piotrs("msg-ua-qx", "thr-ua-qx")));
        for (MessageHandle handle : handles) {
            reading.keysOf(handle);
            reading.mark(handle, new AboutTheWedding(1));
        }
        reading.counts(weddingId());
        // The link holds the mailbox's address: made and thrown away, it leaves nothing in the log.
        String link = reading.linkTo(annas("msg-now-qx", "thr-plain-qx")).orElseThrow();
        assertThat(link).contains("authuser=");
        reading.linkTo(annas("not/an/identifier", "thr-plain-qx"));
        reading.waiting(100, List.of(piotr()));
        String address = link.substring(link.indexOf("authuser=") + 9, link.indexOf('#'));
        secrets.addAll(List.of(address, ANNA_MAILBOX));

        assertThat(contents).anySatisfy(content -> assertThat(content).isInstanceOf(Letter.class));
        // Whatever a person might print: the answers and the handles.
        String printed = contents + " " + handles + " " + handles.stream().map(reading::keysOf).toList();
        String log = output.getAll();
        String table = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_message t", String.class));
        assertThat(log).as("the log was captured and the sync is in it").contains("Looked at the mailbox of member " + anna());
        for (String secret : secrets) {
            assertThat(log).as("the log, a secret of " + secret.length() + " characters starting " + secret.substring(0, 6)).doesNotContain(secret);
            assertThat(printed).as("toString, a secret starting " + secret.substring(0, 6)).doesNotContain(secret);
        }
        for (String secret : secrets.subList(0, 10)) {
            assertThat(table).as("the table, a secret starting " + secret.substring(0, 6)).doesNotContain(secret);
        }
        // The table holds the two identifiers and the order and the verdict; nothing of a message but those.
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'mail_message'", String.class))
                .containsExactlyInAnyOrder("member_id", "message_id", "thread_id", "noted_at", "look", "newness", "verdict", "unread_reason", "proposals", "read_at");
        assertThat(log).doesNotContain("Subject-Secret").doesNotContain("-Qx");
    }
}
