package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.ReadingReport;
import io.github.trufelek124.weddingplanner.mail.ReadingReport.State;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * A key for the AI service is given, but the mail import is not set up: the reading is off and asks nothing of anybody.
 * Also: a setting that is not valid switches the reading off with a line that names it and never shows its value.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "app.reading.api-key=" + StandInAnthropic.KEY,
    "app.reading.automatic=false",
    "app.reading.daily-messages=-1",
    "app.reading.triage-price-in=Secret-Qx-not-a-number"
})
class ReadingStaysOffIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void standIn(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @Autowired
    ReadingRound round;

    @Autowired
    ReadingSteps steps;

    @Autowired
    ReadingReport reading;

    @Autowired
    MailReading mail;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void quiet() {
        StandInAnthropic.get().reset();
        // The database is shared with the other tests of this run: the count starts empty.
        jdbc.update("DELETE FROM reading_usage");
    }

    @Test
    void aSettingThatIsNotValidSwitchesTheReadingOffAndTheServerStartedAllTheSame(CapturedOutput output) {
        assertThat(steps).isInstanceOf(NoSteps.class);
        assertThat(reading.report().state()).isEqualTo(State.OFF);
        assertThat(round.round()).isZero();
        assertThat(StandInAnthropic.get().calls()).isEmpty();
        assertThat(output.getAll()).contains("The reading of the mail is off: not valid: ").contains("MAIL_READING_DAILY_MESSAGES")
                .contains("APP_AI_TRIAGE_PRICE_IN").doesNotContain("Secret-Qx").doesNotContain(StandInAnthropic.KEY);
    }

    @Test
    void theStateIsOffAndTheCostIsNothing() {
        ReadingReport.Report status = reading.report();

        assertThat(status.state()).isEqualTo(State.OFF);
        assertThat(status.costThisMonthCents()).isZero();
        assertThat(status.costInAllCents()).isZero();
        assertThat(mail.isSetUp()).isFalse();
    }
}
