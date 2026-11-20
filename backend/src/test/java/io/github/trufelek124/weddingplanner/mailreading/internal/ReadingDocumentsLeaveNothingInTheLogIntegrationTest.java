package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestPdfs;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * Documents of every kind - read, locked, damaged, too large, gone, refused, asked again without them, looked at a second time -
 * with the most talkative logging: the log holds no name, no size and no word of a document, and the tables that are the
 * module's own hold none either. Made-up documents.
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
class ReadingDocumentsLeaveNothingInTheLogIntegrationTest extends RoundTestBase {

    static final String WORDS = "Doc-Secret-Qx zaliczka 2500 PLN do 1 grudnia 2026";
    static final String MAIL = "Body-Secret-Qx Zaliczkę prosimy wpłacić do 1 grudnia 2026. Oferta w załączniku.";

    @Test
    void nothingOfADocumentReachesTheLogOrTheModulesOwnTablesNorWhatAPersonMightPrint(CapturedOutput output) throws Exception {
        connectAnna();
        byte[] good = TestPdfs.withPages(WORDS);
        // The first reading: a good one, a locked one, an html page called a pdf, a large one that is passed over, and one that is gone.
        annaGetsWithFiles("m-1", "Subject-Secret-Qx", MAIL,
                Attached.pdf("Name-Secret-Qx-1.pdf", good, 4_718_592),
                Attached.pdf("Name-Secret-Qx-2.pdf", TestPdfs.locked("Password-Secret-Qx", WORDS), 4_718_593),
                Attached.pdf("Name-Secret-Qx-3.pdf", "<html>Doc-Secret-Qx</html>".getBytes(), 4_718_594),
                Attached.pdf("Name-Secret-Qx-4.pdf", good, 12_345_678));
        // The second: asked again without documents (the answer with them is no answer), and one that Gmail refuses.
        annaGetsWithFiles("m-2", "Subject-Secret-Qx", MAIL, Attached.pdf("Name-Secret-Qx-5.pdf", good, 4_718_595));
        annaGetsWithFiles("m-3", "Subject-Secret-Qx", MAIL, Attached.pdf("Name-Secret-Qx-6.pdf", good, 4_718_596));
        note();
        google.answerNextCallsToWith(ATTACHMENTS, StandInGoogle.Reply.normally(), StandInGoogle.Reply.normally(), StandInGoogle.Reply.normally(),
                StandInGoogle.Reply.json(404, "{\"error\":\"Doc-Secret-Qx\"}"));
        String payment = with(with(with(proposal("PAYMENT", "Title-Secret-Qx", "Doc-Secret-Qx zaliczka 2500 PLN"), "amount", "2500"), "currency", "PLN"),
                "dueDay", "2026-12-01");
        // Newest first: m-3, m-2, m-1 (noted in that order, the last is the newest).
        service.answerNextCallsWith(
                triageSays(true, 95), extractionSays(payment),
                triageSays(true, 95), Reply.says("Doc-Secret-Qx is not the shape"), extractionSays(payment),
                triageSays(true, 95), extractionSays(payment));
        assertThat(round.round()).isEqualTo(3);

        // A message read before documents could be, looked at a second time.
        annaGetsWithFiles("m-old", "Subject-Secret-Qx", MAIL, Attached.pdf("Name-Secret-Qx-7.pdf", good, 4_718_597));
        note();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays(payment));
        roundOf(rulesForDocuments(false, 80)).round();
        jdbc.update("UPDATE mail_message SET documents = 'PENDING' WHERE message_id = 'm-old'");
        service.reset();
        service.answerNextCallsWith(triageSays(true, 95), extractionSays(payment));
        round.forget();
        round.round();

        String log = output.getAll();
        assertThat(log).as("the log was captured and the documents are in it").contains("Read the documents of a message of member")
                .contains("asked again without them").contains("Looked again at a message of member");
        List<String> secrets = List.of("Doc-Secret-Qx", "Name-Secret-Qx", "Password-Secret-Qx", "Body-Secret-Qx", "Subject-Secret-Qx", "Title-Secret-Qx",
                "4718592", "4718593", "4718594", "4718595", "4718596", "4718597", "12345678", "att-m-", "%PDF", "oferta", StandInAnthropic.KEY);
        for (String secret : secrets) {
            assertThat(log).as("the log, a secret starting " + secret.substring(0, Math.min(6, secret.length()))).doesNotContain(secret);
        }
        String table = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_message t", String.class));
        String counted = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM reading_usage t", String.class));
        for (String stored : List.of(table, counted)) {
            for (String secret : List.of("Doc-Secret-Qx", "Name-Secret-Qx", "Password-Secret-Qx", "4718592", "4718597", "12345678", "att-m-", "%PDF", ".pdf")) {
                assertThat(stored).as("a table, a secret starting " + secret.substring(0, Math.min(6, secret.length()))).doesNotContain(secret);
            }
        }
        // What a person might print of what carries a document.
        MailReading.Content letter = mail.read(new MailReading.MessageHandle(weddingId(), anna(), "m-1", "thread-m-1"));
        MailReading.Letter read = (MailReading.Letter) letter;
        MailExtraction.Document document = new MailExtraction.Document("Name-Secret-Qx-1.pdf", WORDS, false);
        String printed = read + " " + read.documents() + " " + document + " " + List.of(document) + " "
                + new MailExtraction.Input(new io.github.trufelek124.weddingplanner.mailreading.Sender("A", "a@x.example"), "S", java.time.Instant.EPOCH, "t",
                        List.of(), java.time.LocalDate.EPOCH, null, List.of(), List.of(document))
                + " " + new MailReading.Pdf(good) + " " + pdfText.read(good);
        for (String secret : List.of("Doc-Secret-Qx", "Name-Secret-Qx", "4718592", "%PDF")) {
            assertThat(printed).as("toString, a secret starting " + secret.substring(0, Math.min(6, secret.length()))).doesNotContain(secret);
        }
    }
}
