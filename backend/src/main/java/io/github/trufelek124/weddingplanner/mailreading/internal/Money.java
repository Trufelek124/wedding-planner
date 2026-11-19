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
}
