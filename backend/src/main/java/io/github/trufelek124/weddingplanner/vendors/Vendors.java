package io.github.trufelek124.weddingplanner.vendors;

import java.util.Optional;
import java.util.UUID;

/** How the other modules ask about vendors, for the things that can point to one. */
public interface Vendors {

    /** Whether the wedding has a vendor with this id. */
    boolean exists(UUID weddingId, UUID vendorId);

    /**
     * The vendor whose e-mail address is exactly this one, compared without regard to case and to spaces around it.
     * Empty when none has it, when more than one has it, or when the address is blank.
     */
    Optional<UUID> withAddress(UUID weddingId, String address);

    /** The vendor whose name is exactly this one, compared the same way. Empty when none or more than one. */
    Optional<UUID> named(UUID weddingId, String name);
}
