package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.CategoryRepository.CategoryRow;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.Keyed;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Change;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.FileProblem;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Outcome;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Report;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.RowProblem;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.RowReport;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileReader.Given;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileReader.Reading;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileReader.Row;
import io.github.trufelek124.weddingplanner.vendors.Vendors;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.WeddingFacts;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasks from a file (S06, D-013): the file is checked against the planner, or applied in one piece. What a file
 * does is worked out by one piece of code for both, so that a check says what applying then does. Nothing is ever
 * removed, and nothing of a file is logged.
 */
@Service
class TaskFileService {

    private static final Logger log = LoggerFactory.getLogger(TaskFileService.class);

    /** How many problems a refusal names in its fields; the check shows the rest. */
    static final int MOST_FIELDS = 100;
    private static final int MOST_SHOWN = 500;
    private static final String REFUSED = "The file cannot be taken as it is, so nothing was changed. Check it again to see what is wrong.";

    private final EntryRepository entries;
    private final CategoryRepository categories;
    private final CategoryService categoryService;
    private final Vendors vendors;
    private final Weddings weddings;
    private final Validator validator;
    private final Clock clock;

    TaskFileService(
            EntryRepository entries,
            CategoryRepository categories,
            CategoryService categoryService,
            Vendors vendors,
            Weddings weddings,
            Validator validator,
            Clock clock) {
        this.entries = entries;
        this.categories = categories;
        this.categoryService = categoryService;
        this.vendors = vendors;
        this.weddings = weddings;
        this.validator = validator;
        this.clock = clock;
    }

    /** What the file would do. Changes nothing, however often it is asked. */
    @Transactional
    Report check(UserId user, String text) {
        Membership membership = weddings.membershipOf(user);
        Worked worked = work(membership, text, false);
        Report report = worked.report(false);
        logged("checked", membership, report);
        return report;
    }

    /** Takes the file whole or not at all. */
    @Transactional
    Report apply(UserId user, String text) {
        Membership membership = weddings.membershipOf(user);
        Worked worked = work(membership, text, true);
        if (!worked.canBeApplied()) {
            throw new ApiException(ProblemCode.TASK_FILE_REFUSED, REFUSED, worked.fieldProblems());
        }
        write(membership, worked);
        Report report = worked.report(true);
        logged("applied", membership, report);
        return report;
    }

    private static void logged(String what, Membership membership, Report report) {
        log.info("A file of tasks was {} by member {}: {} new, {} changed, {} unchanged, {} refused", what,
                membership.memberId(), report.added(), report.changed(), report.unchanged(), report.refused());
    }

    // ------------------------------------------------------------ working out what a file does

    /** The planner's draft of one task after its row: what the row gives, and for the rest what the task has. */
    private record Draft(
            String title,
            String notes,
            String categoryName,
            LocalDate dueDate,
            Status status,
            PriorityMode mode,
            Priority priority,
            UUID vendorId,
            Long amount,
            PaymentKind paymentKind,
            LocalDate paidOn) {
        @Override
        public String toString() {
            return "Draft[hidden]";
        }
    }

    private record Item(Row row, Outcome outcome, List<RowProblem> problems, List<Change> changes, EntryRow before, Draft draft) {
        @Override
        public String toString() {
            return "Item[hidden]";
        }
    }

    private record Worked(List<String> fileProblems, List<Item> items, List<String> newCategories) {

        boolean canBeApplied() {
            return fileProblems.isEmpty() && !items.isEmpty() && items.stream().noneMatch(item -> item.outcome() == Outcome.REFUSED);
        }

        long count(Outcome outcome) {
            return items.stream().filter(item -> item.outcome() == outcome).count();
        }

        Report report(boolean applied) {
            boolean can = canBeApplied();
            List<RowReport> rows = items.stream()
                    .map(item -> new RowReport(item.row().number(), shown(item.row().key()), shown(item.row().title()), item.outcome(),
                            item.changes(), item.problems()))
                    .toList();
            return new Report(
                    applied && can, can, (int) count(Outcome.NEW), (int) count(Outcome.CHANGED), (int) count(Outcome.UNCHANGED),
                    (int) count(Outcome.REFUSED), can ? newCategories : List.of(),
                    fileProblems.stream().map(FileProblem::new).toList(), rows);
        }

