package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VendorRepository {

    /**
     * A vendor as stored.
     *
     * @param agreedPrice in the smallest unit of the wedding's currency; null while nothing is agreed
     */
    record VendorRow(
            UUID id,
            String name,
            UUID categoryId,
            Status status,
            String contactPerson,
            String phone,
            String email,
            String website,
            String address,
            Long agreedPrice,
            String notes,
            int version,
            Instant createdAt,
            Instant updatedAt) {

        /** Names and contact details are personal data: what gets printed is only which vendor it is. */
        @Override
        public String toString() {
            return "VendorRow[id=" + id + ", status=" + status + ", version=" + version + "]";
        }
    }

    /** What a list may be narrowed down by; null or empty means "any". */
    record Filter(List<Status> statuses, UUID categoryId) {}

    private static final String SELECT = """
            SELECT id, name, category_id, status, contact_person, phone, email, website, address, agreed_price, notes,
                   version, created_at, updated_at
            FROM vendor
            """;

    private final JdbcClient jdbc;

    VendorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<VendorRow> find(UUID weddingId, UUID id) {
        return jdbc.sql(SELECT + " WHERE wedding_id = ? AND id = ?").params(weddingId, id).query(VendorRepository::row).optional();
    }

    boolean exists(UUID weddingId, UUID id) {
        return jdbc.sql("SELECT count(*) FROM vendor WHERE wedding_id = ? AND id = ?").params(weddingId, id).query(Integer.class).single() > 0;
    }

    Map<UUID, String> namesOf(UUID weddingId, Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, name FROM vendor WHERE wedding_id = :wedding AND id IN (:ids)")
                .param("wedding", weddingId)
                .param("ids", ids)
                .query(row -> {
                    names.put(row.getObject("id", UUID.class), row.getString("name"));
                });
        return names;
    }

    /**
     * The id of the one vendor whose {@code column} ("name" or "email") equals the value without regard to case and
     * to spaces around it; empty when none or more than one. The value is a statement parameter and nothing else.
     */
    Optional<UUID> onlyOneWith(String column, UUID weddingId, String value) {
        if (!column.equals("name") && !column.equals("email")) {
            throw new IllegalArgumentException("column");
        }
        List<UUID> found = jdbc.sql("SELECT id FROM vendor WHERE wedding_id = ? AND lower(btrim(" + column
                        + ")) = lower(btrim(?)) LIMIT 2")
                .params(weddingId, value)
                .query(UUID.class)
                .list();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** By name, however it is capitalised. */
    List<VendorRow> list(UUID weddingId, Filter filter) {
        StringBuilder where = new StringBuilder(" WHERE wedding_id = :wedding");
        Map<String, Object> params = new HashMap<>();
        params.put("wedding", weddingId);
        if (filter.statuses() != null && !filter.statuses().isEmpty()) {
            where.append(" AND status IN (:statuses)");
            params.put("statuses", filter.statuses().stream().map(Enum::name).toList());
        }
        if (filter.categoryId() != null) {
            where.append(" AND category_id = :category");
            params.put("category", filter.categoryId());
        }
        return jdbc.sql(SELECT + where + " ORDER BY lower(name), id").params(params).query(VendorRepository::row).list();
    }

    void insert(UUID weddingId, VendorRow vendor) {
        jdbc.sql("""
                INSERT INTO vendor (id, wedding_id, name, category_id, status, contact_person, phone, email, website,
                                    address, agreed_price, notes, version, created_at, updated_at)
                VALUES (:id, :wedding, :name, :category, :status, :contactPerson, :phone, :email, :website,
                        :address, :agreedPrice, :notes, 0, :now, :now)
                """)
                .params(fields(vendor))
                .param("id", vendor.id())
                .param("wedding", weddingId)
                .param("now", at(vendor.createdAt()))
                .update();
    }

    /** Saves the vendor if it still has the version the change was based on. */
    boolean update(UUID weddingId, VendorRow vendor, int basedOnVersion, Instant now) {
        return jdbc.sql("""
                UPDATE vendor
                SET name = :name, category_id = :category, status = :status, contact_person = :contactPerson,
                    phone = :phone, email = :email, website = :website, address = :address,
                    agreed_price = :agreedPrice, notes = :notes, version = version + 1, updated_at = :now
                WHERE wedding_id = :wedding AND id = :id AND version = :version
                """)
                        .params(fields(vendor))
                        .param("id", vendor.id())
                        .param("wedding", weddingId)
                        .param("version", basedOnVersion)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    /** What pointed to the vendor stays and loses it: the database clears those links. */
    void delete(UUID weddingId, UUID id) {
        jdbc.sql("DELETE FROM vendor WHERE wedding_id = ? AND id = ?").params(weddingId, id).update();
    }

    private static Map<String, Object> fields(VendorRow vendor) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", vendor.name());
        fields.put("category", vendor.categoryId());
        fields.put("status", vendor.status().name());
        fields.put("contactPerson", vendor.contactPerson());
        fields.put("phone", vendor.phone());
        fields.put("email", vendor.email());
        fields.put("website", vendor.website());
        fields.put("address", vendor.address());
        fields.put("agreedPrice", vendor.agreedPrice());
        fields.put("notes", vendor.notes());
        return fields;
    }

    private static VendorRow row(ResultSet row, int number) throws SQLException {
        return new VendorRow(
                row.getObject("id", UUID.class),
                row.getString("name"),
                row.getObject("category_id", UUID.class),
                Status.valueOf(row.getString("status")),
                row.getString("contact_person"),
                row.getString("phone"),
                row.getString("email"),
                row.getString("website"),
                row.getString("address"),
                row.getObject("agreed_price", Long.class),
                row.getString("notes"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
