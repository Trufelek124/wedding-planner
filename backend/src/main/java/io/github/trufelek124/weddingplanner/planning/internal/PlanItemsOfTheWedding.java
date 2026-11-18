package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.PlanItems;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PlanItemsOfTheWedding implements PlanItems {

    private final EntryRepository entries;

    PlanItemsOfTheWedding(EntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public boolean exists(UUID weddingId, UUID planItemId) {
        return entries.find(weddingId, planItemId).filter(entry -> entry.kind() == Kind.PLAN_ITEM).isPresent();
    }
}
