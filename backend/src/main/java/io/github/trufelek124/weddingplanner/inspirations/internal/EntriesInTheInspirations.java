package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.planning.EntryUse;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** What the inspirations tell the plan: whether an idea has been put on an entry. */
@Component
class EntriesInTheInspirations implements EntryUse {

    private final InspirationRepository inspirations;

    EntriesInTheInspirations(InspirationRepository inspirations) {
        this.inspirations = inspirations;
    }

    @Override
    public boolean pointsTo(UUID weddingId, UUID entryId) {
        return inspirations.anyOnPlanItem(weddingId, entryId);
    }
}
