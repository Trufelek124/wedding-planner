package io.github.trufelek124.weddingplanner.mailreading.internal;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ReadingProperties.class, RoundProperties.class})
class ReadingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ReadingConfiguration.class);

    /**
     * How the round runs and what holds it back. On only when the key is given and every setting, the round's too, is valid:
     * otherwise the reading is off with one line that names the settings and never their values.
     */
    @Bean
    Rules readingRules(ReadingProperties properties, RoundProperties round) {
        ReadingSetup.Parsed parsed = ReadingSetup.parse(properties);
        RoundSetup.Parsed rules = RoundSetup.parse(round, parsed.config() != null);
        if (parsed.config() != null && !rules.invalid().isEmpty()) {
            log.warn("The reading of the mail is off: not valid: {}", String.join(", ", rules.invalid()));
        }
        return rules.rules();
    }

    /**
     * The one bean the two steps are: real when a key is given and the settings are valid, otherwise steps that ask
     * nothing. The client of the AI service is made here, once, and only in the first case.
     */
    @Bean(destroyMethod = "close")
    ReadingSteps readingSteps(ReadingProperties properties, Rules rules) {
        ReadingSetup.Parsed parsed = ReadingSetup.parse(properties);
        if (parsed.config() == null) {
            if (parsed.notSet()) {
                log.info("The reading of the mail is off: not set: {}", ReadingSetup.API_KEY);
            } else {
                // Names only, never a value.
                log.warn("The reading of the mail is off: not valid: {}", String.join(", ", parsed.invalid()));
            }
            return new NoSteps();
        }
        if (!rules.on()) {
            // The key is there; one of the settings of the round is not valid (the rules bean above said which).
            return new NoSteps();
        }
        ReadingSetup.Config config = parsed.config();
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(config.apiKey()).timeout(config.timeout());
        // Empty in production: the SDK's own address. A test points it at its stand-in.
        Optional.ofNullable(config.baseUrl()).ifPresent(builder::baseUrl);
        AnthropicClient client = builder.build();
        log.info("The reading of the mail is set up: triage {}, extraction {}", config.triageModel(), config.extractionModel());
        return new AnthropicSteps(config, client, Instructions.load());
    }
}
