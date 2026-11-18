package io.github.trufelek124.weddingplanner.planning.internal;

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
class BudgetRepository {

    /** @param total what the couple means to spend on everything; null while they have not said */
    record BudgetRow(Long total, int version) {}

    /**
     * What the payments of one category come to.
     *
     * @param categoryId null for the payments that are filed under no category
     * @param committed every payment that is not dropped
     * @param paid those of them that are done
     * @param payments how many payments that are not dropped
     */
    record Spent(UUID categoryId, long committed, long paid, int payments) {}

    private final JdbcClient jdbc;

    BudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BudgetRow> find(UUID weddingId) {
        return jdbc.sql("SELECT total, version FROM budget WHERE wedding_id = ?")
                .param(weddingId)
                .query((row, number) -> new BudgetRow(row.getObject("total", Long.class), row.getInt("version")))
                .optional();
    }

    /** The planned amount of every category that has one. */
    Map<UUID, Long> planned(UUID weddingId) {
        Map<UUID, Long> planned = new HashMap<>();
        jdbc.sql("""
                SELECT p.category_id, p.amount FROM budget_plan p
                JOIN category c ON c.id = p.category_id
                WHERE c.wedding_id = ?
                """)
                .param(weddingId)
                .query(row -> {
                    planned.put(row.getObject("category_id", UUID.class), row.getLong("amount"));
                });
        return planned;
    }

    /**
     * The sums of the payments, category by category. The database adds whole numbers; no amount
     * ever passes through a fraction.
     */
    List<Spent> spent(UUID weddingId) {
        return jdbc.sql("""
                SELECT category_id,
                       sum(amount)::bigint AS committed,
                       COALESCE(sum(amount) FILTER (WHERE status = 'DONE'), 0)::bigint AS paid,
                       count(*) AS payments
                FROM entry
                WHERE wedding_id = ? AND amount IS NOT NULL AND status <> 'DROPPED'
                GROUP BY category_id
                """)
                .param(weddingId)
                .query((row, number) -> new Spent(
                        row.getObject("category_id", UUID.class), row.getLong("committed"), row.getLong("paid"), row.getInt("payments")))
                .list();
    }

    /**
     * Saves the total if the budget still has the version the change was based on. A budget nobody
     * has saved yet has version 0.
     */
    boolean save(UUID weddingId, Long total, int basedOnVersion, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        if (basedOnVersion == 0) {
            return jdbc.sql("""
                    INSERT INTO budget (wedding_id, total, version, updated_at) VALUES (?, ?, 1, ?)
                    ON CONFLICT (wedding_id) DO NOTHING
                    """)
                            .params(weddingId, total, at)
                            .update()
                    == 1;
        }
        return jdbc.sql("UPDATE budget SET total = ?, version = version + 1, updated_at = ? WHERE wedding_id = ? AND version = ?")
                        .params(total, at, weddingId, basedOnVersion)
                        .update()
                == 1;
    }

    void replacePlanned(UUID weddingId, Map<UUID, Long> planned) {
        jdbc.sql("DELETE FROM budget_plan WHERE category_id IN (SELECT id FROM category WHERE wedding_id = ?)")
                .param(weddingId)
                .update();
        planned.forEach((categoryId, amount) ->
                jdbc.sql("INSERT INTO budget_plan (category_id, amount) VALUES (?, ?)").params(categoryId, amount).update());
    }
}
