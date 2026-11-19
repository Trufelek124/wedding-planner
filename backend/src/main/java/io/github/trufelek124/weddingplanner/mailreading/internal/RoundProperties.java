package io.github.trufelek124.weddingplanner.mailreading.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of the round and its brakes, as they were given: every one as text, so that a value that is not valid
 * switches the reading off (see {@link RoundSetup}) instead of stopping the server at its start.
 */
@ConfigurationProperties("app.reading")
record RoundProperties(
        String interval,
        String automatic,
        String batch,
        String triageSure,
        String monthlyLimitUsd,
        String dailyMessages,
        String dailySuggestionsPerMailbox,
        String triagePriceIn,
        String triagePriceOut,
        String extractionPriceIn,
        String extractionPriceOut) {}
