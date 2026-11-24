package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.vendors.VendorUse.Use;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EntryRepository {

    /**
     * An entry as stored, with who does it and how many tasks hang on it.
     *
     * @param priority the level a person chose; null while the planner works the level out
     * @param vendorId the vendor the entry concerns, if any
     * @param amount for a payment: what it costs, in the smallest unit of the wedding's currency; null for any other entry
     * @param paidOn the day a payment was paid; null until it is done
     * @param fromSuggestionId the suggestion that made the entry; null for one made by hand. Saving never changes it
     */
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
            UUID vendorId,
            Long amount,
            PaymentKind paymentKind,
            LocalDate paidOn,
            List<UUID> assigneeIds,
            int taskCount,
            int version,
            Instant createdAt,
            Instant updatedAt,
            UUID fromSuggestionId) {

        boolean isPayment() {
            return amount != null;
        }

        /** The same entry, said to have been made by this suggestion. */
        EntryRow madeBy(UUID suggestionId) {
            return new EntryRow(
                    id, kind, title, notes, categoryId, startDate, dueDate, status, completedAt, priorityMode, priority,
                    parentId, vendorId, amount, paymentKind, paidOn, assigneeIds, taskCount, version, createdAt, updatedAt,
                    suggestionId);
        }
    }

    /** No narrowing down: every entry of the wedding. */
    static final Filter EVERYTHING = new Filter(null, null, null, false, null, null, null, null, null);

    /**
     * What a list may be narrowed down by; null means "any".
     *
     * @param withoutCategory only entries filed under no category
     * @param payment true: only payments; false: only what carries no money
     */
    record Filter(
            Kind kind,
            List<Status> statuses,
            UUID categoryId,
            boolean withoutCategory,
            UUID assigneeId,
            UUID parentId,
            Boolean scheduled,
            Boolean payment,
            UUID vendorId) {}

    private static final String SELECT = """
            SELECT e.id, e.kind, e.title, e.notes, e.category_id, e.start_date, e.due_date, e.status, e.completed_at,
                   e.priority_mode, e.priority, e.parent_id, e.vendor_id, e.amount, e.payment_kind, e.paid_on,
                   e.version, e.created_at, e.updated_at, e.from_suggestion_id, e.task_key,
                   (SELECT count(*) FROM entry task WHERE task.parent_id = e.id) AS task_count,
                   ARRAY(SELECT a.member_id FROM entry_assignee a WHERE a.entry_id = e.id) AS assignee_ids
            FROM entry e
            """;

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

    /** Whether a task of the wedding, of any status, has exactly this amount and is due on exactly this day (both without a day: the same). */
    boolean hasPayment(UUID weddingId, long amount, LocalDate dueDate) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM entry
                    WHERE wedding_id = ? AND amount = ? AND due_date IS NOT DISTINCT FROM CAST(? AS date))
                """)
                .params(weddingId, amount, dueDate)
                .query(Boolean.class)
                .single();
    }

    /** Finds it and holds its row until the transaction ends: nobody saves it or hangs something on it meanwhile. */
    Optional<EntryRow> findForUpdate(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE e.wedding_id = ? AND e.id = ? FOR UPDATE OF e")
                .params(weddingId, id)
                .query(EntryRepository::row)
                .optional();
    }

    /** In the order they were created; the service puts them in the order asked for. */
    List<EntryRow> list(UUID weddingId, Filter filter) {
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
        if (filter.withoutCategory()) {
            where.append(" AND e.category_id IS NULL");
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
        if (filter.payment() != null) {
            where.append(filter.payment() ? " AND e.amount IS NOT NULL" : " AND e.amount IS NULL");
        }
        if (filter.vendorId() != null) {
            where.append(" AND e.vendor_id = :vendor");
            params.put("vendor", filter.vendorId());
        }
        return jdbc.sql(SELECT + where + " ORDER BY e.created_at, e.id").params(params).query(EntryRepository::row).list();
    }

    /** An entry together with the key of the row of a file that made it. */
    record Keyed(String key, EntryRow entry) {}

    /**
     * The entries of the wedding that came from a file with one of these keys, held until the transaction ends
     * when {@code hold} is set: nobody saves them meanwhile.
     */
    List<Keyed> findByTaskKeys(UUID weddingId, Collection<String> keys, boolean hold) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE e.wedding_id = :wedding AND e.task_key IN (:keys) ORDER BY e.id"
                        + (hold ? " FOR UPDATE OF e" : ""))
                .param("wedding", weddingId)
                .param("keys", keys)
                .query((row, number) -> new Keyed(row.getString("task_key"), row(row, number)))
                .list();
    }

    /** @param taskKey the key of the row of a file that makes this entry; null for an entry made any other way */
    void insert(UUID weddingId, UUID createdBy, EntryRow entry, String taskKey) {
        jdbc.sql("""
                INSERT INTO entry (id, wedding_id, kind, title, notes, category_id, start_date, due_date, status,
                                   completed_at, priority_mode, priority, parent_id, vendor_id, amount, payment_kind,
                                   paid_on, version, created_by, created_at, updated_at, from_suggestion_id, task_key)
                VALUES (:id, :wedding, :kind, :title, :notes, :category, :start, :due, :status,
                        :completed, :mode, :priority, :parent, :vendor, :amount, :paymentKind, :paidOn,
                        0, :createdBy, :now, :now, :fromSuggestion, :taskKey)
                """)
                .params(fields(entry))
                .param("id", entry.id())
                .param("wedding", weddingId)
                .param("createdBy", createdBy)
                .param("fromSuggestion", entry.fromSuggestionId())
                .param("taskKey", taskKey)
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
                    priority = :priority, parent_id = :parent, vendor_id = :vendor, amount = :amount, payment_kind = :paymentKind,
                    paid_on = :paidOn, version = version + 1, updated_at = :now
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

    /** What the entries that point to each vendor come to. Dropped payments are not counted as money. */
    Map<UUID, Use> useByVendor(UUID weddingId) {
        Map<UUID, Use> all = new HashMap<>();
        jdbc.sql("""
                SELECT vendor_id,
                       count(*) FILTER (WHERE kind = 'PLAN_ITEM') AS plan_items,
                       count(*) FILTER (WHERE kind = 'TASK') AS tasks,
                       count(*) FILTER (WHERE amount IS NOT NULL AND status <> 'DROPPED') AS payments,
                       COALESCE(sum(amount) FILTER (WHERE status <> 'DROPPED'), 0)::bigint AS committed,
                       COALESCE(sum(amount) FILTER (WHERE status = 'DONE'), 0)::bigint AS paid
                FROM entry
                WHERE wedding_id = ? AND vendor_id IS NOT NULL
                GROUP BY vendor_id
                """)
                .param(weddingId)
                .query(row -> {
                    all.put(
                            row.getObject("vendor_id", UUID.class),
                            new Use(row.getInt("plan_items"), row.getInt("tasks"), row.getInt("payments"),
                                    row.getLong("committed"), row.getLong("paid"), 0));
                });
        return all;
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
        // Only a level a person chose is stored; the automatic one is worked out when asked for.
        fields.put("priority", entry.priority() == null ? null : entry.priority().name());
        fields.put("parent", entry.parentId());
        fields.put("vendor", entry.vendorId());
        fields.put("amount", entry.amount());
        fields.put("paymentKind", entry.paymentKind() == null ? null : entry.paymentKind().name());
        fields.put("paidOn", entry.paidOn());
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
                row.getString("priority") == null ? null : Priority.valueOf(row.getString("priority")),
                row.getObject("parent_id", UUID.class),
                row.getObject("vendor_id", UUID.class),
                row.getObject("amount", Long.class),
                row.getString("payment_kind") == null ? null : PaymentKind.valueOf(row.getString("payment_kind")),
                row.getObject("paid_on", LocalDate.class),
                new ArrayList<>(Arrays.asList(assignees)),
                row.getInt("task_count"),
                row.getInt("version"),
                instant(row.getObject("created_at", OffsetDateTime.class)),
                instant(row.getObject("updated_at", OffsetDateTime.class)),
                row.getObject("from_suggestion_id", UUID.class));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
