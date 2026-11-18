package io.github.trufelek124.weddingplanner.vendors;

import java.util.UUID;

/** How the other modules ask about vendors, for the things that can point to one. */
public interface Vendors {

    /** Whether the wedding has a vendor with this id. */
    boolean exists(UUID weddingId, UUID vendorId);
}
