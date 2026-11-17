package io.github.trufelek124.weddingplanner.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Checks a Google ID token: signed by Google, issued for this planner's own client, not expired,
 * and about an address Google has verified. Anything else is "not signed in".
 */
@Component
class GoogleIdTokenVerifier {

    record GoogleAccount(String subject, String email, String name) {

        @Override
        public String toString() {
            return "GoogleAccount[hidden]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final JwtDecoder decoder;

    GoogleIdTokenVerifier(IdentityProperties properties, Clock clock) {
        String clientId = properties.googleClientId();
        if (clientId.isEmpty()) {
            log.warn("GOOGLE_OAUTH_WEB_CLIENT_ID is not set: every sign-in with Google will be refused");
            this.decoder = null;
            return;
        }
        // Google's keys are fetched on first use and cached; a slow answer must not hang a sign-in.
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(5));
        http.setReadTimeout(Duration.ofSeconds(5));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(properties.googleJwksUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(http))
                .build();
        JwtTimestampValidator notExpired = new JwtTimestampValidator(Duration.ofSeconds(60));
        notExpired.setClock(clock);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                notExpired,
                require("iss", jwt -> jwt.getClaimAsString("iss") != null && ISSUERS.contains(jwt.getClaimAsString("iss"))),
                require("aud", jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)),
                require("sub", jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()),
                require("email", jwt -> jwt.getClaimAsString("email") != null && !jwt.getClaimAsString("email").isBlank()),
                require("email_verified", jwt -> Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")))));
        this.decoder = nimbus;
    }

    Optional<GoogleAccount> verify(String idToken) {
        if (decoder == null) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            return Optional.of(new GoogleAccount(jwt.getSubject(), jwt.getClaimAsString("email"), jwt.getClaimAsString("name")));
        } catch (JwtException e) {
            // The reason is for whoever debugs a failing sign-in; it holds no token and no address.
            log.debug("A Google ID token was refused: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static OAuth2TokenValidator<Jwt> require(String claim, java.util.function.Predicate<Jwt> rule) {
        OAuth2Error error = new OAuth2Error("invalid_token", "The " + claim + " claim is not acceptable", null);
        return jwt -> rule.test(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
    }
}
