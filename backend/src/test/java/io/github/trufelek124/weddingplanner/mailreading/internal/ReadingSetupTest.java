package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadingSetupTest {

    private static final String KEY = "sk-test-not-a-real-key-0123456789abcdef";

    private static ReadingProperties properties(String key, String baseUrl, String triage, String extraction, Duration timeout) {
        return new ReadingProperties(key, baseUrl, triage, extraction, timeout);
    }

    private static ReadingProperties good() {
        return properties(KEY, null, "claude-haiku-5-5", "claude-opus-5-5", Duration.ofMinutes(5));
    }

    @Test
    void noKeyMeansOff() {
        for (String none : new String[] {null, "", "   "}) {
            ReadingSetup.Parsed parsed = ReadingSetup.parse(properties(none, null, "claude-haiku-5-5", "claude-opus-5-5", Duration.ofMinutes(5)));

            assertThat(parsed.config()).isNull();
            assertThat(parsed.notSet()).isTrue();
        }
    }

    @Test
    void aKeyAndValidSettingsMeanOn() {
        ReadingSetup.Parsed parsed = ReadingSetup.parse(properties("  " + KEY + " ", "", "claude-haiku-5-5", "claude-opus-5-5", Duration.ofMinutes(5)));

        assertThat(parsed.config()).isNotNull();
        assertThat(parsed.config().apiKey()).isEqualTo(KEY);
        assertThat(parsed.config().baseUrl()).isNull();
        assertThat(parsed.config().triageModel()).isEqualTo("claude-haiku-5-5");
        assertThat(parsed.config().extractionModel()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void aSettingThatIsNotValidMeansOffAndIsNamedWithoutItsValue() {
        assertThat(ReadingSetup.parse(properties("has a space in it", null, "claude-haiku-5-5", "claude-opus-5-5", Duration.ofMinutes(5))).invalid())
                .containsExactly("APP_AI_API_KEY");
        assertThat(ReadingSetup.parse(properties(KEY, null, "Claude Haiku", "claude-opus-5-5", Duration.ofMinutes(5))).invalid())
                .containsExactly("APP_AI_TRIAGE_MODEL");
        assertThat(ReadingSetup.parse(properties(KEY, null, "claude-haiku-5-5", "", Duration.ofMinutes(5))).invalid())
                .containsExactly("APP_AI_EXTRACTION_MODEL");
        assertThat(ReadingSetup.parse(properties(KEY, null, "claude-haiku-5-5", "claude-opus-5-5", Duration.ZERO)).invalid())
                .containsExactly("app.reading.request-timeout");
        assertThat(ReadingSetup.parse(properties(KEY, null, "claude-haiku-5-5", "claude-opus-5-5", null)).invalid())
                .containsExactly("app.reading.request-timeout");
        ReadingSetup.Parsed parsed = ReadingSetup.parse(properties(KEY, "http://elsewhere.example", "claude-haiku-5-5", "claude-opus-5-5", Duration.ofMinutes(5)));
        assertThat(parsed.config()).isNull();
        assertThat(parsed.notSet()).isFalse();
        assertThat(parsed.invalid()).containsExactly("app.reading.base-url");
    }

    @Test
    void theKeyOnlyGoesToHttpsOrToThisMachine() {
        assertThat(List.of("https://api.anthropic.example", "http://localhost:8099", "http://127.0.0.1:8099")).allMatch(ReadingSetup::acceptable);
        assertThat(List.of("http://api.anthropic.example", "ftp://localhost", "https://user:pass@api.anthropic.example", "https://x.example/?a=b",
                "not an address", "https://")).noneMatch(ReadingSetup::acceptable);
    }

    @Test
    void nothingPrintsTheKey() {
        assertThat(good().toString()).doesNotContain(KEY);
        assertThat(ReadingSetup.parse(good()).config().toString()).doesNotContain(KEY);
        assertThat(ReadingSetup.parse(good()).toString()).doesNotContain(KEY);
    }
}
