package io.github.trufelek124.weddingplanner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The application's clock in tests: real time plus whatever a test has moved it forward by. */
public final class TestClock extends Clock {

    private static volatile Duration offset = Duration.ZERO;

    private final ZoneId zone;

    private TestClock(ZoneId zone) {
        this.zone = zone;
    }

    public static void advance(Duration by) {
        offset = offset.plus(by);
    }

    public static void reset() {
        offset = Duration.ZERO;
    }

    /** The moment the application believes it is. */
    public static Instant now() {
        return Instant.now().plus(offset);
    }

    @Override
    public Instant instant() {
        return now();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(zone);
    }

    @TestConfiguration
    public static class Configuration {

        @Bean
        @Primary
        Clock testClock() {
            return new TestClock(ZoneOffset.UTC);
        }
    }
}
