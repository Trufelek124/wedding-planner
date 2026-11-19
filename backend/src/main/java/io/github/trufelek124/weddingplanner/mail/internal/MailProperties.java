package io.github.trufelek124.weddingplanner.mail.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of the e-mail import, as they were given. Nothing is checked here: {@link MailSetup} decides whether
 * they are enough, so that a missing or wrong one switches the import off instead of stopping the server.
 */
@ConfigurationProperties("app.mail")
record MailProperties(
        String googleClientId,
        String googleClientSecret,
        String publicUrl,
        String tokenKey,
        String authorizationUri,
        String tokenUri,
        String revokeUri,
        String gmailUri,
        Duration googleTimeout) {

    @Override
    public String toString() {
        // The secret and the key are in here.
        return "MailProperties[]";
    }
}
