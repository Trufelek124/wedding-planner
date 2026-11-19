package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Used;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The cost arithmetic, to the cent and beyond, and the two extrapolations: every figure is worked out on paper here. */
class MeasureCostsTest {

    private static final Rules.Price HAIKU = new Rules.Price(new BigDecimal("0.10"), new BigDecimal("0.50"));
    private static final Rules.Price OPUS = new Rules.Price(new BigDecimal("4"), new BigDecimal("20"));

    @Test
    void tokensAreCountedAtThePricePerMillion() {
        // 1,000,000 in at 4 and 500,000 out at 20: 4 + 10 = 14 dollars.
        assertThat(MeasureCosts.of(1_000_000, 500_000, OPUS)).isEqualByComparingTo("14");
        // 700 in and 100 out at 0.10 and 0.50: (70 + 50) / 1,000,000 = 0.00012, which is D-007's "about 0.12 USD" per thousand.
        assertThat(MeasureCosts.of(700, 100, HAIKU)).isEqualByComparingTo("0.00012");
        // 6,000 in and 1,500 out at 4 and 20: (24,000 + 30,000) / 1,000,000 = 0.054; D-007's "2.70 USD" is 50 of them.
        assertThat(MeasureCosts.of(6_000, 1_500, OPUS)).isEqualByComparingTo("0.054");
        assertThat(MeasureCosts.of(0, 0, OPUS)).isEqualByComparingTo("0");
    }

    @Test
    void aPerThousandIsTheTriageOfEveryMessageAndTheExtractionOfTheShareThatConcernsTheWedding() {
        BigDecimal triage = MeasureCosts.of(700, 100, HAIKU);
        BigDecimal extraction = MeasureCosts.of(6_000, 1_500, OPUS);

        // One message of each: 1000 x 0.00012 = 0.12; at 5 in 100: 1000 x 0.054 x 5 / 100 = 2.70; together 2.82 (D-007: "about 3 USD").
        BigDecimal atFive = MeasureCosts.perThousand(triage, 1, extraction, 1, 5, 100);

        assertThat(atFive).isEqualByComparingTo("2.82");
        assertThat(MeasureCosts.usd(atFive)).isEqualTo("2.8200 USD");
        // At the share of a set of 20 with 11 wedding messages: 0.12 + 1000 x 0.054 x 11 / 20 = 0.12 + 29.70 = 29.82.
        assertThat(MeasureCosts.perThousand(triage, 1, extraction, 1, 11, 20)).isEqualByComparingTo("29.82");
        // The averages are over the messages that went through the step: 20 triaged and 11 extracted, with 20 times and 11 times the cost.
        assertThat(MeasureCosts.perThousand(triage.multiply(BigDecimal.valueOf(20)), 20, extraction.multiply(BigDecimal.valueOf(11)), 11, 11, 20))
                .isEqualByComparingTo("29.82");
    }

    @Test
    void aFirstLookOverFiveThousandIsFiveTimesAThousand() {
        assertThat(MeasureReport.firstLook(new BigDecimal("2.82"))).isEqualByComparingTo("14.10");
        assertThat(MeasureCosts.usd(MeasureReport.firstLook(new BigDecimal("2.82")))).isEqualTo("14.1000 USD");
    }

    @Test
    void nothingToAverageIsNotADivisionByZero() {
        assertThat(MeasureCosts.perThousand(BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 5, 100)).isEqualByComparingTo("0");
    }

    @Test
    void moneyIsShownWithFourDecimalsRoundedHalfUp() {
        assertThat(MeasureCosts.usd(new BigDecimal("0.00005"))).isEqualTo("0.0001 USD");
        assertThat(MeasureCosts.usd(new BigDecimal("0.00004"))).isEqualTo("0.0000 USD");
        assertThat(MeasureCosts.usd(new BigDecimal("12"))).isEqualTo("12.0000 USD");
        assertThat(MeasureCosts.price(new BigDecimal("0.10"))).isEqualTo("0.1");
        assertThat(MeasureCosts.price(new BigDecimal("20"))).isEqualTo("20");
    }

    @Test
    void callsAndTokensAreKeptPerStepAndModel() {
        MeasureCosts costs = new MeasureCosts();
        costs.record(true, new Outcome.Answered<>(
                "x", new Used("m-a", 10, 2)));
        costs.record(true, new Outcome.Answered<>(
                "x", new Used("m-b", 30, 4)));
        costs.record(true, new Outcome.Unanswered<String>(
                Outcome.Why.TRY_LATER, Used.NOTHING));
        costs.record(false, new Outcome.Answered<>(
                "x", new Used("m-c", 1_000, 200)));

        assertThat(costs.calls(true)).isEqualTo(3);
        assertThat(costs.tokensIn(true)).isEqualTo(40);
        assertThat(costs.tokensOut(true)).isEqualTo(6);
        assertThat(costs.calls(false)).isEqualTo(1);
        assertThat(costs.triage.keySet()).containsExactly("m-a", "m-b", "");
        // A model that answers and is not the one that was set is counted at its step's price: (40 x 0.10 + 6 x 0.50) / 1,000,000.
        assertThat(costs.cost(true, HAIKU)).isEqualByComparingTo("0.000007");
    }
}
