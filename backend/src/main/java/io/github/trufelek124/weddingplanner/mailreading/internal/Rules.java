package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * How the round runs and what holds it back, as the settings gave it and as {@link RoundSetup} checked it.
 *
 * @param on whether the reading is on at all: a key is given and every setting is valid
 * @param triageSure a "no" less sure than this (0 to 100) is read on
 * @param documents whether the text of a message's PDF documents is read at all (D-011)
 * @param documentsSure how sure triage must be, in 100, that a message concerns the wedding for its documents to be read
 */
record Rules(
        boolean on,
        Duration interval,
        boolean automatic,
        int batch,
        int triageSure,
        boolean documents,
        int documentsSure,
        BigDecimal monthlyLimitUsd,
        int dailyMessages,
        int dailySuggestionsPerMailbox,
        Price triagePrice,
        Price extractionPrice) {

    /** US dollars per million tokens. */
    record Price(BigDecimal in, BigDecimal out) {}
}
