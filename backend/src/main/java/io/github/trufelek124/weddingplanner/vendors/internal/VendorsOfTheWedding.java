package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.vendors.Vendors;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class VendorsOfTheWedding implements Vendors {

    private final VendorRepository vendors;

    VendorsOfTheWedding(VendorRepository vendors) {
        this.vendors = vendors;
    }

    @Override
    public boolean exists(UUID weddingId, UUID vendorId) {
        return vendors.exists(weddingId, vendorId);
    }
}
