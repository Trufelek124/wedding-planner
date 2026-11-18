package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class BoardRepository {

    record BoardRow(UUID id, String name, int position, int inspirationCount) {}

    private static final String SELECT = """
            SELECT b.id, b.name, b.position,
                   (SELECT count(*) FROM inspiration i WHERE i.board_id = b.id) AS inspiration_count
            FROM board b
            """;

    private final JdbcClient jdbc;

    BoardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<BoardRow> list(UUID weddingId) {
        return jdbc.sql(SELECT + " WHERE b.wedding_id = ? ORDER BY b.position, lower(b.name)")
                .param(weddingId)
                .query(BoardRow.class)
                .list();
    }

    boolean exists(UUID weddingId, UUID boardId) {
        return jdbc.sql("SELECT count(*) FROM board WHERE wedding_id = ? AND id = ?")
                        .params(weddingId, boardId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** @return false when the wedding has a board of that name already (however it is capitalised) */
    boolean insert(UUID weddingId, UUID id, String name) {
        try {
            jdbc.sql("""
                    INSERT INTO board (id, wedding_id, name, position)
                    VALUES (?, ?, ?, (SELECT COALESCE(max(position), 0) + 1 FROM board WHERE wedding_id = ?))
                    """)
                    .params(id, weddingId, name, weddingId)
                    .update();
            return true;
        } catch (DuplicateKeyException sameName) {
            return false;
        }
    }

    /** @return 1 when renamed, 0 when there is no such board, -1 when the name is taken */
    int rename(UUID weddingId, UUID id, String name) {
        try {
            return jdbc.sql("UPDATE board SET name = ? WHERE wedding_id = ? AND id = ?").params(name, weddingId, id).update();
        } catch (DuplicateKeyException sameName) {
            return -1;
        }
    }

    /** Inspirations still on the board stay, without a board: the database sees to that. */
    boolean delete(UUID weddingId, UUID id) {
        return jdbc.sql("DELETE FROM board WHERE wedding_id = ? AND id = ?").params(weddingId, id).update() == 1;
    }

    /** @return true the first time it is called for a wedding, and never again */
    boolean markOffered(UUID weddingId, Instant now) {
        return jdbc.sql("""
                INSERT INTO inspirations_setup (wedding_id, boards_offered_at) VALUES (?, ?)
                ON CONFLICT (wedding_id) DO NOTHING
                """)
                        .params(weddingId, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
