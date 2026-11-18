package io.github.trufelek124.weddingplanner.vendors;

import java.util.UUID;

/**
 * The categories a vendor can be filed under. They are the plan's own, so the module that keeps
 * them implements this; the register only asks whether one exists.
 */
public interface VendorCategories {

    boolean exists(UUID weddingId, UUID categoryId);
}
