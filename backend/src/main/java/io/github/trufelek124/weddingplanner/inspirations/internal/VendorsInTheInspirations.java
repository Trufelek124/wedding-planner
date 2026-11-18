package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.vendors.VendorUse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** What the inspirations tell the vendor register: how many ideas point to each vendor. */
@Component
class VendorsInTheInspirations implements VendorUse {

    private final InspirationRepository inspirations;

    VendorsInTheInspirations(InspirationRepository inspirations) {
        this.inspirations = inspirations;
    }

    @Override
    public Map<UUID, Use> byVendor(UUID weddingId) {
        Map<UUID, Use> all = new HashMap<>();
        inspirations.countByVendor(weddingId).forEach((vendorId, count) -> all.put(vendorId, new Use(0, 0, 0, 0, 0, count)));
        return all;
    }
}
