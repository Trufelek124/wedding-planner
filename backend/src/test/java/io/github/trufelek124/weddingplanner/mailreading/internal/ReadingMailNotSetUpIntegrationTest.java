package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.ReadingReport.State;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** A key is given and every setting is valid, but the mail import is not set up: nothing is read, nothing is asked. */
@TestPropertySource(properties = {"app.reading.api-key=" + StandInAnthropic.KEY, "app.reading.automatic=false"})
class ReadingMailNotSetUpIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void standIn(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @Autowired
    ReadingRound round;

    @Autowired
    MailReading mail;

    @Test
    void withAKeyAndNoMailImportTheStateIsOffAndTheRoundAsksNothingOfTheServiceOrTheMailModule() {
        StandInAnthropic.get().reset();

        assertThat(mail.isSetUp()).isFalse();
        assertThat(round.report().state()).isEqualTo(State.OFF);
        assertThat(round.round()).isZero();
        assertThat(StandInAnthropic.get().calls()).isEmpty();
    }
}
