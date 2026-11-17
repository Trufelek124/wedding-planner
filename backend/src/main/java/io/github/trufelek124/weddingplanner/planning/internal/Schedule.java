package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.DependencyRepository.Link;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The plan as a whole: which entries wait for which. Built from all entries and links of a
 * wedding, it answers what one entry alone cannot know.
 */
final class Schedule {

    private final Map<UUID, EntryRow> entries = new HashMap<>();
    private final Map<UUID, List<UUID>> dependsOn = new HashMap<>();
    private final Map<UUID, List<UUID>> dependents = new HashMap<>();

    Schedule(Collection<EntryRow> allEntries, Collection<Link> links) {
        allEntries.forEach(entry -> entries.put(entry.id(), entry));
        for (Link link : links) {
            dependsOn.computeIfAbsent(link.entryId(), id -> new java.util.ArrayList<>()).add(link.dependsOnId());
            dependents.computeIfAbsent(link.dependsOnId(), id -> new java.util.ArrayList<>()).add(link.entryId());
        }
    }

    /** What the entry waits for, in a stable order. */
    List<UUID> dependsOn(UUID entryId) {
        return dependsOn.getOrDefault(entryId, List.of()).stream().sorted().toList();
    }

    /**
     * The entries this one waits for that end after it is meant to begin. An entry without a start
     * date is measured by its due date: it cannot be finished before what it waits for.
     */
    List<UUID> conflicts(UUID entryId) {
        EntryRow entry = entries.get(entryId);
        LocalDate begins = entry.startDate() != null ? entry.startDate() : entry.dueDate();
        if (begins == null) {
            return List.of();
        }
        return dependsOn(entryId).stream()
                .filter(id -> {
                    LocalDate ends = entries.get(id).dueDate();
                    return ends != null && begins.isBefore(ends);
                })
                .toList();
    }

    /** The earliest due date among the unfinished entries that wait for this one; null if there is none. */
    LocalDate earliestDependentDue(UUID entryId) {
        return dependents.getOrDefault(entryId, List.of()).stream()
                .map(entries::get)
                .filter(waiting -> waiting.status() != Status.DONE && waiting.status() != Status.DROPPED)
                .map(EntryRow::dueDate)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    /** Whether making "entry" wait for "dependsOn" would close a circle of entries waiting for each other. */
    boolean wouldCloseACircle(UUID entryId, UUID dependsOnId) {
        if (entryId.equals(dependsOnId)) {
            return true;
        }
        // A circle arises exactly when "dependsOn" already waits, directly or through others, for "entry".
        Deque<UUID> toVisit = new ArrayDeque<>(List.of(dependsOnId));
        Set<UUID> visited = new HashSet<>();
        while (!toVisit.isEmpty()) {
            UUID current = toVisit.pop();
            if (current.equals(entryId)) {
                return true;
            }
            if (visited.add(current)) {
                toVisit.addAll(dependsOn.getOrDefault(current, List.of()));
            }
        }
        return false;
    }
}
