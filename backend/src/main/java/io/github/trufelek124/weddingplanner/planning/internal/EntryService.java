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
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Assessment;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Facts;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Reason;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plan items and tasks: what may be saved, and what saving, converting, linking and deleting do. */
@Service
class EntryService {

    private final EntryRepository entries;
    private final CategoryRepository categories;
    private final DependencyRepository dependencies;
    private final Weddings weddings;
    private final Clock clock;

    EntryService(
            EntryRepository entries,
            CategoryRepository categories,
            DependencyRepository dependencies,
            Weddings weddings,
            Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.dependencies = dependencies;
        this.weddings = weddings;
        this.clock = clock;
    }

    // ------------------------------------------------------------ reading

    /**
     * @param levels only entries at one of these levels; null or empty means any. The level is the one shown today,
     *     worked out or set by hand, so it cannot be asked of the database.
     */
    @Transactional
    List<EntryView> list(UserId user, Filter filter, List<Priority> levels, Sort sort) {
        Membership membership = weddings.membershipOf(user);
        Schedule schedule = schedule(membership);
        return entries.list(membership.weddingId(), filter).stream()
                .map(row -> view(row, membership, schedule))
                .filter(view -> levels == null || levels.isEmpty() || levels.contains(view.priority()))
                .sorted(order(sort))
                .toList();
    }

    @Transactional
    EntryView get(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        return view(existing(membership, id), membership, schedule(membership));
    }

    // ------------------------------------------------------------ writing

    @Transactional
    EntryView create(UserId user, NewEntry request) {
        Membership membership = weddings.membershipOf(user);
        Instant now = clock.instant();
        EntryRow entry = checked(membership, UUID.randomUUID(), request.kind(), request, null, now);
        entries.insert(membership.weddingId(), membership.memberId(), entry);
        return shown(membership, entry.id());
    }

    @Transactional
    EntryView change(UserId user, UUID id, EntryChange request) {
        Membership membership = weddings.membershipOf(user);
        EntryRow before = existing(membership, id);
        Instant now = clock.instant();
        save(membership, checked(membership, id, before.kind(), request, before, now), request.version(), now);
        return shown(membership, id);
    }

    /** Ticking off (or un-ticking) without sending the whole entry. */
    @Transactional
    EntryView setStatus(UserId user, UUID id, Status status) {
        Membership membership = weddings.membershipOf(user);
        EntryRow before = existing(membership, id);
        Instant now = clock.instant();
        EntryRow after = new EntryRow(
                before.id(), before.kind(), before.title(), before.notes(), before.categoryId(), before.startDate(),
                before.dueDate(), status, completedAt(status, before, now), before.priorityMode(), before.priority(),
                before.parentId(), before.assigneeIds(), before.taskCount(), before.version(), before.createdAt(), now);
        save(membership, after, before.version(), now);
        return shown(membership, id);
    }

