package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether the reading is set up, and with what. It is on only when a key is given, and the other settings are valid.
 * Off, no client of the AI service is made and nothing is ever asked of it.
 */
final class ReadingSetup {

    /** The names an operator knows the settings by, for saying which one is not valid. Never a value. */
    static final String API_KEY = "APP_AI_API_KEY";
    static final String BASE_URL = "app.reading.base-url";
    static final String TRIAGE_MODEL = "APP_AI_TRIAGE_MODEL";
    static final String EXTRACTION_MODEL = "APP_AI_EXTRACTION_MODEL";
    static final String TIMEOUT = "app.reading.request-timeout";

    private static final Duration LONGEST_WAIT = Duration.ofMinutes(15);
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{8,300}");
    private static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9._-]{1,99}");

    /** What the reading runs on. It holds the key: it prints nothing of itself. */
    record Config(String apiKey, String baseUrl, String triageModel, String extractionModel, Duration timeout) {

        @Override
        public String toString() {
            return "Config[]";
        }
    }

    /** What was made of the settings: the means, or why the reading is off. {@code notSet} is true when there is no key at all. */
    record Parsed(Config config, boolean notSet, List<String> invalid) {}

    private ReadingSetup() {}

    static Parsed parse(ReadingProperties properties) {
        String key = text(properties.apiKey());
        if (key.isEmpty()) {
            return new Parsed(null, true, List.of());
        }
        List<String> invalid = new ArrayList<>();
        if (!KEY.matcher(key).matches()) {
            invalid.add(API_KEY);
        }
        String base = text(properties.baseUrl());
        if (!base.isEmpty() && !acceptable(base)) {
            invalid.add(BASE_URL);
        }
        String triage = text(properties.triageModel());
        if (!MODEL.matcher(triage).matches()) {
            invalid.add(TRIAGE_MODEL);
        }
        String extraction = text(properties.extractionModel());
        if (!MODEL.matcher(extraction).matches()) {
            invalid.add(EXTRACTION_MODEL);
        }
        Duration timeout = properties.requestTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(LONGEST_WAIT) > 0) {
            invalid.add(TIMEOUT);
        }
        if (!invalid.isEmpty()) {
            return new Parsed(null, false, List.copyOf(invalid));
        }
        return new Parsed(new Config(key, base.isEmpty() ? null : base, triage, extraction, timeout), false, List.of());
    }

    /** The key travels to this address: https, or http on this machine for a developer and the tests. No user name. */
    static boolean acceptable(String address) {
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return false;
            }
            return scheme.equals("https") || (scheme.equals("http") && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")));
        } catch (URISyntaxException notAnAddress) {
            return false;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
