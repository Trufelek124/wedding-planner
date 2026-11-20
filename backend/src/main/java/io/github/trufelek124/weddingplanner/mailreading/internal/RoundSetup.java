package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.convert.DurationStyle;

/** Checks the settings of the round. A value that is not valid is named (never shown) and the reading is off. */
final class RoundSetup {

    static final String INTERVAL = "MAIL_READING_INTERVAL";
    static final String AUTOMATIC = "MAIL_READING_ENABLED";
    static final String BATCH = "app.reading.batch";
    static final String TRIAGE_SURE = "MAIL_READING_TRIAGE_SURE";
    static final String DOCUMENTS = "MAIL_READING_DOCUMENTS_ENABLED";
    static final String DOCUMENTS_SURE = "MAIL_READING_DOCUMENTS_SURE";
    static final String MONTHLY_LIMIT = "MAIL_READING_MONTHLY_LIMIT_USD";
    static final String DAILY_MESSAGES = "MAIL_READING_DAILY_MESSAGES";
    static final String DAILY_SUGGESTIONS = "MAIL_READING_DAILY_SUGGESTIONS";
    static final String TRIAGE_PRICE_IN = "APP_AI_TRIAGE_PRICE_IN";
    static final String TRIAGE_PRICE_OUT = "APP_AI_TRIAGE_PRICE_OUT";
    static final String EXTRACTION_PRICE_IN = "APP_AI_EXTRACTION_PRICE_IN";
    static final String EXTRACTION_PRICE_OUT = "APP_AI_EXTRACTION_PRICE_OUT";

    private static final Duration SHORTEST = Duration.ofSeconds(1);
    private static final Duration LONGEST = Duration.ofDays(1);
    private static final BigDecimal MOST_MONEY = new BigDecimal("1000000000");
    private static final int MOST_BATCH = 100;
    private static final int MOST_COUNT = 1_000_000_000;

    /** @param rules valid; {@code on} is false when anything was not */
    record Parsed(Rules rules, List<String> invalid) {}

    private RoundSetup() {}

    /** @param keyGiven whether the AI service's key is there and valid: without it the rules are off whatever they say */
    static Parsed parse(RoundProperties properties, boolean keyGiven) {
        List<String> invalid = new ArrayList<>();
        Duration interval = duration(properties.interval(), invalid);
        Boolean automatic = bool(properties.automatic(), AUTOMATIC, invalid);
        int batch = whole(properties.batch(), 1, MOST_BATCH, BATCH, invalid);
        int sure = whole(properties.triageSure(), 0, 100, TRIAGE_SURE, invalid);
        Boolean documents = bool(properties.documents(), DOCUMENTS, invalid);
        int documentsSure = whole(properties.documentsSure(), 0, 100, DOCUMENTS_SURE, invalid);
        BigDecimal limit = money(properties.monthlyLimitUsd(), MONTHLY_LIMIT, invalid);
        int daily = whole(properties.dailyMessages(), 0, MOST_COUNT, DAILY_MESSAGES, invalid);
        int perMailbox = whole(properties.dailySuggestionsPerMailbox(), 0, MOST_COUNT, DAILY_SUGGESTIONS, invalid);
        Rules.Price triage = new Rules.Price(
                money(properties.triagePriceIn(), TRIAGE_PRICE_IN, invalid), money(properties.triagePriceOut(), TRIAGE_PRICE_OUT, invalid));
        Rules.Price extraction = new Rules.Price(
                money(properties.extractionPriceIn(), EXTRACTION_PRICE_IN, invalid),
                money(properties.extractionPriceOut(), EXTRACTION_PRICE_OUT, invalid));
        boolean valid = invalid.isEmpty();
        return new Parsed(
                new Rules(keyGiven && valid, valid ? interval : Duration.ofMinutes(1), valid && automatic, valid ? batch : 1, sure,
                        valid && documents, documentsSure, limit, daily, perMailbox, triage, extraction),
                List.copyOf(invalid));
    }

    private static Duration duration(String text, List<String> invalid) {
        try {
            Duration parsed = DurationStyle.detectAndParse(text == null ? "" : text.strip());
            if (parsed.compareTo(SHORTEST) >= 0 && parsed.compareTo(LONGEST) <= 0) {
                return parsed;
            }
        } catch (RuntimeException notADuration) {
            // Named below, never quoted.
        }
        invalid.add(INTERVAL);
        return Duration.ofMinutes(1);
    }

    private static Boolean bool(String text, String name, List<String> invalid) {
        String word = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (word.equals("true")) {
            return true;
        }
        if (word.equals("false")) {
            return false;
        }
        invalid.add(name);
        return false;
    }

    private static int whole(String text, int least, int most, String name, List<String> invalid) {
        try {
            int value = Integer.parseInt(text == null ? "" : text.strip());
            if (value >= least && value <= most) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // Named below, never quoted.
        }
        invalid.add(name);
        return least;
    }

    private static BigDecimal money(String text, String name, List<String> invalid) {
        try {
            BigDecimal value = new BigDecimal(text == null ? "" : text.strip());
            if (value.signum() >= 0 && value.compareTo(MOST_MONEY) <= 0) {
                return value;
            }
        } catch (NumberFormatException notANumber) {
            // Named below, never quoted.
        }
        invalid.add(name);
        return BigDecimal.ZERO;
    }
}
