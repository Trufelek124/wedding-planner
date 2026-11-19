package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailTestBase;
import io.github.trufelek124.weddingplanner.mail.internal.NotingForTests;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mailreading.Reading.State;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** The mail import is set up and messages wait, but there is no key: the reading is off, and the mail source is asked nothing. */
@TestPropertySource(properties = "app.reading.automatic=false")
class ReadingWithoutAKeyIntegrationTest extends MailTestBase {

    @DynamicPropertySource
    static void standIn(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @Autowired
    ReadingRound round;

    @Autowired
    ApplicationContext context;

    @Test
    void withNoKeyNothingIsReadAndNothingIsAskedOfGmailOrTheService() throws Exception {
        StandInAnthropic.get().reset();
        google.mailbox(ANNA_MAILBOX).markerIs(7_391_000);
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        google.mailbox(ANNA_MAILBOX).write("m-1", "t-1", Instant.now().minus(Duration.ofDays(2)), "Jan <jan@palace-on-the-hill.example>", "Hi",
                Mime.plain("Hello"), "INBOX");
        NotingForTests.lookAtAll(context);
        int threadCalls = google.calls("/gmail/v1/users/me/threads").size();

        assertThat(round.status().state()).isEqualTo(State.OFF);
        assertThat(round.round()).isZero();

        assertThat(google.calls("/gmail/v1/users/me/threads")).hasSize(threadCalls);
        assertThat(StandInAnthropic.get().calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE verdict IS NULL", Integer.class)).isEqualTo(1);
    }
}
