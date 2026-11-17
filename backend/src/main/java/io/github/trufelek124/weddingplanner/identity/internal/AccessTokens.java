package io.github.trufelek124.weddingplanner.identity.internal;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Access tokens are what a client shows with every request: a short-lived signed statement
 * "this is user X", checked without a database lookup.
 */
@Component
class AccessTokens {

    static final String ISSUER = "wedding-planner";

    record Issued(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "Issued[expiresAt=" + expiresAt + "]";
        }
    }

    private final Clock clock;
    private final Duration ttl;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    AccessTokens(IdentityProperties properties, Clock clock) {
        this.clock = clock;
        this.ttl = properties.accessTokenTtl();
        SecretKey key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        // Only our algorithm, our issuer, and not expired (no tolerance: both ends use this server's clock).
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator notExpired = new JwtTimestampValidator(Duration.ZERO);
        notExpired.setClock(clock);
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(notExpired, new JwtIssuerValidator(ISSUER)));
        this.decoder = nimbus;
    }

    Issued issue(UUID userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new Issued(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }

    JwtDecoder decoder() {
        return decoder;
    }
}
