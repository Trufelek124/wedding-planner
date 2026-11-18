package io.github.trufelek124.weddingplanner.guests.internal;

import io.github.trufelek124.weddingplanner.guests.internal.GuestTypes.Status;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GuestRepository {

    /**
     * A guest as stored.
     *
     * @param sideMemberId the member whose guest this is; null for a guest of both
     */
    record GuestRow(
            UUID id,
            String name,
            UUID sideMemberId,
            UUID householdId,
            Status status,
            boolean plusOne,
            int children,
            String dietaryNeeds,
            boolean needsAccommodation,
            String phone,
            String email,
            String notes,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        /** Everything about a guest is personal data: what gets printed is only which guest it is. */
        @Override
        public String toString() {
            return "GuestRow[id=" + id + ", status=" + status + ", version=" + version + "]";
        }
    }

    /** @param guests how many guests belong to it */
    record HouseholdRow(UUID id, String name, int guests) {

        /** A household is called after people. */
        @Override
        public String toString() {
            return "HouseholdRow[id=" + id + ", guests=" + guests + "]";
        }
    }

    /**
     * What a list may be narrowed down by; null, empty or false means "any".
     *
     * @param bothSides only the guests who are both's
     * @param withoutHousehold only the guests who belong to no household
     */
    record Filter(List<Status> statuses, UUID sideMemberId, boolean bothSides, UUID householdId, boolean withoutHousehold) {}

    /** How many guests of one side stand where, with whom they bring. */
    record Counted(UUID sideMemberId, Status status, int guests, int plusOnes, int children) {}

    private static final String SELECT = """
            SELECT id, name, side_member_id, household_id, status, plus_one, children, dietary_needs, needs_accommodation,
                   phone, email, notes, version, created_at, updated_at
            FROM guest
            """;

    private final JdbcClient jdbc;

    GuestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ guests

    Optional<GuestRow> find(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE wedding_id = ? AND id = ?").params(weddingId, id).query(GuestRepository::row).optional();
    }

    /** By name, however it is capitalised. */
    List<GuestRow> list(UUID weddingId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE wedding_id = :wedding");
        Map<String, Object> params = new HashMap<>();
        params.put("wedding", weddingId);
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            where.append(" AND status IN (:statuses)");
            params.put("statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (filter.sideMemberId() != null) {
            where.append(" AND side_member_id = :side");
            params.put("side", filter.sideMemberId());
        }
        if (filter.bothSides()) {
            where.append(" AND side_member_id IS NULL");
        }
        if (filter.householdId() != null) {
            where.append(" AND household_id = :household");
            params.put("household", filter.householdId());
        }
        if (filter.withoutHousehold()) {
            where.append(" AND household_id IS NULL");
        }
        return jdbc.sql(SELECT + where + " ORDER BY lower(name), id").params(params).query(GuestRepository::row).list();
    }

    void insert(UUID weddingId, GuestRow guest) {
        jdbc.sql("""
                INSERT INTO guest (id, wedding_id, name, side_member_id, household_id, status, plus_one, children,
                                   dietary_needs, needs_accommodation, phone, email, notes, version, created_at, updated_at)
                VALUES (:id, :wedding, :name, :side, :household, :status, :plusOne, :children,
                        :dietaryNeeds, :needsAccommodation, :phone, :email, :notes, 0, :now, :now)
                """)
                .params(fields(guest))
                .param("id", guest.id())
                .param("wedding", weddingId)
                .param("now", at(guest.createdAt()))
                .update();
    }

    /** Saves the guest if it still has the version the change was based on. */
    boolean update(UUID weddingId, GuestRow guest, int basedOnVersion, Instant now) {
        return jdbc.sql("""
                UPDATE guest
                SET name = :name, side_member_id = :side, household_id = :household, status = :status, plus_one = :plusOne,
                    children = :children, dietary_needs = :dietaryNeeds, needs_accommodation = :needsAccommodation,
                    phone = :phone, email = :email, notes = :notes, version = version + 1, updated_at = :now
                WHERE wedding_id = :wedding AND id = :id AND version = :version
                """)
                        .params(fields(guest))
                        .param("id", guest.id())
                        .param("wedding", weddingId)
                        .param("version", basedOnVersion)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    /** An answer that came in: the status alone, whatever else someone is editing. */
    boolean setStatus(UUID weddingId, UUID id, Status status, Instant now) {
        return jdbc.sql("UPDATE guest SET status = ?, version = version + 1, updated_at = ? WHERE wedding_id = ? AND id = ?")
                        .params(status.name(), at(now), weddingId, id)
                        .update()
                == 1;
    }

    boolean delete(UUID weddingId, UUID id) {
        return jdbc.sql("DELETE FROM guest WHERE wedding_id = ? AND id = ?").params(weddingId, id).update() == 1;
    }

    List<Counted> counts(UUID weddingId) {
        return jdbc.sql("""
                SELECT side_member_id, status, count(*) AS guests, count(*) FILTER (WHERE plus_one) AS plus_ones,
                       COALESCE(sum(children), 0) AS children
                FROM guest
                WHERE wedding_id = ?
                GROUP BY side_member_id, status
                """)
                .param(weddingId)
                .query((row, number) -> new Counted(
                        row.getObject("side_member_id", UUID.class), Status.valueOf(row.getString("status")), row.getInt("guests"),
                        row.getInt("plus_ones"), row.getInt("children")))
                .list();
    }

    // ------------------------------------------------------------ households

    /** By name. */
    List<HouseholdRow> households(UUID weddingId) {
        return jdbc.sql("""
                SELECT h.id, h.name, (SELECT count(*) FROM guest g WHERE g.household_id = h.id) AS guests
                FROM household h
                WHERE h.wedding_id = ?
                ORDER BY lower(h.name), h.id
                """)
                .param(weddingId)
                .query((row, number) -> new HouseholdRow(row.getObject("id", UUID.class), row.getString("name"), row.getInt("guests")))
                .list();
    }

    boolean householdExists(UUID weddingId, UUID id) {
        return jdbc.sql("SELECT count(*) FROM household WHERE wedding_id = ? AND id = ?").params(weddingId, id).query(Integer.class).single() > 0;
    }

    /** @return false when the wedding has a household of that name already (however it is capitalised) */
    boolean insertHousehold(UUID weddingId, UUID id, String name) {
        try {
            jdbc.sql("INSERT INTO household (id, wedding_id, name) VALUES (?, ?, ?)").params(id, weddingId, name).update();
            return true;
        } catch (DuplicateKeyException sameName) {
            return false;
        }
    }

    /** @return 1 when renamed, 0 when there is no such household, -1 when the name is taken */
    int renameHousehold(UUID weddingId, UUID id, String name) {
        try {
            return jdbc.sql("UPDATE household SET name = ? WHERE wedding_id = ? AND id = ?").params(name, weddingId, id).update();
        } catch (DuplicateKeyException sameName) {
            return -1;
        }
    }

    /** Its guests stay on the list, without a household: the database clears the link. */
    boolean deleteHousehold(UUID weddingId, UUID id) {
        return jdbc.sql("DELETE FROM household WHERE wedding_id = ? AND id = ?").params(weddingId, id).update() == 1;
    }

    private static Map<String, Object> fields(GuestRow guest) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", guest.name());
        fields.put("side", guest.sideMemberId());
        fields.put("household", guest.householdId());
        fields.put("status", guest.status().name());
        fields.put("plusOne", guest.plusOne());
        fields.put("children", guest.children());
        fields.put("dietaryNeeds", guest.dietaryNeeds());
        fields.put("needsAccommodation", guest.needsAccommodation());
        fields.put("phone", guest.phone());
        fields.put("email", guest.email());
        fields.put("notes", guest.notes());
        return fields;
    }

    private static GuestRow row(ResultSet row, int number) throws SQLException {
        return new GuestRow(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getObject("side_member_id", UUID.class),
                row.getObject("household_id", UUID.class),
                Status.valueOf(row.getString("status")),
                row.getBoolean("plus_one"),
                row.getInt("children"),
                row.getString("dietary_needs"),
                row.getBoolean("needs_accommodation"),
                row.getString("phone"),
                row.getString("email"),
                row.getString("notes"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
