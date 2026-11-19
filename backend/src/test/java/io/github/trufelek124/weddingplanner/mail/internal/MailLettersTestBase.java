package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.MailReading.Content;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.Unavailable;
import io.github.trufelek124.weddingplanner.mail.MailReading.Why;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/** Reading made-up messages through what the mail module offers, against the stand-in's mailbox. */
abstract class MailLettersTestBase extends MailSyncTestBase {

    static final String THREADS = "/gmail/v1/users/me/threads";
    /** Anything below a message's own address: what the planner must never ask for. */
    static final String BELOW_A_MESSAGE = "/gmail/v1/users/me/messages/";

    static final String JAN = "Jan from Palace on the Hill <jan@palace-on-the-hill.example>";

    @Autowired
    MailReading reading;

    UUID weddingId() {
        return jdbc.queryForObject("SELECT wedding_id FROM wedding_member WHERE id = ?", UUID.class, anna());
    }

    MessageHandle annas(String messageId, String threadId) {
        return new MessageHandle(weddingId(), anna(), messageId, threadId);
    }

    MessageHandle piotrs(String messageId, String threadId) {
        return new MessageHandle(weddingId(), piotr(), messageId, threadId);
    }

    /** What Anna's mailbox says of this message; fails unless it is a letter. */
    Letter letterOf(String messageId, String threadId) {
        Content content = reading.read(annas(messageId, threadId));
        assertThat(content).as("a letter").isInstanceOf(Letter.class);
        return (Letter) content;
    }

    Why whyNot(String messageId, String threadId) {
        Content content = reading.read(annas(messageId, threadId));
        assertThat(content).as("not a letter").isInstanceOf(Unavailable.class);
        return ((Unavailable) content).why();
    }

    /** A header word as mail has it: {@code =?charset?B?...?=}. */
    static String word(String charset, String text) {
        return "=?" + charset + "?B?" + Base64.getEncoder().encodeToString(text.getBytes(Charset.forName(charset))) + "?=";
    }

    static Mime plain(String text) {
        return Mime.plain(text);
    }
}
