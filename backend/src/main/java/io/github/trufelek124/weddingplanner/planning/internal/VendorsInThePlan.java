package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.vendors.VendorCategories;
import io.github.trufelek124.weddingplanner.vendors.VendorUse;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What the plan tells the vendor register: which plan items, tasks and payments point to each
 * vendor, and which categories there are to file a vendor under.
 */
@Component
class VendorsInThePlan implements VendorUse, VendorCategories {

    private final EntryRepository entries;
    private final CategoryRepository categories;

    VendorsInThePlan(EntryRepository entries, CategoryRepository categories) {
        this.entries = entries;
        this.categories = categories;
    }

    @Override
    public Map<UUID, Use> byVendor(UUID weddingId) {
        return entries.useByVendor(weddingId);
    }

    @Override
    public boolean exists(UUID weddingId, UUID categoryId) {
        return categories.exists(weddingId, categoryId);
    }
}
