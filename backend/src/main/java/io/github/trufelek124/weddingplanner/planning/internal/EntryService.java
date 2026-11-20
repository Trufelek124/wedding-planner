package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.EntryUse;
import io.github.trufelek124.weddingplanner.planning.PlanEntries.Removal;
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
import io.github.trufelek124.weddingplanner.vendors.Vendors;
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
    private final Vendors vendors;
    private final Weddings weddings;
    private final List<EntryUse> uses;
    private final Clock clock;

    EntryService(
            EntryRepository entries,
            CategoryRepository categories,
            DependencyRepository dependencies,
            Vendors vendors,
            Weddings weddings,
            List<EntryUse> uses,
            Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.dependencies = dependencies;
        this.vendors = vendors;
        this.weddings = weddings;
        this.uses = uses;
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

    /**
     * Adds an entry. A sender that did not learn whether its request arrived sends it again with the
     * same id: the entry is then there already, and is answered as it is now, not added a second time.
     */
    @Transactional
    EntryView create(UserId user, NewEntry request) {
        return create(weddings.membershipOf(user), request, null);
    }

    /**
     * The same for a member of the wedding, and the way an entry gets to say which suggestion made it: no request
     * of a person can carry that, only another module's own adding.
     */
    @Transactional
    EntryView create(Membership membership, NewEntry request, UUID fromSuggestionId) {
        if (request.id() != null && entries.find(membership.weddingId(), request.id()).isPresent()) {
            return shown(membership, request.id());
        }
        Instant now = clock.instant();
        UUID id = request.id() != null ? request.id() : UUID.randomUUID();
        EntryRow entry = checked(membership, id, request.kind(), request, null, now);
        if (fromSuggestionId != null) {
            entry = entry.madeBy(fromSuggestionId);
        }
        entries.insert(membership.weddingId(), membership.memberId(), entry, null);
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
                before.dueDate(), status, EntryRules.completedAt(status, before, now), before.priorityMode(), before.priority(),
                before.parentId(), before.vendorId(), before.amount(), before.paymentKind(),
                EntryRules.paidOn(before.amount(), status, null, before, today(membership)), before.assigneeIds(), before.taskCount(),
                before.version(), before.createdAt(), now, before.fromSuggestionId());
        save(membership, after, before.version(), now);
        return shown(membership, id);
    }

    /**
     * Turns a task into a plan item or back. Title, notes, people, priority, category and the due
     * date stay. A plan item has no parent. A task has no start date, cannot have tasks of its own
     * and waits for nothing, so an entry that becomes a task loses its dependency links. A payment
     * stays a task: a plan item carries no money.
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
            if (to == Kind.PLAN_ITEM && before.isPayment()) {
                // Nothing a person entered is dropped without a word, an amount least of all.
                throw new ApiException(
                        ProblemCode.BAD_REQUEST, "This task is a payment, and a plan item carries no money. Remove the amount first.");
            }
            Instant now = clock.instant();
            EntryRow after = new EntryRow(
                    before.id(), to, before.title(), before.notes(), before.categoryId(),
                    to == Kind.TASK ? null : before.startDate(), before.dueDate(), before.status(), before.completedAt(),
                    before.priorityMode(), before.priority(), to == Kind.PLAN_ITEM ? null : before.parentId(),
                    before.vendorId(), before.amount(), before.paymentKind(), before.paidOn(), before.assigneeIds(),
                    before.taskCount(),
                    before.version(), before.createdAt(), now, before.fromSuggestionId());
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

    /** The version an entry has when it is made: every save counts one more. */
    private static final int VERSION_WHEN_MADE = 0;

    /**
     * Removes the entry that a suggestion made, when it is still as it was made. The row is held, so that nobody
     * saves it or hangs something on it between the look and the removal.
     */
    @Transactional
    Removal removeIfUntouched(UUID weddingId, UUID entryId, UUID suggestionId) {
        EntryRow entry = entries.findForUpdate(weddingId, entryId).orElse(null);
        if (entry == null || !suggestionId.equals(entry.fromSuggestionId())) {
            return Removal.GONE;
        }
        boolean untouched = entry.version() == VERSION_WHEN_MADE
                && entry.status() == Status.NOT_STARTED
                && entry.taskCount() == 0
                && !dependencies.anyOf(entryId)
                && uses.stream().noneMatch(use -> use.pointsTo(weddingId, entryId));
        if (!untouched) {
            return Removal.CHANGED;
        }
        entries.delete(weddingId, entryId);
        return Removal.REMOVED;
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

    /** How many payments the home screen names as the next to be made. */
    static final int NEXT_PAYMENTS = 5;

    private static final Filter STILL_TO_DO =
            new Filter(null, List.of(Status.NOT_STARTED, Status.IN_PROGRESS), null, false, null, null, null, null, null);

    /**
     * What the home screen shows of the plan.
     *
     * @param today the current date where the wedding takes place
     * @param weddingDate null until the couple has fixed the day
     * @param overdue everything unfinished whose date has passed, the longest overdue first
     * @param next the entries to turn to after those: by priority, then by date
     * @param nextPayments the payments still to be made, the soonest due first, overdue ones included
     */
    record Attention(
            LocalDate today, LocalDate weddingDate, List<EntryView> overdue, List<EntryView> next, List<EntryView> nextPayments) {}

    @Transactional
    Attention attention(UserId user) {
        Membership membership = weddings.membershipOf(user);
        LocalDate today = today(membership);
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
        // Money goes by the date alone as well: what has to be paid first is first, whatever its level.
        List<EntryView> nextPayments = stillToDo.stream()
                .filter(view -> view.entry().isPayment())
                .sorted(order(Sort.DUE_DATE))
                .limit(NEXT_PAYMENTS)
                .toList();
        return new Attention(today, membership.weddingDate(), overdue, next, nextPayments);
    }

    /** The current date where the wedding takes place. */
    private LocalDate today(Membership membership) {
        return EntryRules.today(clock, membership);
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
        Facts facts = new Facts(
                row.kind(), row.status(), row.startDate(), row.dueDate(), row.isPayment(), schedule.earliestDependentDue(row.id()));
        LocalDate today = today(membership);
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

        problems.addAll(EntryRules.ofTheDates(kind, request));
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
        if (request.vendorId() != null && !vendors.exists(weddingId, request.vendorId())) {
            problems.add(new FieldProblem("vendorId", "Choose one of your vendors."));
        }
        LocalDate today = today(membership);
        problems.addAll(EntryRules.ofTheMoney(kind, request, today));
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }

        // Only a level a person chose is kept; the automatic one is worked out whenever the entry is read.
        Priority chosen = request.priorityMode() == PriorityMode.MANUAL ? request.priority() : null;
        return new EntryRow(
                id, kind, request.title().strip(), request.notes().strip(), request.categoryId(), request.startDate(),
                request.dueDate(), request.status(), EntryRules.completedAt(request.status(), before, now), request.priorityMode(),
                chosen, request.parentId(), request.vendorId(), request.amount(), request.paymentKind(),
                EntryRules.paidOn(request.amount(), request.status(), request.paidOn(), before, today), assignees,
                before == null ? 0 : before.taskCount(), before == null ? 0 : before.version(),
                before == null ? now : before.createdAt(), now, before == null ? null : before.fromSuggestionId());
    }
}
