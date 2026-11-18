package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PictureRepository {

    /**
     * A picture as it is recorded; the bytes are in the {@link PictureStore} under the same id.
     *
     * @param width as a person sees it; null for a kind the server cannot read
     * @param preview whether it was fetched from the link rather than sent by a person
     */
    record PictureRow(
            UUID id, UUID inspirationId, int position, PictureFormat format, long sizeBytes, Integer width, Integer height,
            boolean hasThumbnail, boolean preview, Instant createdAt) {}

    private static final String SELECT = """
            SELECT p.id, p.inspiration_id, p.position, p.content_type, p.size_bytes, p.width, p.height, p.has_thumbnail,
                   p.preview, p.created_at
            FROM inspiration_picture p
            """;

    private final JdbcClient jdbc;

    PictureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The pictures of these inspirations, each inspiration's in their order. */
    List<PictureRow> of(Collection<UUID> inspirationIds) {
        if (inspirationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE p.inspiration_id IN (:ids) ORDER BY p.inspiration_id, p.position, p.id")
                .param("ids", inspirationIds)
                .query(PictureRepository::row)
                .list();
    }

    /** The picture, if it is one of this inspiration of this wedding. */
    Optional<PictureRow> find(UUID weddingId, UUID inspirationId, UUID pictureId) {
        return jdbc.sql(SELECT + """
                 JOIN inspiration i ON i.id = p.inspiration_id
                 WHERE i.wedding_id = ? AND i.id = ? AND p.id = ?
                """)
                .params(weddingId, inspirationId, pictureId)
                .query(PictureRepository::row)
                .optional();
    }

    int count(UUID inspirationId) {
        return jdbc.sql("SELECT count(*) FROM inspiration_picture WHERE inspiration_id = ?")
                .param(inspirationId)
                .query(Integer.class)
                .single();
    }

    /** Puts the picture after the ones the inspiration has. */
    void insert(PictureRow picture) {
        jdbc.sql("""
                INSERT INTO inspiration_picture (id, inspiration_id, position, content_type, size_bytes, width, height,
                                                 has_thumbnail, preview, created_at)
                VALUES (:id, :inspiration,
                        (SELECT COALESCE(max(position), 0) + 1 FROM inspiration_picture WHERE inspiration_id = :inspiration),
                        :type, :size, :width, :height, :thumbnail, :preview, :now)
                """)
                .param("id", picture.id())
                .param("inspiration", picture.inspirationId())
                .param("type", picture.format().contentType())
                .param("size", picture.sizeBytes())
                .param("width", picture.width())
                .param("height", picture.height())
                .param("thumbnail", picture.hasThumbnail())
                .param("preview", picture.preview())
                .param("now", OffsetDateTime.ofInstant(picture.createdAt(), ZoneOffset.UTC))
                .update();
    }

    boolean delete(UUID inspirationId, UUID pictureId) {
        return jdbc.sql("DELETE FROM inspiration_picture WHERE inspiration_id = ? AND id = ?")
                        .params(inspirationId, pictureId)
                        .update()
                == 1;
    }

    /** Removes the pictures that were fetched from the inspiration's link. @return their ids */
    List<UUID> deletePreviews(UUID inspirationId) {
        return jdbc.sql("DELETE FROM inspiration_picture WHERE inspiration_id = ? AND preview RETURNING id")
                .param(inspirationId)
                .query(UUID.class)
                .list();
    }

    /** The pictures that go when the inspiration goes. */
    List<UUID> idsOfInspiration(UUID inspirationId) {
        return jdbc.sql("SELECT id FROM inspiration_picture WHERE inspiration_id = ?").param(inspirationId).query(UUID.class).list();
    }

    /** The pictures that go when a board is removed together with its inspirations. */
    List<UUID> idsOfBoard(UUID weddingId, UUID boardId) {
        return jdbc.sql("""
                SELECT p.id FROM inspiration_picture p JOIN inspiration i ON i.id = p.inspiration_id
                WHERE i.wedding_id = ? AND i.board_id = ?
                """)
                .params(weddingId, boardId)
                .query(UUID.class)
                .list();
    }

    private static PictureRow row(ResultSet row, int number) throws SQLException {
        return new PictureRow(
                row.getObject("id", UUID.class),
                row.getObject("inspiration_id", UUID.class),
                row.getInt("position"),
                PictureFormat.ofContentType(row.getString("content_type")),
                row.getLong("size_bytes"),
                row.getObject("width", Integer.class),
                row.getObject("height", Integer.class),
                row.getBoolean("has_thumbnail"),
                row.getBoolean("preview"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
