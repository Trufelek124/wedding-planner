package io.github.trufelek124.weddingplanner.suggestions.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** How sure the import has to be before it adds by itself: one setting for the wedding. */
@Repository
class SuggestionSettingsRepository {

    /** What a wedding that never changed it has: on, at 85. */
    static final Settings DEFAULT = new Settings(true, 85);

    record Settings(boolean addsByItself, int threshold) {}

    private final JdbcClient jdbc;

    SuggestionSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Settings of(UUID weddingId) {
        return jdbc.sql("SELECT adds_by_itself, threshold FROM suggestion_setting WHERE wedding_id = ?")
                .param(weddingId)
                .query((row, number) -> new Settings(row.getBoolean("adds_by_itself"), row.getInt("threshold")))
                .optional()
                .orElse(DEFAULT);
    }

    void save(UUID weddingId, Settings settings, Instant now) {
        jdbc.sql("""
                INSERT INTO suggestion_setting (wedding_id, adds_by_itself, threshold, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (wedding_id) DO UPDATE
                SET adds_by_itself = EXCLUDED.adds_by_itself, threshold = EXCLUDED.threshold, updated_at = EXCLUDED.updated_at
                """)
                .params(weddingId, settings.addsByItself(), settings.threshold(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
