package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.Reading.State;
import io.github.trufelek124.weddingplanner.mailreading.Reading.Status;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the reading used, per day, step and model, and what it is estimated to have cost. Nothing of a message is in the count. */
class ReadingCountingIntegrationTest extends RoundTestBase {

    static final String PASSAGE = "Prosimy o wybór menu do końca tygodnia.";
    static final String TEXT = "Dzień dobry, potwierdzamy termin degustacji. " + PASSAGE + " Pozdrawiamy.";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT day, step, model, calls, input_tokens, output_tokens FROM reading_usage ORDER BY step, model");
    }

    @Test
    void callsAndTokensAreCountedPerDayStepAndModel() {
        annaGets("m-1", "one", TEXT);
        annaGets("m-2", "two", TEXT);
        note();
        service.answerNextCallsWith(
                Reply.says("{\"concernsTheWedding\":true,\"howSure\":90}", "%MODEL%", 1_000, 200),
                Reply.says("{\"proposals\":[]}", "%MODEL%", 5_000, 700),
                Reply.says("{\"concernsTheWedding\":false,\"howSure\":99}", "%MODEL%", 800, 100));

        assertThat(round.round()).isEqualTo(2);

        assertThat(rows()).hasSize(2);
        assertThat(rows().get(0)).containsEntry("step", "EXTRACTION").containsEntry("model", "claude-opus-5-5")
                .containsEntry("calls", 1L).containsEntry("input_tokens", 5_000L).containsEntry("output_tokens", 700L);
        assertThat(rows().get(1)).containsEntry("step", "TRIAGE").containsEntry("model", "claude-haiku-5-5")
                .containsEntry("calls", 2L).containsEntry("input_tokens", 1_800L).containsEntry("output_tokens", 300L);
        assertThat(rows()).allSatisfy(row -> assertThat(row.get("day").toString()).isEqualTo(today().toString()));
    }

    @Test
    void theDaysAreUtcDaysByTheApplicationsClock() {
        annaGets("m-1", "one", TEXT);
        note();
        TestClockAdvance.days(2);
        service.answerNextCallsWith(triageSays(false, 95));

        round.round();

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).get("day").toString()).isEqualTo(today().toString());
        assertThat(today()).isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1));
    }

    @Test
    void theEstimateIsTokensTimesThePricesOfTheStepToTheCentRoundedUp() {
        annaGets("m-1", "one", TEXT);
        note();
        // Triage: 1,000,000 in at $0.10 and 1,000,000 out at $0.50 is $0.60. Extraction: 500,000 in at $4 and 100,000 out at
        // $20 is $4.00.
        service.answerNextCallsWith(
                Reply.says("{\"concernsTheWedding\":true,\"howSure\":90}", "%MODEL%", 1_000_000, 1_000_000),
                Reply.says("{\"proposals\":[]}", "%MODEL%", 500_000, 100_000));
        round.round();

        Status status = round.status();

        assertThat(status.costThisMonth()).isEqualByComparingTo("4.60");
        assertThat(status.costInAll()).isEqualByComparingTo("4.60");
        assertThat(status.monthlyLimit()).isEqualByComparingTo("20");
        assertThat(status.state()).isEqualTo(State.READING);
        assertThat(status.costThisMonth().scale()).isEqualTo(2);

        // One more token is a part of a cent: a whole cent.
        usage.count(today(), UsageRepository.Step.EXTRACTION, "claude-opus-5-5", 1, 0);
        assertThat(round.status().costThisMonth()).isEqualByComparingTo("4.61");
    }

    @Test
    void thisMonthAndInAllAreNotTheSameThing() {
        usage.count(today().minusMonths(2), UsageRepository.Step.EXTRACTION, "claude-opus-5-5", 1_000_000, 0);
        usage.count(today(), UsageRepository.Step.EXTRACTION, "claude-opus-5-5", 500_000, 0);

        assertThat(round.status().costThisMonth()).isEqualByComparingTo("2.00");
        assertThat(round.status().costInAll()).isEqualByComparingTo("6.00");
    }

    @Test
    void aCallWhoseAnswerCouldNotBeUsedIsCountedAndSoIsADeclinedOne() {
        annaGets("m-1", "one", TEXT);
        annaGets("m-2", "two", TEXT);
        note();
        service.answerNextCallsWith(Reply.says("I am not sure.", "%MODEL%", 300, 30), Reply.refusal());

        assertThat(round.round()).isEqualTo(2);

        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0)).containsEntry("step", "TRIAGE").containsEntry("calls", 2L).containsEntry("input_tokens", 377L);
    }

    @Test
    void aCallThatNeverReachedTheServiceIsNotCounted() {
        annaGets("m-1", "one", TEXT);
        note();
        service.answerEveryCallWith(Reply.error(529, "overloaded_error", "x"));

        round.round();

        assertThat(rows()).isEmpty();
    }

    @Test
    void aModelTheSettingsDoNotNameIsCountedAtThePriceOfTheStepThatAsked() {
        annaGets("m-1", "one", TEXT);
        note();
        // The service's own fallback answers extraction with a model the settings never named: 1,000,000 tokens in.
        service.answerNextCallsWith(
                triageSays(true, 90),
                Reply.says("{\"proposals\":[]}", "claude-some-other-model-9", 1_000_000, 0));

        assertThat(round.round()).isEqualTo(1);

        assertThat(rows().stream().map(row -> row.get("model")).toList()).containsExactlyInAnyOrder("claude-haiku-5-5", "claude-some-other-model-9");
        assertThat(round.status().costThisMonth()).as("$4 for a million tokens in, the extraction's price").isEqualByComparingTo("4.01");
    }

    @Test
    void aModelNameThatCouldNotBeOneIsKeptAsUnknown() {
        usage.count(today(), UsageRepository.Step.TRIAGE, "a model with spaces and a Secret-Qx", 10, 10);
        usage.count(today(), UsageRepository.Step.TRIAGE, "", 10, 10);

        assertThat(rows().stream().map(row -> row.get("model")).toList()).containsExactly("unknown");
        assertThat(rows().get(0)).containsEntry("calls", 2L);
    }

    @Test
    void nothingOfAMessageOrAnAnswerIsInTheCount() {
        annaGets("m-secret", "Subject-Secret-Qx", "Body-Secret-Qx " + TEXT);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Title-Secret-Qx", PASSAGE)));

        round.round();

        String table = String.join("\n", jdbc.queryForList("SELECT row_to_json(t)::text FROM reading_usage t", String.class));
        assertThat(table).isNotBlank().doesNotContain("Secret").doesNotContain("Qx").doesNotContain("m-secret").doesNotContain("palace")
                .doesNotContain("anna.mailbox");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'reading_usage' ORDER BY ordinal_position", String.class))
                .containsExactly("day", "step", "model", "calls", "input_tokens", "output_tokens");
    }
}
