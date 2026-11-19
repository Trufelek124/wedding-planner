package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Sender;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import io.github.trufelek124.weddingplanner.mailreading.Used;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With no key set the application starts exactly as before: there are steps and they answer "asked nothing", but there
 * is no client of the AI service that could call out. The address is the stand-in's, so that a call would be seen.
 */
class ReadingOffIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void standIn(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MailTriage triage;

    @Autowired
    private MailExtraction extraction;

    @Test
    void theStepsAreThereAndMakeNoClient() {
        assertThat(context.getBeansOfType(ReadingSteps.class)).hasSize(1);
        assertThat(context.getBean(ReadingSteps.class)).isInstanceOf(NoSteps.class);
        assertThat(triage).isSameAs(context.getBean(ReadingSteps.class));
        assertThat(extraction).isSameAs(context.getBean(ReadingSteps.class));
    }

    @Test
    void bothStepsAnswerAskedNothingAndAskNothing() {
        StandInAnthropic.get().reset();
        Sender jan = new Sender("Jan from Palace on the Hill", "jan@palace-on-the-hill.example");

        Outcome<MailTriage.Triaged> triaged = triage.triage(new MailTriage.Input(jan, "A subject", Instant.parse("2026-11-18T09:30:00Z"), "A text."));
        Outcome<MailExtraction.Extracted> extracted = extraction.extract(new MailExtraction.Input(
                jan, "A subject", Instant.parse("2026-11-18T09:30:00Z"), "A text.", List.of(), LocalDate.parse("2026-11-19"), null, List.of()));

        assertThat(triaged).isEqualTo(new Outcome.Unanswered<MailTriage.Triaged>(Outcome.Why.ASKED_NOTHING, Used.NOTHING));
        assertThat(extracted).isEqualTo(new Outcome.Unanswered<MailExtraction.Extracted>(Outcome.Why.ASKED_NOTHING, Used.NOTHING));
        assertThat(StandInAnthropic.get().calls()).isEmpty();
    }
}
