package io.github.trufelek124.weddingplanner.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdentityPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aSecretOfThirtyTwoCharactersIsAccepted() {
        assertThat(validator.validate(withSecret("x".repeat(32)))).isEmpty();
    }

    @Test
    void aShortOrMissingSecretIsRefused() {
        assertThat(validator.validate(withSecret("x".repeat(31)))).isNotEmpty();
        assertThat(validator.validate(withSecret(""))).isNotEmpty();
        assertThat(validator.validate(withSecret(null))).isNotEmpty();
    }

    @Test
    void neverPrintsTheSecretOrTheAddresses() {
        IdentityProperties properties = new IdentityProperties(
                "the-secret-that-signs-everything-0123456789", "client", "anna@example.com", false,
                URI.create("https://example.org/certs"), Duration.ofMinutes(15), Duration.ofDays(60));

        assertThat(properties.toString()).doesNotContain("the-secret").doesNotContain("anna@");
    }

    private static IdentityProperties withSecret(String secret) {
        return new IdentityProperties(
                secret, "", "", false, URI.create("https://example.org/certs"), Duration.ofMinutes(15), Duration.ofDays(60));
    }
}
