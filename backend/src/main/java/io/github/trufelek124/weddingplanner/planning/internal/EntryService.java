package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.Filter;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.EntryChange;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.EntryFields;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Sort;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.TasksOnDelete;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plan items and tasks: what may be saved, and what saving, converting and deleting do. */
@Service
class EntryService {

    private final EntryRepository entries;
    private final CategoryRepository categories;
    private final Weddings weddings;
    private final Clock clock;

    EntryService(EntryRepository entries, CategoryRepository categories, Weddings weddings, Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<EntryRow> list(UserId user, Filter filter, Sort sort) {
        Membership membership = weddings.membershipOf(user);
        List<EntryRow> rows = entries.list(membership.weddingId(), filter, sort);
        rows.forEach(row -> inMemberOrder(row, membership));
        return rows;
    }

    @Transactional
    EntryRow get(UserId user, UUID id) {
        return existing(weddings.membershipOf(user), id);
    }

    @Transactional
    EntryRow create(UserId user, NewEntry request) {
        Membership membership = weddings.membershipOf(user);
        Instant now = clock.instant();
        EntryRow entry = checked(membership, UUID.randomUUID(), request.kind(), request, null, 0, now, now);
        entries.insert(membership.weddingId(), membership.memberId(), entry);
        return existing(membership, entry.id());
    }

    @Transactional
    EntryRow change(UserId user, UUID id, EntryChange request) {
        Membership membership = weddings.membershipOf(user);
        EntryRow before = existing(membership, id);
        Instant now = clock.instant();
        EntryRow after = checked(membership, id, before.kind(), request, before, before.taskCount(), before.createdAt(), now);
        save(membership.weddingId(), after, request.version(), now);
        return existing(membership, id);
    }

    /** Ticking off (or un-ticking) without sending the whole entry. */
    @Transactional
    EntryRow setStatus(UserId user, UUID id, Status status) {
        Membership membership = weddings.membershipOf(user);
        EntryRow before = existing(membership, id);
        Instant now = clock.instant();
        save(membership.weddingId(), with(before, before.kind(), status, completedAt(status, before, now)), before.version(), now);
        return existing(membership, id);
    }

    /**
     * Turns a task into a plan item or back. Title, notes, people, priority, category and the due
     * date stay. A plan item has no parent; a task has no start date, and cannot have tasks of its own.
     */
    @Transactional
    EntryRow convert(UserId user, UUID id, Kind to) {
        Membership membership = weddings.membershipOf(user);
        UUID weddingId = membership.weddingId();
        EntryRow before = existing(membership, id);
        if (before.kind() == to) {
            return before;
        }
        if (to == Kind.TASK && before.taskCount() > 0) {
            throw new ApiException(
                    ProblemCode.ENTRY_HAS_TASKS, "This plan item has tasks. Move or delete them before making it a task.");
        }
        Instant now = clock.instant();
        EntryRow after = new EntryRow(
                before.id(), to, before.title(), before.notes(), before.categoryId(),
                to == Kind.TASK ? null : before.startDate(), before.dueDate(), before.status(), before.completedAt(),
                before.priorityMode(), before.priority(), to == Kind.PLAN_ITEM ? null : before.parentId(),
                before.assigneeIds(), before.taskCount(), before.version(), before.createdAt(), now);
        save(weddingId, after, before.version(), now);
        return existing(membership, id);
    }

    /**
     * @param tasks what happens to the tasks of a plan item; while it has tasks and this is null,
     *     nothing is deleted and the caller is asked to choose
     */
    @Transactional
    void delete(UserId user, UUID id, TasksOnDelete tasks) {
        Membership membership = weddings.membershipOf(user);
        UUID weddingId = membership.weddingId();
        EntryRow entry = existing(membership, id);
        if (entry.taskCount() > 0) {
            if (tasks == null) {
                throw new ApiException(
                        ProblemCode.ENTRY_HAS_TASKS, "This plan item has tasks. Say whether to keep them or delete them too.");
            }
            if (tasks == TasksOnDelete.DELETE) {
                entries.deleteTasks(weddingId, id);
            } else {
                entries.detachTasks(weddingId, id, clock.instant());
            }
        }
        entries.delete(weddingId, id);
    }

    private void save(UUID weddingId, EntryRow entry, int basedOnVersion, Instant now) {
        if (!entries.update(weddingId, entry, basedOnVersion, now)) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
    }

    private EntryRow existing(Membership membership, UUID id) {
        EntryRow row = entries.find(membership.weddingId(), id)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such entry."));
        inMemberOrder(row, membership);
        return row;
    }

    /** The people of an entry are always listed in the wedding's own order of its two members. */
    private static void inMemberOrder(EntryRow row, Membership membership) {
        row.assigneeIds().sort(Comparator.comparingInt(membership.memberIds()::indexOf));
    }

    /** Applies the rules to what a person sent and returns the entry as it will be stored. */
    private EntryRow checked(
            Membership membership, UUID id, Kind kind, EntryFields request, EntryRow before, int taskCount, Instant createdAt,
            Instant now) {
        UUID weddingId = membership.weddingId();
        List<FieldProblem> problems = new ArrayList<>();

        if (kind == Kind.TASK && request.startDate() != null) {
            problems.add(new FieldProblem("startDate", "A task has only a due date. Leave the start empty."));
        }
        if (request.startDate() != null && request.dueDate() == null) {
            problems.add(new FieldProblem("dueDate", "Enter the end date too, or leave both dates empty."));
        }
        if (request.startDate() != null && request.dueDate() != null && request.startDate().isAfter(request.dueDate())) {
            problems.add(new FieldProblem("dueDate", "The end date cannot be before the start date."));
        }
        if (request.priorityMode() == PriorityMode.MANUAL && request.priority() == null) {
            problems.add(new FieldProblem("priority", "Choose a priority, or let the planner set it."));
        }
        if (request.categoryId() != null && !categories.exists(weddingId, request.categoryId())) {
            problems.add(new FieldProblem("categoryId", "Choose one of the categories."));
        }
        List<UUID> assignees = new ArrayList<>(new LinkedHashSet<>(request.assigneeIds()));
        if (!membership.memberIds().containsAll(assignees)) {
            problems.add(new FieldProblem("assigneeIds", "Choose one of you, or both."));
        }
        if (request.parentId() != null) {
            if (kind == Kind.PLAN_ITEM) {
                problems.add(new FieldProblem("parentId", "A plan item cannot belong to another plan item."));
            } else if (entries.find(weddingId, request.parentId()).filter(parent -> parent.kind() == Kind.PLAN_ITEM).isEmpty()) {
                problems.add(new FieldProblem("parentId", "Choose one of the plan items."));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }

        // Until the automatic rule exists (WP-TS-17) an entry left to the planner counts as normal.
        Priority priority = request.priorityMode() == PriorityMode.MANUAL ? request.priority() : Priority.NORMAL;
        return new EntryRow(
                id, kind, request.title().strip(), request.notes().strip(), request.categoryId(), request.startDate(),
                request.dueDate(), request.status(), completedAt(request.status(), before, now), request.priorityMode(),
                priority, request.parentId(), assignees, taskCount, before == null ? 0 : before.version(), createdAt, now);
    }

    /** "Done" remembers when it was done; a change that leaves it done keeps that moment. */
    private static Instant completedAt(Status status, EntryRow before, Instant now) {
        if (status != Status.DONE) {
            return null;
        }
        return before != null && before.completedAt() != null ? before.completedAt() : now;
    }

    private static EntryRow with(EntryRow entry, Kind kind, Status status, Instant completedAt) {
        return new EntryRow(
                entry.id(), kind, entry.title(), entry.notes(), entry.categoryId(), entry.startDate(), entry.dueDate(),
                status, completedAt, entry.priorityMode(), entry.priority(), entry.parentId(), entry.assigneeIds(),
                entry.taskCount(), entry.version(), entry.createdAt(), entry.updatedAt());
    }
}
