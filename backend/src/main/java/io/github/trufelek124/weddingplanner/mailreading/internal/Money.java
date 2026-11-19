package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Amount;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * An amount as the mail writes it, turned into whole numbers of the smallest unit of the wedding's currency (S04
 * section 5). Exact arithmetic only: nothing is rounded, and what does not come out whole is left out.
 */
final class Money {

    /** The planner's limit for an amount, in the smallest unit: what its own forms hold an amount to. */
    static final long MOST = 1_000_000_000_000L;

    private Money() {}

    /**
     * @param planCurrency the wedding's currency, an ISO 4217 code
     * @return the amount in the smallest unit; empty when the mail's currency is not the wedding's (or names none), when
     *     the amount does not come out whole, or when it is above the planner's limit
     */
    static OptionalLong smallestUnits(Amount amount, String planCurrency) {
        if (amount.currency().isEmpty() || !amount.currency().equalsIgnoreCase(planCurrency)) {
            return OptionalLong.empty();
        }
        int digits;
        try {
            digits = Currency.getInstance(planCurrency.toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        } catch (IllegalArgumentException notACurrency) {
            return OptionalLong.empty();
        }
        if (digits < 0) {
            return OptionalLong.empty();
        }
        try {
            BigDecimal scaled = new BigDecimal(amount.value()).movePointRight(digits);
            long whole = scaled.toBigIntegerExact().longValueExact();
            return whole >= 0 && whole <= MOST ? OptionalLong.of(whole) : OptionalLong.empty();
        } catch (ArithmeticException | NumberFormatException doesNotComeOutWhole) {
            return OptionalLong.empty();
        }
    }

    /**
     * The way back: whole numbers of the smallest unit as people write an amount, with the currency ({@code 5000.00 PLN}).
     * Exact: the number is shifted by the currency's digits, never divided, and no floating point is involved. A currency
     * the platform does not know is written with no fraction.
     */
    static String written(long smallestUnits, String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency.toUpperCase(Locale.ROOT)).getDefaultFractionDigits());
        } catch (IllegalArgumentException notACurrency) {
            digits = 0;
        }
        return BigDecimal.valueOf(smallestUnits, digits).toPlainString() + " " + currency.toUpperCase(Locale.ROOT);
    }
}
