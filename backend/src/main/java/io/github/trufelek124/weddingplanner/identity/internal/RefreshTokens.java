package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.identity.internal.RefreshTokenRepository.StoredToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh tokens keep a person signed in. Each is a random value that is used once: using it
 * yields its successor. The database holds only hashes, so a copy of the database signs nobody in.
 */
@Service
class RefreshTokens {

    /**
     * Two requests of the same client can present the same token at the same moment (two browser
     * tabs). Within this time a second use is taken for that, not for theft.
     */
    static final Duration REUSE_LEEWAY = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RefreshTokens.class);

    record Rotation(UUID userId, String refreshToken) {

        @Override
        public String toString() {
            return "Rotation[user=" + userId + "]";
        }
    }

    private final RefreshTokenRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    RefreshTokens(RefreshTokenRepository repository, Clock clock, IdentityProperties properties) {
        this.repository = repository;
        this.clock = clock;
        this.ttl = properties.refreshTokenTtl();
    }

    /** Starts a new session for the user. */
    @Transactional
    String issue(UUID userId) {
        return issue(userId, UUID.randomUUID());
    }

    /**
     * Exchanges a token for its successor.
     *
     * @return empty when the token is unknown, expired, revoked, or was used before; in the last
     *     case every token of that session is revoked, because someone may have copied one
     */
    @Transactional
    Optional<Rotation> rotate(String presented) {
        Instant now = clock.instant();
        Optional<StoredToken> found = repository.findByHash(hash(presented));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StoredToken token = found.get();
        if (token.revokedAt() != null || !token.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        if (token.usedAt() != null && token.usedAt().plus(REUSE_LEEWAY).isBefore(now)) {
            repository.revokeFamily(token.familyId(), now);
            log.warn("A refresh token was presented a second time; the session of user {} is revoked", token.userId());
            return Optional.empty();
        }
        if (token.usedAt() == null) {
            // False means a request running at this very moment used it first, which the leeway allows too.
            repository.markUsed(token.id(), now);
        }
        return Optional.of(new Rotation(token.userId(), issue(token.userId(), token.familyId())));
    }

    /** Ends the session the token belongs to. An unknown token is ignored. */
    @Transactional
    void revoke(String presented) {
        repository.findByHash(hash(presented))
                .ifPresent(token -> repository.revokeFamily(token.familyId(), clock.instant()));
    }

    private String issue(UUID userId, UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        repository.insert(userId, familyId, hash(token), now, now.plus(ttl));
        return token;
    }

    static String hash(String token) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