        /** {@code tasks[<row>].<field>} for what is wrong, the file's own problems as {@code file}. */
        List<FieldProblem> fieldProblems() {
            List<FieldProblem> fields = new ArrayList<>();
            for (String problem : fileProblems) {
                fields.add(new FieldProblem("file", problem));
            }
            for (Item item : items) {
                for (RowProblem problem : item.problems()) {
                    String field = "tasks[" + item.row().number() + "]" + (problem.field() == null ? "" : "." + problem.field());
                    fields.add(new FieldProblem(field, problem.message()));
                }
            }
            return fields.size() > MOST_FIELDS ? fields.subList(0, MOST_FIELDS) : fields;
        }
    }

    private static String shown(String text) {
        return text == null || text.length() <= MOST_SHOWN ? text : text.substring(0, MOST_SHOWN) + "…";
    }

    private Worked work(Membership membership, String text, boolean hold) {
        UUID weddingId = membership.weddingId();
        WeddingFacts facts = weddings.factsOf(weddingId).orElseThrow();
        TaskFileReader reader = new TaskFileReader(validator, facts.currency(), decimalsOf(facts.currency()));
        Reading reading = reader.read(text);
        if (reading.rows() == null) {
            return new Worked(reading.problems(), List.of(), List.of());
        }
        List<Row> rows = reading.rows();

        Map<String, Integer> uses = new HashMap<>();
        rows.stream().filter(Row::keyFit).forEach(row -> uses.merge(row.key(), 1, Integer::sum));
        Map<String, EntryRow> existing = entries.findByTaskKeys(
                        weddingId, rows.stream().filter(Row::keyFit).map(Row::key).distinct().toList(), hold).stream()
                .collect(Collectors.toMap(Keyed::key, Keyed::entry));

        Names names = new Names(weddingId);
        LocalDate today = EntryRules.today(clock, membership);
        List<Item> items = new ArrayList<>();
        for (Row row : rows) {
            items.add(item(row, uses, existing, names, today));
        }
        return new Worked(reading.problems(), items, List.copyOf(names.added.values()));
    }

    private static int decimalsOf(String currency) {
        try {
            return Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException unknown) {
            return 2;
        }
    }

    /** The names of categories and vendors, as the planner compares them. */
    private final class Names {
        final UUID weddingId;
        /** What the wedding has: compared name to the name as stored. A starter list not yet offered counts as there. */
        final Map<String, String> known = new LinkedHashMap<>();
        final Map<UUID, String> categoryNames = new HashMap<>();
        /** The categories the file adds, by compared name, as the first row writes them. */
        final Map<String, String> added = new LinkedHashMap<>();
        final Map<String, Optional<UUID>> vendorIds = new HashMap<>();
        final Map<UUID, String> vendorNames = new HashMap<>();

        Names(UUID weddingId) {
            this.weddingId = weddingId;
            for (CategoryRow category : categories.list(weddingId)) {
                known.putIfAbsent(compared(category.name()), category.name());
                categoryNames.put(category.id(), category.name());
            }
            if (!categories.offered(weddingId)) {
                CategoryService.STARTER.forEach(name -> known.putIfAbsent(compared(name), name));
            }
        }

        /** The name the planner will have for the category the file names. */
        String category(String name) {
            String key = compared(name);
            String existing = known.get(key);
            return existing != null ? existing : added.computeIfAbsent(key, ignored -> name.strip());
        }

        Optional<UUID> vendor(String name) {
            return vendorIds.computeIfAbsent(name.strip(), vendors_ -> vendors.named(weddingId, name));
        }

        String vendorName(UUID id) {
            if (id == null) {
                return null;
            }
            if (!vendorNames.containsKey(id)) {
                vendorNames.put(id, vendors.namesOf(weddingId, List.of(id)).get(id));
            }
            return vendorNames.get(id);
        }
    }

