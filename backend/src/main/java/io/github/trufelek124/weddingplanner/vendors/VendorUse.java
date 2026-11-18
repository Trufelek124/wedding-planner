package io.github.trufelek124.weddingplanner.vendors;

import java.util.Map;
import java.util.UUID;

/**
 * What hangs on the vendors. Each module whose records can point to a vendor implements this, so
 * that the register can say what a vendor is used for without knowing those modules.
 */
public interface VendorUse {

    /**
     * What of this module points to each vendor of the wedding. A vendor nothing points to may be
     * left out.
     */
    Map<UUID, Use> byVendor(UUID weddingId);

    /**
     * @param tasks every task that points to the vendor, payments among them
     * @param payments its tasks that carry an amount and are not dropped
     * @param committed what those payments come to, in the smallest unit of the wedding's currency
     * @param paid the part of it that is paid
     */
    record Use(int planItems, int tasks, int payments, long committed, long paid, int inspirations) {

        public static final Use NONE = new Use(0, 0, 0, 0, 0, 0);

        public Use plus(Use other) {
            return new Use(
                    planItems + other.planItems,
                    tasks + other.tasks,
                    payments + other.payments,
                    Math.addExact(committed, other.committed),
                    Math.addExact(paid, other.paid),
                    inspirations + other.inspirations);
        }

        /** Whether anything at all points to the vendor. */
        public boolean any() {
            return planItems + tasks + inspirations > 0;
        }
    }
}
