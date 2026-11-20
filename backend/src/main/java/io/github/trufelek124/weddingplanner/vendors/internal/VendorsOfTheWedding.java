package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.vendors.Vendors;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
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

    @Override
    public Optional<UUID> withAddress(UUID weddingId, String address) {
        return address == null || address.isBlank() ? Optional.empty() : vendors.onlyOneWith("email", weddingId, address.strip());
    }

    @Override
    public Optional<UUID> named(UUID weddingId, String name) {
        return name == null || name.isBlank() ? Optional.empty() : vendors.onlyOneWith("name", weddingId, name.strip());
    }

    @Override
    public Map<UUID, String> namesOf(UUID weddingId, Collection<UUID> vendorIds) {
        return vendorIds.isEmpty() ? Map.of() : vendors.namesOf(weddingId, vendorIds);
    }
}
