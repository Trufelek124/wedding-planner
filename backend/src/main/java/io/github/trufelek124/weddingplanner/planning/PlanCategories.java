package io.github.trufelek124.weddingplanner.planning;

import java.util.Optional;
import java.util.UUID;

/** How another module finds a category it knows only by name. */
public interface PlanCategories {

    /** The category with exactly this name, without regard to case and to spaces around it. Empty when there is none. */
    Optional<UUID> named(UUID weddingId, String name);
}
