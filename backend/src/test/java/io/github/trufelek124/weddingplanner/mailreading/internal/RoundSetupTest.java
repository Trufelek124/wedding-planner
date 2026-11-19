package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class RoundSetupTest {

    private static RoundProperties good() {
        return new RoundProperties("1m", "true", "20", "60", "20", "2000", "50", "0.10", "0.50", "4", "20");
    }

    private static RoundProperties with(String field, String value) {
        RoundProperties p = good();
        return switch (field) {
            case "interval" -> new RoundProperties(value, p.automatic(), p.batch(), p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "automatic" -> new RoundProperties(p.interval(), value, p.batch(), p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "batch" -> new RoundProperties(p.interval(), p.automatic(), value, p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "sure" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), value, p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "limit" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), p.triageSure(), value, p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "daily" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), p.triageSure(), p.monthlyLimitUsd(), value, p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "perMailbox" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), value, p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "triageIn" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), value, p.triagePriceOut(), p.extractionPriceIn(), p.extractionPriceOut());
            case "extractionOut" -> new RoundProperties(p.interval(), p.automatic(), p.batch(), p.triageSure(), p.monthlyLimitUsd(), p.dailyMessages(), p.dailySuggestionsPerMailbox(), p.triagePriceIn(), p.triagePriceOut(), p.extractionPriceIn(), value);
            default -> throw new IllegalArgumentException(field);
        };
    }

    @Test
    void theStartingValuesAreValidAndMeanOnWithAKey() {
        RoundSetup.Parsed parsed = RoundSetup.parse(good(), true);

        assertThat(parsed.invalid()).isEmpty();
        assertThat(parsed.rules().on()).isTrue();
        assertThat(parsed.rules().interval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(parsed.rules().automatic()).isTrue();
        assertThat(parsed.rules().batch()).isEqualTo(20);
        assertThat(parsed.rules().triageSure()).isEqualTo(60);
        assertThat(parsed.rules().monthlyLimitUsd()).isEqualByComparingTo("20");
        assertThat(parsed.rules().dailyMessages()).isEqualTo(2000);
        assertThat(parsed.rules().dailySuggestionsPerMailbox()).isEqualTo(50);
        assertThat(parsed.rules().triagePrice().out()).isEqualByComparingTo("0.5");
        assertThat(parsed.rules().extractionPrice().in()).isEqualByComparingTo("4");
    }

    @Test
    void withoutAKeyTheRulesAreOffWhateverTheyAre() {
        assertThat(RoundSetup.parse(good(), false).rules().on()).isFalse();
    }

    @Test
    void aSettingThatIsNotValidIsNamedAndNeverQuotedAndTheReadingIsOff() {
        String secret = "Secret-Qx";
        String[][] bad = {
            {"interval", secret, RoundSetup.INTERVAL}, {"interval", "0s", RoundSetup.INTERVAL}, {"interval", "2d", RoundSetup.INTERVAL},
            {"automatic", secret, RoundSetup.AUTOMATIC}, {"batch", "0", RoundSetup.BATCH}, {"batch", "101", RoundSetup.BATCH},
            {"sure", "-1", RoundSetup.TRIAGE_SURE}, {"sure", "101", RoundSetup.TRIAGE_SURE}, {"sure", "60.5", RoundSetup.TRIAGE_SURE},
            {"limit", "-0.01", RoundSetup.MONTHLY_LIMIT}, {"limit", secret, RoundSetup.MONTHLY_LIMIT}, {"limit", "NaN", RoundSetup.MONTHLY_LIMIT},
            {"daily", "-1", RoundSetup.DAILY_MESSAGES}, {"perMailbox", "-1", RoundSetup.DAILY_SUGGESTIONS},
            {"triageIn", secret, RoundSetup.TRIAGE_PRICE_IN}, {"extractionOut", "-3", RoundSetup.EXTRACTION_PRICE_OUT},
            {"limit", "", RoundSetup.MONTHLY_LIMIT}};
        for (String[] one : bad) {
            RoundSetup.Parsed parsed = RoundSetup.parse(with(one[0], one[1]), true);

            assertThat(parsed.invalid()).as(one[0] + " " + one[1]).containsExactly(one[2]);
            assertThat(parsed.rules().on()).isFalse();
            assertThat(parsed.invalid().toString()).doesNotContain(secret);
        }
    }

    @Test
    void zeroLimitsAreValidAndMeanNothingIsReadToday() {
        assertThat(RoundSetup.parse(with("limit", "0"), true).rules().on()).isTrue();
        assertThat(RoundSetup.parse(with("daily", "0"), true).rules().on()).isTrue();
    }

    @Test
    void theRoundIsScheduledOnlyWhenTheReadingIsOnAndAutomatic() {
        Rules on = RoundSetup.parse(good(), true).rules();
        Rules notAutomatic = RoundSetup.parse(with("automatic", "false"), true).rules();
        Rules off = RoundSetup.parse(good(), false).rules();

        assertThat(tasks(on)).isEqualTo(1);
        assertThat(tasks(notAutomatic)).isZero();
        assertThat(tasks(off)).isZero();
    }

    private static int tasks(Rules rules) {
        ReadingRound round = new ReadingRound(new NoSteps(), rules, null, null, null, null, null, java.time.Clock.systemUTC());
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        round.configureTasks(registrar);
        return registrar.getFixedDelayTaskList().size();
    }
}
