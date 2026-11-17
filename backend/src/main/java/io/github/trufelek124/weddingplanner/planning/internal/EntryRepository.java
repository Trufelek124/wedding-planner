package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Sort;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EntryRepository {

    /** An entry as stored, with what lists need next to it: who does it and how many tasks hang on it. */
    record EntryRow(
            UUID id,
            Kind kind,
            String title,
            String notes,
            UUID categoryId,
            LocalDate startDate,
            LocalDate dueDate,
            Status status,
            Instant completedAt,
            PriorityMode priorityMode,
            Priority priority,
            UUID parentId,
            List<UUID> assigneeIds,
            int taskCount,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    /** What a list may be narrowed down by; null means "any". */
    record Filter(Kind kind, List<Status> statuses, UUID categoryId, UUID assigneeId, UUID parentId, Boolean scheduled) {}

    private static final String SELECT = """
            SELECT e.id, e.kind, e.title, e.notes, e.category_id, e.start_date, e.due_date, e.status, e.completed_at,
                   e.priority_mode, e.priority, e.parent_id, e.version, e.created_at, e.updated_at,
                   (SELECT count(*) FROM entry task WHERE task.parent_id = e.id) AS task_count,
                   ARRAY(SELECT a.member_id FROM entry_assignee a WHERE a.entry_id = e.id) AS assignee_ids
            FROM entry e
            """;

    private static final String BY_PRIORITY =
            "CASE e.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END";

    private final JdbcClient jdbc;

    EntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<EntryRow> find(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE e.wedding_id = ? AND e.id = ?")
                .params(weddingId, id)
                .query(EntryRepository::row)
                .optional();
    }

    List<EntryRow> list(UUID weddingId, Filter filter, Sort sort) {
        StringBuilder where = new StringBuilder(" WHERE e.wedding_id = :wedding");
        Map<String, Object> params = new HashMap<>();
        params.put("wedding", weddingId);
        if (filter.kind() != null) {
            where.append(" AND e.kind = :kind");
            params.put("kind", filter.kind().name());
        }
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            where.append(" AND e.status IN (:statuses)");
            params.put("statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (filter.categoryId() != null) {
            where.append(" AND e.category_id = :category");
            params.put("category", filter.categoryId());
        }
        if (filter.assigneeId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM entry_assignee a WHERE a.entry_id = e.id AND a.member_id = :assignee)");
            params.put("assignee", filter.assigneeId());
        }
        if (filter.parentId() != null) {
            where.append(" AND e.parent_id = :parent");
            params.put("parent", filter.parentId());
        }
        if (filter.scheduled() != null) {
            where.append(filter.scheduled() ? " AND e.due_date IS NOT NULL" : " AND e.due_date IS NULL");
        }
        // Each order ends in the id, so that equal entries never swap places between two requests.
        String order = switch (sort) {
            case DUE_DATE -> " ORDER BY e.due_date NULLS LAST, " + BY_PRIORITY + ", lower(e.title), e.id";
            case PRIORITY -> " ORDER BY " + BY_PRIORITY + ", e.due_date NULLS LAST, lower(e.title), e.id";
            case TITLE -> " ORDER BY lower(e.title), e.id";
            case CREATED -> " ORDER BY e.created_at, e.id";
        };
        return jdbc.sql(SELECT + where + order).params(params).query(EntryRepository::row).list();
    }

    void insert(UUID weddingId, UUID createdBy, EntryRow entry) {
        jdbc.sql("""
                INSERT INTO entry (id, wedding_id, kind, title, notes, category_id, start_date, due_date, status,
                                   completed_at, priority_mode, priority, parent_id, version, created_by, created_at, updated_at)
                VALUES (:id, :wedding, :kind, :title, :notes, :category, :start, :due, :status,
                        :completed, :mode, :priority, :parent, 0, :createdBy, :now, :now)
                """)
                .params(fields(entry))
                .param("id", entry.id())
                .param("wedding", weddingId)
                .param("createdBy", createdBy)
                .param("now", at(entry.createdAt()))
                .update();
        replaceAssignees(entry.id(), entry.assigneeIds());
    }

    /** Saves the entry if it still has the version the change was based on. */
    boolean update(UUID weddingId, EntryRow entry, int basedOnVersion, Instant now) {
        boolean saved = jdbc.sql("""
                UPDATE entry
                SET kind = :kind, title = :title, notes = :notes, category_id = :category, start_date = :start,
                    due_date = :due, status = :status, completed_at = :completed, priority_mode = :mode,
                    priority = :priority, parent_id = :parent, version = version + 1, updated_at = :now
                WHERE wedding_id = :wedding AND id = :id AND version = :version
                """)
                        .params(fields(entry))
                        .param("id", entry.id())
                        .param("wedding", weddingId)
                        .param("version", basedOnVersion)
                        .param("now", at(now))
                        .update()
                == 1;
        if (saved) {
            replaceAssignees(entry.id(), entry.assigneeIds());
        }
        return saved;
    }

    /** Tasks of a plan item that goes away lose it (and count as changed). */
    void detachTasks(UUID weddingId, UUID parentId, Instant now) {
        jdbc.sql("""
                UPDATE entry SET parent_id = NULL, version = version + 1, updated_at = ?
                WHERE wedding_id = ? AND parent_id = ?
                """)
                .params(at(now), weddingId, parentId)
                .update();
    }

    void deleteTasks(UUID weddingId, UUID parentId) {
        jdbc.sql("DELETE FROM entry WHERE wedding_id = ? AND parent_id = ?").params(weddingId, parentId).update();
    }

    void delete(UUID weddingId, UUID id) {
        jdbc.sql("DELETE FROM entry WHERE wedding_id = ? AND id = ?").params(weddingId, id).update();
    }

    private void replaceAssignees(UUID entryId, List<UUID> memberIds) {
        jdbc.sql("DELETE FROM entry_assignee WHERE entry_id = ?").param(entryId).update();
        for (UUID memberId : memberIds) {
            jdbc.sql("INSERT INTO entry_assignee (entry_id, member_id) VALUES (?, ?)").params(entryId, memberId).update();
        }
    }

    private static Map<String, Object> fields(EntryRow entry) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("kind", entry.kind().name());
        fields.put("title", entry.title());
        fields.put("notes", entry.notes());
        fields.put("category", entry.categoryId());
        fields.put("start", entry.startDate());
        fields.put("due", entry.dueDate());
        fields.put("status", entry.status().name());
        fields.put("completed", at(entry.completedAt()));
        fields.put("mode", entry.priorityMode().name());
        fields.put("priority", entry.priority().name());
        fields.put("parent", entry.parentId());
        return fields;
    }

    private static EntryRow row(ResultSet row, int number) throws SQLException {
        UUID[] assignees = (UUID[]) row.getArray("assignee_ids").getArray();
        return new EntryRow(
                row.getObject("id", UUID.class),
                Kind.valueOf(row.getString("kind")),
                row.getString("title"),
                row.getString("notes"),
                row.getObject("category_id", UUID.class),
                row.getObject("start_date", LocalDate.class),
                row.getObject("due_date", LocalDate.class),
                Status.valueOf(row.getString("status")),
                instant(row.getObject("completed_at", OffsetDateTime.class)),
                PriorityMode.valueOf(row.getString("priority_mode")),
                Priority.valueOf(row.getString("priority")),
                row.getObject("parent_id", UUID.class),
                new ArrayList<>(Arrays.asList(assignees)),
                row.getInt("task_count"),
                row.getInt("version"),
                instant(row.getObject("created_at", OffsetDateTime.class)),
                instant(row.getObject("updated_at", OffsetDateTime.class)));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