    private static String compared(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private Item item(Row row, Map<String, Integer> uses, Map<String, EntryRow> existing, Names names, LocalDate today) {
        List<RowProblem> problems = new ArrayList<>(row.problems());
        EntryRow before = row.keyFit() ? existing.get(row.key()) : null;
        if (row.keyFit() && uses.get(row.key()) > 1) {
            problems.add(new RowProblem("key", "Another row of the file has this key too. Every task needs its own key."));
        }
        if (before != null && before.kind() != Kind.TASK) {
            problems.add(new RowProblem("key", "This key belongs to a step of the plan now, not to a task. Use another key to make a task."));
        }

        String categoryName = null;
        boolean categoryGiven = row.category().present();
        if (categoryGiven && row.category().value() != null) {
            categoryName = names.category(row.category().value());
        }
        UUID vendorId = null;
        if (row.vendor().present() && row.vendor().value() != null) {
            Optional<UUID> found = names.vendor(row.vendor().value());
            if (found.isEmpty()) {
                problems.add(new RowProblem("vendor", "The planner does not have exactly one vendor of that name. Add the vendor "
                        + "first (or give each vendor of that name its own), or leave the vendor out of the file."));
            } else {
                vendorId = found.get();
            }
        }
        if (!problems.isEmpty()) {
            return refused(row, problems);
        }

        Draft draft = draft(row, before, names, categoryGiven, categoryName, vendorId);
        List<RowProblem> broken = rulesBroken(draft, today);
        if (!broken.isEmpty()) {
            return refused(row, broken);
        }
        if (before == null) {
            return new Item(row, Outcome.NEW, List.of(), List.of(), null, draft);
        }
        List<Change> changes = changes(before, draft, names, today);
        return new Item(row, changes.isEmpty() ? Outcome.UNCHANGED : Outcome.CHANGED, List.of(), changes, before, draft);
    }

    private static Item refused(Row row, List<RowProblem> problems) {
        List<RowProblem> ordered = problems.stream()
                .sorted(Comparator.comparingInt((RowProblem problem) -> problem.field() == null
                                ? -1
                                : TaskFileReader.ROW_FIELDS.indexOf(problem.field()) < 0
                                        ? TaskFileReader.ROW_FIELDS.size()
                                        : TaskFileReader.ROW_FIELDS.indexOf(problem.field())))
                .toList();
        return new Item(row, Outcome.REFUSED, ordered, List.of(), null, null);
    }

    private Draft draft(Row row, EntryRow before, Names names, boolean categoryGiven, String categoryName, UUID vendorId) {
        boolean has = before != null;
        PriorityMode mode = has ? before.priorityMode() : PriorityMode.AUTO;
        Priority priority = has && mode == PriorityMode.MANUAL ? before.priority() : null;
        if (row.priority().present()) {
            mode = row.priority().value() == null ? PriorityMode.AUTO : PriorityMode.MANUAL;
            priority = row.priority().value();
        }
        Status status = given(row.status(), has ? before.status() : Status.NOT_STARTED);
        Long amount = given(row.amount(), has ? before.amount() : null);
        // A paid date that is only carried over stays while it still applies: the task is a payment and done.
        LocalDate carriedPaidOn = has && amount != null && status == Status.DONE ? before.paidOn() : null;
        String category = categoryGiven ? categoryName : has && before.categoryId() != null ? names.categoryNames.get(before.categoryId()) : null;
        return new Draft(
                row.title(),
                given(row.notes(), has ? before.notes() : ""),
                category,
                given(row.dueDate(), has ? before.dueDate() : null),
                status,
                mode,
                priority,
                row.vendor().present() ? vendorId : has ? before.vendorId() : null,
                amount,
                given(row.paymentKind(), has ? before.paymentKind() : null),
                given(row.paidOn(), carriedPaidOn));
    }

    private static <T> T given(Given<T> field, T otherwise) {
        return field.present() ? field.value() : otherwise;
    }

    /** The planner's own rules for the task as the row leaves it, with the form's words and the file's field names. */
    private List<RowProblem> rulesBroken(Draft draft, LocalDate today) {
        NewEntry request = request(draft);
        List<FieldProblem> broken = new ArrayList<>(EntryRules.ofTheDates(Kind.TASK, request));
        broken.addAll(EntryRules.ofTheMoney(Kind.TASK, request, today));
        return broken.stream().map(problem -> new RowProblem(fileField(problem.field()), problem.message())).toList();
    }

    private static NewEntry request(Draft draft) {
        return new NewEntry(null, Kind.TASK, draft.title(), draft.notes(), null, null, draft.dueDate(), draft.status(), List.of(),
                draft.mode(), draft.priority(), null, draft.vendorId(), draft.amount(), draft.paymentKind(), draft.paidOn());
    }

    /** The file's name of a field the planner's rules name by the API's. */
    private static String fileField(String field) {
        return switch (field) {
            case "categoryId" -> "category";
            case "vendorId" -> "vendor";
            default -> field;
        };
    }

    // ------------------------------------------------------------ what a row changes

    private List<Change> changes(EntryRow before, Draft draft, Names names, LocalDate today) {
        EntryRow after = entryOf(before.id(), before, draft, before.categoryId(), Instant.EPOCH, today, before.createdAt());
        List<Change> changes = new ArrayList<>();
        change(changes, "title", before.title(), after.title());
        change(changes, "notes", before.notes(), after.notes());
        change(changes, "dueDate", text(before.dueDate()), text(after.dueDate()));
        change(changes, "status", before.status().name(), after.status().name());
        change(changes, "priority", chosen(before), chosen(after));
        String categoryBefore = before.categoryId() == null ? null : names.categoryNames.get(before.categoryId());
        if (!Objects.equals(categoryBefore == null ? null : compared(categoryBefore),
                draft.categoryName() == null ? null : compared(draft.categoryName()))) {
            changes.add(new Change("category", categoryBefore, draft.categoryName()));
        }
        if (!Objects.equals(before.vendorId(), after.vendorId())) {
            changes.add(new Change("vendor", names.vendorName(before.vendorId()), names.vendorName(after.vendorId())));
        }
        change(changes, "amount", text(before.amount()), text(after.amount()));
        change(changes, "paymentKind", text(before.paymentKind()), text(after.paymentKind()));
        change(changes, "paidOn", text(before.paidOn()), text(after.paidOn()));
        return changes;
    }

    private static void change(List<Change> changes, String field, String from, String to) {
        if (!Objects.equals(from, to)) {
            changes.add(new Change(field, from, to));
        }
    }

    private static String chosen(EntryRow row) {
        return row.priorityMode() == PriorityMode.MANUAL && row.priority() != null ? row.priority().name() : null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Enum<?> named ? named.name() : value.toString();
    }

    /** The entry as it will be stored: the same assembly as the form's, from the draft. */
    private static EntryRow entryOf(UUID id, EntryRow before, Draft draft, UUID categoryId, Instant now, LocalDate today, Instant createdAt) {
        return new EntryRow(
                id, Kind.TASK, draft.title().strip(), draft.notes().strip(), categoryId, null, draft.dueDate(), draft.status(),
                EntryRules.completedAt(draft.status(), before, now), draft.mode(), draft.priority(), before == null ? null : before.parentId(),
                draft.vendorId(), draft.amount(), draft.paymentKind(),
                EntryRules.paidOn(draft.amount(), draft.status(), draft.paidOn(), before, today),
                before == null ? new ArrayList<>() : before.assigneeIds(), before == null ? 0 : before.taskCount(),
                before == null ? 0 : before.version(), createdAt, now, before == null ? null : before.fromSuggestionId());
    }

    // ------------------------------------------------------------ writing

    private void write(Membership membership, Worked worked) {
        UUID weddingId = membership.weddingId();
        Instant now = clock.instant();
        LocalDate today = EntryRules.today(clock, membership);
        boolean anyCategory = worked.items().stream().anyMatch(item -> item.draft() != null && item.draft().categoryName() != null);
        if (anyCategory) {
            categoryService.offerStarter(weddingId);
        }
        Map<String, UUID> categoryIds = new HashMap<>();
        for (Item item : worked.items()) {
            if (item.outcome() == Outcome.UNCHANGED) {
                continue;
            }
            Draft draft = item.draft();
            UUID categoryId = draft.categoryName() == null
                    ? null
                    : categoryIds.computeIfAbsent(compared(draft.categoryName()), ignored -> categoryOf(weddingId, draft.categoryName()));
            if (item.outcome() == Outcome.NEW) {
                EntryRow made = entryOf(UUID.randomUUID(), null, draft, categoryId, now, today, now);
                try {
                    entries.insert(weddingId, membership.memberId(), made, item.row().key());
                } catch (DuplicateKeyException sameKey) {
                    throw new ApiException(ProblemCode.TASK_FILE_REFUSED,
                            "Another upload of tasks was taken at the same moment, so nothing was changed. Check the file again.",
                            List.of(new FieldProblem("tasks[" + item.row().number() + "].key",
                                    "Another upload made a task with this key at the same moment.")));
                }
            } else {
                EntryRow changed = entryOf(item.before().id(), item.before(), draft, categoryId, now, today, item.before().createdAt());
                if (!entries.update(weddingId, changed, item.before().version(), now)) {
                    throw new ApiException(ProblemCode.TASK_FILE_REFUSED,
                            "A task was changed at the same moment, so nothing was changed. Check the file again.",
                            List.of(new FieldProblem("tasks[" + item.row().number() + "]", "This task was changed at the same moment.")));
                }
            }
        }
    }

    /** The category of that name, added when the wedding has none; a name the wedding gained meanwhile is found. */
    private UUID categoryOf(UUID weddingId, String name) {
        Optional<UUID> found = categories.findByName(weddingId, name);
        if (found.isPresent()) {
            return found.get();
        }
        categories.insertIfAbsent(weddingId, UUID.randomUUID(), name.strip());
        return categories.findByName(weddingId, name).orElseThrow();
    }
}
