package io.github.trufelek124.weddingplanner.planning.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DependencyRepository {

    /** "entry" can only start when "dependsOn" is finished. */
    record Link(UUID entryId, UUID dependsOnId) {}

    private final JdbcClient jdbc;

    DependencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every link of the wedding. There are few enough to hold them all. */
    List<Link> links(UUID weddingId) {
        return jdbc.sql("""
                SELECT d.entry_id, d.depends_on_id
                FROM entry_dependency d JOIN entry e ON e.id = d.entry_id
                WHERE e.wedding_id = ?
                """)
                .param(weddingId)
                .query(Link.class)
                .list();
    }

    /** Whether this entry waits for another one, or another waits for it. */
    boolean anyOf(UUID entryId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM entry_dependency WHERE entry_id = ? OR depends_on_id = ?)")
                .params(entryId, entryId)
                .query(Boolean.class)
                .single();
    }

    void add(UUID entryId, UUID dependsOnId) {
        jdbc.sql("INSERT INTO entry_dependency (entry_id, depends_on_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(entryId, dependsOnId)
                .update();
    }

    /** @return false when there was no such link */
    boolean remove(UUID entryId, UUID dependsOnId) {
        return jdbc.sql("DELETE FROM entry_dependency WHERE entry_id = ? AND depends_on_id = ?")
                        .params(entryId, dependsOnId)
                        .update()
                == 1;
    }

    /** A task has no dependencies: an entry that becomes one loses its links in both directions. */
    void removeAllOf(UUID entryId) {
        jdbc.sql("DELETE FROM entry_dependency WHERE entry_id = ? OR depends_on_id = ?").params(entryId, entryId).update();
    }
}
