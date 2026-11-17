package io.github.trufelek124.weddingplanner.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.identity")
record IdentityProperties(
        @NotBlank @Size(min = 32, message = "APP_JWT_SECRET must be at least 32 characters long") String jwtSecret,
        String googleClientId,
        String allowedEmails,
        boolean devLoginEnabled,
        @NotNull URI googleJwksUri,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {

    IdentityProperties {
        googleClientId = googleClientId == null ? "" : googleClientId.strip();
        allowedEmails = allowedEmails == null ? "" : allowedEmails;
    }

    @Override
    public String toString() {
        // Never the secret, never the addresses.
        return "IdentityProperties[devLoginEnabled=" + devLoginEnabled + "]";
    }
}
