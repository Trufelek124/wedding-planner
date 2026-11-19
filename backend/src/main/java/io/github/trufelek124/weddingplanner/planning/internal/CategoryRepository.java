package io.github.trufelek124.weddingplanner.planning.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CategoryRepository {

    record CategoryRow(UUID id, String name, int position) {}

    private final JdbcClient jdbc;

    CategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<CategoryRow> list(UUID weddingId) {
        return jdbc.sql("SELECT id, name, position FROM category WHERE wedding_id = ? ORDER BY position, lower(name)")
                .param(weddingId)
                .query(CategoryRow.class)
                .list();
    }

    /** The unique index on (wedding_id, lower(name)) makes at most one. */
    Optional<UUID> findByName(UUID weddingId, String name) {
        return jdbc.sql("SELECT id FROM category WHERE wedding_id = ? AND lower(name) = lower(btrim(?))")
                .params(weddingId, name)
                .query(UUID.class)
                .optional();
    }

    boolean exists(UUID weddingId, UUID categoryId) {
        return jdbc.sql("SELECT count(*) FROM category WHERE wedding_id = ? AND id = ?")
                        .params(weddingId, categoryId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** @return false when the wedding has a category of that name already (however it is capitalised) */
    boolean insert(UUID weddingId, UUID id, String name) {
        try {
            jdbc.sql("""
                    INSERT INTO category (id, wedding_id, name, position)
                    VALUES (?, ?, ?, (SELECT COALESCE(max(position), 0) + 1 FROM category WHERE wedding_id = ?))
                    """)
                    .params(id, weddingId, name, weddingId)
                    .update();
            return true;
        } catch (DuplicateKeyException sameName) {
            return false;
        }
    }

    /** @return 1 when renamed, 0 when there is no such category, -1 when the name is taken */
    int rename(UUID weddingId, UUID id, String name) {
        try {
            return jdbc.sql("UPDATE category SET name = ? WHERE wedding_id = ? AND id = ?").params(name, weddingId, id).update();
        } catch (DuplicateKeyException sameName) {
            return -1;
        }
    }

    boolean delete(UUID weddingId, UUID id) {
        return jdbc.sql("DELETE FROM category WHERE wedding_id = ? AND id = ?").params(weddingId, id).update() == 1;
    }

    /** @return true the first time it is called for a wedding, and never again */
    boolean markOffered(UUID weddingId, Instant now) {
        return jdbc.sql("""
                INSERT INTO planning_setup (wedding_id, categories_offered_at) VALUES (?, ?)
                ON CONFLICT (wedding_id) DO NOTHING
                """)
                        .params(weddingId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
