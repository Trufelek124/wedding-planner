package io.github.trufelek124.weddingplanner.planning;

import java.util.UUID;

/** How the other modules ask about plan items, for the things that can be attached to one. */
public interface PlanItems {

    /** Whether the wedding has a plan item with this id. A task is not a plan item. */
    boolean exists(UUID weddingId, UUID planItemId);
}
