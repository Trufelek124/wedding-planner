package io.github.trufelek124.weddingplanner.identity.internal;

import static io.github.trufelek124.weddingplanner.identity.internal.UserRepository.at;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

    record StoredToken(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant usedAt, Instant revokedAt) {}

    private final JdbcClient jdbc;

    RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, UUID familyId, String tokenHash, Instant now, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO refresh_token (id, user_id, family_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(UUID.randomUUID(), userId, familyId, tokenHash, at(now), at(expiresAt))
                .update();
    }

    Optional<StoredToken> findByHash(String tokenHash) {
        return jdbc.sql("""
                SELECT id, user_id, family_id, expires_at, used_at, revoked_at
                FROM refresh_token WHERE token_hash = ?
                """)
                .param(tokenHash)
                .query((row, n) -> new StoredToken(
                        row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getObject("family_id", UUID.class),
                        instant(row.getObject("expires_at", OffsetDateTime.class)),
                        instant(row.getObject("used_at", OffsetDateTime.class)),
                        instant(row.getObject("revoked_at", OffsetDateTime.class))))
                .optional();
    }

    /** @return false when the token had been used already (by a request running at the same moment) */
    boolean markUsed(UUID id, Instant now) {
        return jdbc.sql("UPDATE refresh_token SET used_at = ? WHERE id = ? AND used_at IS NULL")
                        .params(at(now), id)
                        .update()
                == 1;
    }

    void revokeFamily(UUID familyId, Instant now) {
        jdbc.sql("UPDATE refresh_token SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")
                .params(at(now), familyId)
                .update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
