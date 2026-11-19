package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Amount;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static OptionalLong of(String value, String currency, String planCurrency) {
        return Money.smallestUnits(new Amount(value, currency), planCurrency);
    }

    @Test
    void anAmountInTheWeddingsCurrencyBecomesWholeSmallestUnitsExactly() {
        assertThat(of("2500", "PLN", "PLN")).hasValue(250_000);
        assertThat(of("2500.5", "PLN", "PLN")).hasValue(250_050);
        assertThat(of("2500.50", "PLN", "PLN")).hasValue(250_050);
        assertThat(of("0.07", "PLN", "PLN")).hasValue(7);
        assertThat(of("0", "PLN", "PLN")).hasValue(0);
        assertThat(of("19.99", "EUR", "EUR")).as("a float would say 1998.9999999999998").hasValue(1_999);
        assertThat(of("1.15", "PLN", "PLN")).hasValue(115);
    }

    @Test
    void theCurrencyIsComparedWithoutRegardToCase() {
        assertThat(of("10", "pln", "PLN")).hasValue(1_000);
        assertThat(of("10", "PLN", "pln")).hasValue(1_000);
    }

    @Test
    void aCurrencyWithNoDecimalsCountsInWholeUnitsAndFractionIsLeftOut() {
        assertThat(of("1500", "JPY", "JPY")).hasValue(1_500);
        assertThat(of("1500.00", "JPY", "JPY")).hasValue(1_500);
        assertThat(of("1500.50", "JPY", "JPY")).as("not whole, and never rounded").isEmpty();
    }

    @Test
    void aCurrencyWithThreeDecimalsCountsInThousandths() {
        assertThat(of("1.5", "KWD", "KWD")).hasValue(1_500);
        assertThat(of("1.25", "KWD", "KWD")).hasValue(1_250);
    }

    @Test
    void anotherCurrencyOrNoneIsLeftOutAndNothingIsConverted() {
        assertThat(of("300.00", "EUR", "PLN")).isEmpty();
        assertThat(of("300.00", "", "PLN")).isEmpty();
        assertThat(of("300", "PL", "PLN")).isEmpty();
    }

    @Test
    void theLimitOfTheSmallestUnitIsAllowedAndNothingAboveIt() {
        assertThat(of("10000000000", "PLN", "PLN")).hasValue(1_000_000_000_000L);
        assertThat(of("10000000000.01", "PLN", "PLN")).isEmpty();
        assertThat(of("999999999999.99", "PLN", "PLN")).isEmpty();
    }

    @Test
    void aPlanCurrencyThatIsNotOneIsLeftOut() {
        assertThat(of("10", "XQX", "XQX")).isEmpty();
        assertThat(of("10", "XXX", "XXX")).isEmpty();
    }
}
