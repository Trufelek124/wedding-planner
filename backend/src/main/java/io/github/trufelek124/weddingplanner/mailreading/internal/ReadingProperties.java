package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of the AI service, as they were given. Nothing is checked here: {@link ReadingSetup} decides whether
 * they are enough, so that a missing or wrong one switches the reading off instead of stopping the server.
 */
@ConfigurationProperties("app.reading")
record ReadingProperties(String apiKey, String baseUrl, String triageModel, String extractionModel, Duration requestTimeout) {

    @Override
    public String toString() {
        // The key is in here.
        return "ReadingProperties[]";
    }
}
