package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.PreviewStatus;
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
     * @param vendorId the vendor the idea concerns, if any
     * @param addedBy the member who saved it; null once that member is gone
     * @param previewTitle the title the linked page gives itself; empty when none was fetched
     * @param x where its card lies on the canvas: the left edge
     * @param y the top edge
     * @param placedAt when it was put there; a move changes neither the version nor updatedAt
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
            UUID vendorId,
            UUID addedBy,
            PreviewStatus previewStatus,
            String previewTitle,
            int version,
            Instant createdAt,
            Instant updatedAt,
            int x,
            int y,
            Instant placedAt) {}

    /** A link whose preview is still to be fetched. */
    record PendingPreview(UUID id, String url, int attempts) {}

    /** What a list may be narrowed down by; null means "any". */
    record Filter(UUID boardId, Boolean withoutBoard, String tag, UUID planItemId, UUID vendorId) {}

    record TagCount(String tag, int count) {}

    private static final String SELECT = """
            SELECT i.id, i.url, i.source, i.title, i.note, i.board_id, i.entry_id, i.vendor_id, i.added_by, i.preview_status,
                   i.preview_title, i.version, i.created_at, i.updated_at, i.x, i.y, i.placed_at,
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
        if (filter.vendorId() != null) {
            where.append(" AND i.vendor_id = :vendor");
            params.put("vendor", filter.vendorId());
        }
        return jdbc.sql(SELECT + where + " ORDER BY i.created_at DESC, i.id").params(params).query(InspirationRepository::row).list();
    }

    /** How many inspirations point to each vendor that any points to. */
    Map<UUID, Integer> countByVendor(UUID weddingId) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("SELECT vendor_id, count(*) AS inspirations FROM inspiration WHERE wedding_id = ? AND vendor_id IS NOT NULL GROUP BY vendor_id")
                .param(weddingId)
                .query(row -> {
                    counts.put(row.getObject("vendor_id", UUID.class), row.getInt("inspirations"));
                });
        return counts;
    }

    /** Whether any inspiration belongs to this plan item. */
    boolean anyOnPlanItem(UUID weddingId, UUID planItemId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM inspiration WHERE wedding_id = ? AND entry_id = ?)")
                .params(weddingId, planItemId)
                .query(Boolean.class)
                .single();
    }

    void insert(UUID weddingId, InspirationRow inspiration) {
        jdbc.sql("""
                INSERT INTO inspiration (id, wedding_id, url, source, title, note, board_id, entry_id, vendor_id, version,
                                         added_by, preview_status, preview_next_at, created_at, updated_at, x, y, placed_at)
                VALUES (:id, :wedding, :url, :source, :title, :note, :board, :planItem, :vendor, 0, :addedBy,
                        :preview, CASE WHEN :preview = 'PENDING' THEN :now END, :now, :now, :x, :y, :now)
                """)
                .params(fields(inspiration))
                .param("id", inspiration.id())
                .param("wedding", weddingId)
                .param("addedBy", inspiration.addedBy())
                .param("preview", inspiration.previewStatus().name())
                .param("now", at(inspiration.createdAt()))
                .param("x", inspiration.x())
                .param("y", inspiration.y())
                .update();
        replaceTags(inspiration.id(), inspiration.tags());
    }

    /**
     * Holds the wedding's canvas until the end of the transaction, so that two ideas that arrive at the same moment
     * each see the place the other took.
     */
    void holdCanvas(UUID weddingId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("inspiration-canvas:" + weddingId)
                .query()
                .listOfRows();
    }

    /** Where every idea of the wedding lies. */
    List<CanvasPlacement.Place> places(UUID weddingId) {
        return jdbc.sql("SELECT x, y FROM inspiration WHERE wedding_id = ?")
                .param(weddingId)
                .query((row, number) -> new CanvasPlacement.Place(row.getInt("x"), row.getInt("y")))
                .list();
    }

    /** Puts the card somewhere else. Not a change of the idea: its version and updated_at stay. */
    boolean place(UUID weddingId, UUID id, int x, int y, Instant now) {
        return jdbc.sql("UPDATE inspiration SET x = ?, y = ?, placed_at = ? WHERE wedding_id = ? AND id = ?")
                .params(x, y, at(now), weddingId, id)
                .update()
                == 1;
    }

    /** Saves the inspiration if it still has the version the change was based on. */
    boolean update(UUID weddingId, InspirationRow inspiration, int basedOnVersion, Instant now) {
        boolean saved = jdbc.sql("""
                UPDATE inspiration
                SET url = :url, source = :source, title = :title, note = :note, board_id = :board, entry_id = :planItem,
                    vendor_id = :vendor, version = version + 1, updated_at = :now
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

    // ------------------------------------------------------------ previews

    /** The link changed, or went: what was fetched for the old one no longer applies. */
    void startPreviewAgain(UUID id, PreviewStatus status, Instant now) {
        jdbc.sql("""
                UPDATE inspiration
                SET preview_status = :status, preview_title = '', preview_attempts = 0,
                    preview_next_at = CASE WHEN :status = 'PENDING' THEN :now END
                WHERE id = :id
                """)
                .param("status", status.name())
                .param("now", at(now))
                .param("id", id)
                .update();
    }

    /** The links whose turn has come, the longest waiting first. */
    List<PendingPreview> pendingPreviews(Instant now, int atMost) {
        return jdbc.sql("""
                SELECT id, url, preview_attempts AS attempts FROM inspiration
                WHERE preview_status = 'PENDING' AND url IS NOT NULL AND preview_next_at <= ?
                ORDER BY preview_next_at, id
                LIMIT ?
                """)
                .params(at(now), atMost)
                .query(PendingPreview.class)
                .list();
    }

    /** Whether the inspiration still has this link and still waits for its preview. Holds the row until the end of the transaction. */
    boolean stillWaitsForPreview(UUID id, String url) {
        return !jdbc.sql("SELECT 1 FROM inspiration WHERE id = ? AND url = ? AND preview_status = 'PENDING' FOR UPDATE")
                .params(id, url)
                .query(Integer.class)
                .list()
                .isEmpty();
    }

    /**
     * Records how the fetch ended, unless the link was changed or removed while it ran.
     *
     * @return false when what was fetched is for a link the inspiration no longer has
     */
    boolean finishPreview(UUID id, String url, PreviewStatus status, String title) {
        return jdbc.sql("""
                UPDATE inspiration SET preview_status = ?, preview_title = ?, preview_next_at = NULL
                WHERE id = ? AND url = ? AND preview_status = 'PENDING'
                """)
                        .params(status.name(), title, id, url)
                        .update()
                == 1;
    }

    void tryPreviewLater(UUID id, String url, int attempts, Instant nextAt) {
        jdbc.sql("""
                UPDATE inspiration SET preview_attempts = ?, preview_next_at = ?
                WHERE id = ? AND url = ? AND preview_status = 'PENDING'
                """)
                .params(attempts, at(nextAt), id, url)
                .update();
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
        fields.put("vendor", inspiration.vendorId());
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
                row.getObject("vendor_id", UUID.class),
                row.getObject("added_by", UUID.class),
                PreviewStatus.valueOf(row.getString("preview_status")),
                row.getString("preview_title"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getInt("x"),
                row.getInt("y"),
                row.getObject("placed_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
