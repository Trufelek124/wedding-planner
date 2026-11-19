package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the calls of a run used and what that comes to at the prices in the settings (US dollars per million tokens, by
 * step: a model that answers and is not the one that was set is counted at its step's price, as the server counts).
 * Money is {@link BigDecimal} all the way; only the printing rounds.
 */
final class MeasureCosts {

    /** The assumption of D-007: of a hundred messages, this many concern the wedding. */
    static final int ASSUMED_WEDDING_IN_100 = 5;

    /** The size of the first look that D-007 estimated. */
    static final int FIRST_LOOK_MESSAGES = 5_000;

    private static final int SCALE = 12;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** What one step used under one model name; the empty name is a call that was not answered. */
    static final class Counts {
        long calls;
        long in;
        long out;
    }

    /** Messages that had at least one call of the step. */
    int triageMessages;

    int extractionMessages;

    final Map<String, Counts> triage = new LinkedHashMap<>();
    final Map<String, Counts> extraction = new LinkedHashMap<>();

    void record(boolean triageStep, Outcome<?> outcome) {
        Map<String, Counts> models = triageStep ? triage : extraction;
        Counts counts = models.computeIfAbsent(outcome.used().model(), model -> new Counts());
        counts.calls++;
        counts.in += outcome.used().inputTokens();
        counts.out += outcome.used().outputTokens();
    }

    long calls(boolean triageStep) {
        return (triageStep ? triage : extraction).values().stream().mapToLong(counts -> counts.calls).sum();
    }

    long tokensIn(boolean triageStep) {
        return (triageStep ? triage : extraction).values().stream().mapToLong(counts -> counts.in).sum();
    }

    long tokensOut(boolean triageStep) {
        return (triageStep ? triage : extraction).values().stream().mapToLong(counts -> counts.out).sum();
    }

    BigDecimal cost(boolean triageStep, Rules.Price price) {
        return of(tokensIn(triageStep), tokensOut(triageStep), price);
    }

    /** Tokens at a price per million, exactly. */
    static BigDecimal of(long in, long out, Rules.Price price) {
        return BigDecimal.valueOf(in).multiply(price.in()).add(BigDecimal.valueOf(out).multiply(price.out())).divide(MILLION);
    }

    /**
     * What a thousand messages cost when {@code weddingShare} of them (as a fraction written as parts and whole, so
     * that nothing is rounded early) go through extraction too: every one is triaged.
     *
     * @param triageCost what triage cost in all, over {@code triageMessages} messages
     * @param extractionCost what extraction cost in all, over {@code extractionMessages} messages
     */
    static BigDecimal perThousand(
            BigDecimal triageCost, int triageMessages, BigDecimal extractionCost, int extractionMessages, int weddingParts, int whole) {
        BigDecimal triagePart = triageMessages == 0 ? BigDecimal.ZERO : triageCost.multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(triageMessages), SCALE, RoundingMode.HALF_UP);
        BigDecimal extractionPart = extractionMessages == 0
                ? BigDecimal.ZERO
                : extractionCost
                        .multiply(BigDecimal.valueOf(1000))
                        .multiply(BigDecimal.valueOf(weddingParts))
                        .divide(BigDecimal.valueOf((long) extractionMessages * whole), SCALE, RoundingMode.HALF_UP);
        return triagePart.add(extractionPart);
    }

    /** US dollars for a person to read: four decimals, because a message costs a fraction of a cent. */
    static String usd(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).toPlainString() + " USD";
    }

    /** A price for a person to read, without trailing zeros. */
    static String price(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