    /**
     * Turns a task into a plan item or back. Title, notes, people, priority, category and the due
     * date stay. A plan item has no parent. A task has no start date, cannot have tasks of its own
     * and waits for nothing, so an entry that becomes a task loses its dependency links.
     */
    @Transactional
    EntryView convert(UserId user, UUID id, Kind to) {
        Membership membership = weddings.membershipOf(user);
        EntryRow before = existing(membership, id);
        if (before.kind() != to) {
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
            save(membership, after, before.version(), now);
            if (to == Kind.TASK) {
                dependencies.removeAllOf(id);
            }
        }
        return shown(membership, id);
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

    // ------------------------------------------------------------ dependencies

    /** "This plan item can only start when that one is finished." Saying it twice changes nothing. */
    @Transactional
    EntryView addDependency(UserId user, UUID id, UUID dependsOnId) {
        Membership membership = weddings.membershipOf(user);
        EntryRow entry = existing(membership, id);
        EntryRow dependsOn = existing(membership, dependsOnId);
        if (entry.kind() != Kind.PLAN_ITEM || dependsOn.kind() != Kind.PLAN_ITEM) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "Only plan items can wait for each other.");
        }
        if (schedule(membership).wouldCloseACircle(id, dependsOnId)) {
            throw new ApiException(
                    ProblemCode.DEPENDENCY_CYCLE,
                    id.equals(dependsOnId)
                            ? "A plan item cannot wait for itself."
                            : "That plan item already waits for this one, directly or through others.");
        }
        dependencies.add(id, dependsOnId);
        return shown(membership, id);
    }

    @Transactional
    EntryView removeDependency(UserId user, UUID id, UUID dependsOnId) {
        Membership membership = weddings.membershipOf(user);
        existing(membership, id);
        if (!dependencies.remove(id, dependsOnId)) {
            throw new ApiException(ProblemCode.NOT_FOUND, "This plan item does not wait for that one.");
        }
        return shown(membership, id);
    }

    // ------------------------------------------------------------ what needs attention

    /** How many entries the home screen names as the ones to turn to next. */
    static final int NEXT_ENTRIES = 5;

    private static final Filter STILL_TO_DO =
            new Filter(null, List.of(Status.NOT_STARTED, Status.IN_PROGRESS), null, null, null, null);

    /**
     * What the home screen shows of the plan.
     *
     * @param today the current date where the wedding takes place
     * @param weddingDate null until the couple has fixed the day
     * @param overdue everything unfinished whose date has passed, the longest overdue first
     * @param next the entries to turn to after those: by priority, then by date
     */
    record Attention(LocalDate today, LocalDate weddingDate, List<EntryView> overdue, List<EntryView> next) {}

    @Transactional
    Attention attention(UserId user) {
        Membership membership = weddings.membershipOf(user);
        LocalDate today = LocalDate.now(clock.withZone(membership.timeZone()));
        Schedule schedule = schedule(membership);
        List<EntryView> stillToDo = entries.list(membership.weddingId(), STILL_TO_DO).stream()
                .map(row -> view(row, membership, schedule))
                .toList();
        // Overdue goes by the date alone: a level set by hand does not hide that a date has passed.
        List<EntryView> overdue = stillToDo.stream()
                .filter(view -> isOverdue(view, today))
                .sorted(order(Sort.DUE_DATE))
                .toList();
        List<EntryView> next = stillToDo.stream()
                .filter(view -> !isOverdue(view, today))
                .sorted(order(Sort.PRIORITY))
                .limit(NEXT_ENTRIES)
                .toList();
        return new Attention(today, membership.weddingDate(), overdue, next);
    }

    private static boolean isOverdue(EntryView view, LocalDate today) {
        return view.entry().dueDate() != null && view.entry().dueDate().isBefore(today);
    }

    // ------------------------------------------------------------ how an entry is shown

    private EntryView shown(Membership membership, UUID id) {
        return view(existing(membership, id), membership, schedule(membership));
    }

    private Schedule schedule(Membership membership) {
        UUID weddingId = membership.weddingId();
        return new Schedule(entries.list(weddingId, EntryRepository.EVERYTHING), dependencies.links(weddingId));
    }

    /**
     * An entry as it is shown: its people in the wedding's own order, its priority for today and
     * its place in the plan. The automatic priority depends on the date, so it is worked out here,
     * at the moment of asking, with "today" being the date where the wedding takes place.
     */
    private EntryView view(EntryRow row, Membership membership, Schedule schedule) {
        row.assigneeIds().sort(Comparator.comparingInt(membership.memberIds()::indexOf));
        List<UUID> dependsOn = schedule.dependsOn(row.id());
        List<UUID> conflicts = schedule.conflicts(row.id());
        if (row.priorityMode() == PriorityMode.MANUAL) {
            return new EntryView(row, row.priority(), Reason.SET_BY_HAND, dependsOn, conflicts);
        }
        LocalDate today = LocalDate.now(clock.withZone(membership.timeZone()));
        // Payments arrive with epic 6; until then no entry is one.
        Facts facts = new Facts(
                row.kind(), row.status(), row.startDate(), row.dueDate(), false, schedule.earliestDependentDue(row.id()));
        Assessment assessment = PriorityRule.assess(facts, today);
        return new EntryView(row, assessment.priority(), assessment.reason(), dependsOn, conflicts);
    }

    /** Each order ends in the id, so that equal entries never swap places between two requests. */
    private static Comparator<EntryView> order(Sort sort) {
        Comparator<EntryView> byDueDate =
                Comparator.comparing(view -> view.entry().dueDate(), Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<EntryView> byPriority = Comparator.comparing(EntryView::priority);
        Comparator<EntryView> byTitle = Comparator.comparing(view -> view.entry().title().toLowerCase(Locale.ROOT));
        Comparator<EntryView> byId = Comparator.comparing(view -> view.entry().id());
        return switch (sort) {
            case DUE_DATE -> byDueDate.thenComparing(byPriority).thenComparing(byTitle).thenComparing(byId);
            case PRIORITY -> byPriority.thenComparing(byDueDate).thenComparing(byTitle).thenComparing(byId);
            case TITLE -> byTitle.thenComparing(byId);
            case CREATED -> Comparator.<EntryView, Instant>comparing(view -> view.entry().createdAt()).thenComparing(byId);
        };
    }

    // ------------------------------------------------------------ rules

    private void save(Membership membership, EntryRow entry, int basedOnVersion, Instant now) {
        if (!entries.update(membership.weddingId(), entry, basedOnVersion, now)) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
    }

    private EntryRow existing(Membership membership, UUID id) {
        return entries.find(membership.weddingId(), id)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such entry."));
    }

    /** Applies the rules to what a person sent and returns the entry as it will be stored. */
    private EntryRow checked(Membership membership, UUID id, Kind kind, EntryFields request, EntryRow before, Instant now) {
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

        // Only a level a person chose is kept; the automatic one is worked out whenever the entry is read.
        Priority chosen = request.priorityMode() == PriorityMode.MANUAL ? request.priority() : null;
        return new EntryRow(
                id, kind, request.title().strip(), request.notes().strip(), request.categoryId(), request.startDate(),
                request.dueDate(), request.status(), completedAt(request.status(), before, now), request.priorityMode(),
                chosen, request.parentId(), assignees, before == null ? 0 : before.taskCount(),
                before == null ? 0 : before.version(), before == null ? now : before.createdAt(), now);
    }

    /** "Done" remembers when it was done; a change that leaves it done keeps that moment. */
    private static Instant completedAt(Status status, EntryRow before, Instant now) {
        if (status != Status.DONE) {
            return null;
        }
        return before != null && before.completedAt() != null ? before.completedAt() : now;
    }
}
