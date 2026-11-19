package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.TestClock;
import java.time.Duration;

/** Moves the application's clock; the clock is put back after every test. */
final class TestClockAdvance {

    private TestClockAdvance() {}

    static void minutes(long minutes) {
        TestClock.advance(Duration.ofMinutes(minutes));
    }

    static void days(long days) {
        TestClock.advance(Duration.ofDays(days));
    }

    static void seconds(long seconds) {
        TestClock.advance(Duration.ofSeconds(seconds));
    }
}
