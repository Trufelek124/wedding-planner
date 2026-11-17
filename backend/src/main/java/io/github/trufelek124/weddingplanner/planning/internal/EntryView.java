package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Reason;
import java.util.List;
import java.util.UUID;

/**
 * An entry as it is shown today: what is stored, plus what follows from the date and from the
 * rest of the plan.
 *
 * @param priority the level a person chose, or the one worked out for today
 * @param dependsOnIds the plan items this one waits for
 * @param conflictsWithIds those of them that end after this entry is meant to begin
 */
record EntryView(EntryRow entry, Priority priority, Reason priorityReason, List<UUID> dependsOnIds, List<UUID> conflictsWithIds) {}
