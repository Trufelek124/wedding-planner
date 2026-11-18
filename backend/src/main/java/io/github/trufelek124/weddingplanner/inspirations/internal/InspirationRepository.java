package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.Source;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InspirationRepository {

    /**
     * An inspiration as it is stored.
     *
     * @param url null for pictures of one's own
     * @param tags in lower case, in alphabetical order
     * @param planItemId the plan item the idea belongs to, if any
     * @param addedBy the member who saved it; null once that member is gone
     */
    record InspirationRow(
            UUID id,
            String url,
            Source source,
            String title,
            String note,
            List<String> tags,
            UUID boardId,
            UUID planItemId,
            UUID addedBy,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** What a list may be narrowed down by; null means "any". */
    record Filter(UUID boardId, Boolean withoutBoard, String tag, UUID planItemId) {}

    record TagCount(String tag, int count) {}

    private static final String SELECT = """
            SELECT i.id, i.url, i.source, i.title, i.note, i.board_id, i.entry_id, i.added_by, i.version,
                   i.created_at, i.updated_at,
                   ARRAY(SELECT t.tag FROM inspiration_tag t WHERE t.inspiration_id = i.id ORDER BY t.tag) AS tags
            FROM inspiration i
            """;

    private final JdbcClient jdbc;

    InspirationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<InspirationRow> find(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE i.wedding_id = ? AND i.id = ?")
                .params(weddingId, id)
                .query(InspirationRepository::row)
                .optional();
    }

    /** The newest first. */
    List<InspirationRow> list(UUID weddingId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE i.wedding_id = :wedding");
        Map<String, Object> params = new HashMap<>();
        params.put("wedding", weddingId);
        if (filter.boardId() != null) {
            where.append(" AND i.board_id = :board");
            params.put("board", filter.boardId());
        }
        if (Boolean.TRUE.equals(filter.withoutBoard())) {
            where.append(" AND i.board_id IS NULL");
        }
        if (filter.tag() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM inspiration_tag t WHERE t.inspiration_id = i.id AND t.tag = :tag)");
            params.put("tag", filter.tag());
        }
        if (filter.planItemId() != null) {
            where.append(" AND i.entry_id = :planItem");
            params.put("planItem", filter.planItemId());
        }
        return jdbc.sql(SELECT + where + " ORDER BY i.created_at DESC, i.id").params(params).query(InspirationRepository::row).list();
    }

    void insert(UUID weddingId, InspirationRow inspiration) {
        jdbc.sql("""
                INSERT INTO inspiration (id, wedding_id, url, source, title, note, board_id, entry_id, version,
                                         added_by, created_at, updated_at)
                VALUES (:id, :wedding, :url, :source, :title, :note, :board, :planItem, 0, :addedBy, :now, :now)
                """)
                .params(fields(inspiration))
                .param("id", inspiration.id())
                .param("wedding", weddingId)
                .param("addedBy", inspiration.addedBy())
                .param("now", at(inspiration.createdAt()))
                .update();
        replaceTags(inspiration.id(), inspiration.tags());
    }

    /** Saves the inspiration if it still has the version the change was based on. */
    boolean update(UUID weddingId, InspirationRow inspiration, int basedOnVersion, Instant now) {
        boolean saved = jdbc.sql("""
                UPDATE inspiration
                SET url = :url, source = :source, title = :title, note = :note, board_id = :board, entry_id = :planItem,
                    version = version + 1, updated_at = :now
                WHERE wedding_id = :wedding AND id = :id AND version = :version
                """)
                        .params(fields(inspiration))
                        .param("id", inspiration.id())
                        .param("wedding", weddingId)
                        .param("version", basedOnVersion)
                        .param("now", at(now))
                        .update()
                == 1;
        if (saved) {
            replaceTags(inspiration.id(), inspiration.tags());
        }
        return saved;
    }

    boolean delete(UUID weddingId, UUID id) {
        return jdbc.sql("DELETE FROM inspiration WHERE wedding_id = ? AND id = ?").params(weddingId, id).update() == 1;
    }

    /** @return the ids of the inspirations that went with the board */
    List<UUID> deleteOfBoard(UUID weddingId, UUID boardId) {
        return jdbc.sql("DELETE FROM inspiration WHERE wedding_id = ? AND board_id = ? RETURNING id")
                .params(weddingId, boardId)
                .query(UUID.class)
                .list();
    }

    /** Every tag in use, with how many inspirations carry it, in alphabetical order. */
    List<TagCount> tags(UUID weddingId) {
        return jdbc.sql("""
                SELECT t.tag, count(*) AS count
                FROM inspiration_tag t JOIN inspiration i ON i.id = t.inspiration_id
                WHERE i.wedding_id = ?
                GROUP BY t.tag
                ORDER BY t.tag
                """)
                .param(weddingId)
                .query(TagCount.class)
                .list();
    }

    private void replaceTags(UUID inspirationId, List<String> tags) {
        jdbc.sql("DELETE FROM inspiration_tag WHERE inspiration_id = ?").param(inspirationId).update();
        for (String tag : tags) {
            jdbc.sql("INSERT INTO inspiration_tag (inspiration_id, tag) VALUES (?, ?)").params(inspirationId, tag).update();
        }
    }

    private static Map<String, Object> fields(InspirationRow inspiration) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("url", inspiration.url());
        fields.put("source", inspiration.source().name());
        fields.put("title", inspiration.title());
        fields.put("note", inspiration.note());
        fields.put("board", inspiration.boardId());
        fields.put("planItem", inspiration.planItemId());
        return fields;
    }

    private static InspirationRow row(ResultSet row, int number) throws SQLException {
        String[] tags = (String[]) row.getArray("tags").getArray();
        return new InspirationRow(
                row.getObject("id", UUID.class),
                row.getString("url"),
                Source.valueOf(row.getString("source")),
                row.getString("title"),
                row.getString("note"),
                List.of(tags),
                row.getObject("board_id", UUID.class),
                row.getObject("entry_id", UUID.class),
                row.getObject("added_by", UUID.class),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
