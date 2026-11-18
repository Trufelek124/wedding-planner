package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.EntryService.Attention;
import io.github.trufelek124.weddingplanner.planning.internal.BudgetService.Line;
import io.github.trufelek124.weddingplanner.planning.internal.BudgetService.Summary;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Reason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** What the planning endpoints take and give. The messages are shown to people next to the field. */
final class PlanningDtos {

    static final int TITLE_LENGTH = 200;
    static final int NOTES_LENGTH = 10_000;
    private static final String TITLE_NEEDED = "Enter a title.";
    private static final String TITLE_TOO_LONG = "Use at most 200 characters.";
    private static final String NOTES_TOO_LONG = "Use at most 10,000 characters.";
    /** Ten thousand million of the currency's main unit: far above any wedding, far below what a sum can hold. */
    static final long MOST_MONEY = 1_000_000_000_000L;
    private static final String AMOUNT_ABOVE_ZERO = "Enter an amount above zero.";
    private static final String AMOUNT_NOT_BELOW_ZERO = "Enter an amount, or zero.";
    private static final String AMOUNT_TOO_LARGE = "That amount is too large.";
    private static final String AMOUNT_DESCRIPTION =
            "Tasks only. With an amount the task is a payment: what it costs, in the smallest unit of the wedding's currency.";
    private static final String PAID_ON_DESCRIPTION =
            "For a payment that is done: the day it was paid. Left out, it is the day already recorded, or today.";

    private PlanningDtos() {}

    /** The fields a person fills in; the same for a new entry and for a change. */
    interface EntryFields {
        String title();

        String notes();

        UUID categoryId();

        LocalDate startDate();

        LocalDate dueDate();

        Status status();

        List<UUID> assigneeIds();

        PriorityMode priorityMode();

        Priority priority();

        UUID parentId();

        Long amount();

        PaymentKind paymentKind();

        LocalDate paidOn();
    }

    @Schema(name = "NewEntry", description = "A plan item or a task to add.")
    record NewEntry(
            @NotNull(message = "Say whether this is a plan item or a task.") Kind kind,
            @NotBlank(message = TITLE_NEEDED) @Size(max = TITLE_LENGTH, message = TITLE_TOO_LONG) String title,
            @Schema(description = "Free text; may be empty.") @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes,
            @Schema(nullable = true) UUID categoryId,
            @Schema(description = "Plan items only: the day the step starts. Needs a due date.", nullable = true) LocalDate startDate,
            @Schema(description = "The day a task is due, or the day a plan item ends.", nullable = true) LocalDate dueDate,
            @NotNull Status status,
            @Schema(description = "Who does it: none, one or both of the wedding's members.") @NotNull @Size(max = 2) List<UUID> assigneeIds,
            @NotNull PriorityMode priorityMode,
            @Schema(description = "With priorityMode MANUAL: the chosen priority. With AUTO: null.", nullable = true) Priority priority,
            @Schema(description = "Tasks only: the plan item this task belongs to.", nullable = true) UUID parentId,
            @Schema(description = AMOUNT_DESCRIPTION, nullable = true)
                    @Positive(message = AMOUNT_ABOVE_ZERO)
                    @Max(value = MOST_MONEY, message = AMOUNT_TOO_LARGE)
                    Long amount,
            @Schema(description = "For a payment: which part of the price it is.", nullable = true) PaymentKind paymentKind,
            @Schema(description = PAID_ON_DESCRIPTION, nullable = true) LocalDate paidOn)
            implements EntryFields {}

    @Schema(name = "EntryChange", description = "All fields of an entry as they should be from now on.")
    record EntryChange(
            @Schema(description = "The version of the entry this change is based on.") @NotNull Integer version,
            @NotBlank(message = TITLE_NEEDED) @Size(max = TITLE_LENGTH, message = TITLE_TOO_LONG) String title,
            @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes,
            @Schema(nullable = true) UUID categoryId,
            @Schema(nullable = true) LocalDate startDate,
            @Schema(nullable = true) LocalDate dueDate,
            @NotNull Status status,
            @NotNull @Size(max = 2) List<UUID> assigneeIds,
            @NotNull PriorityMode priorityMode,
            @Schema(nullable = true) Priority priority,
            @Schema(nullable = true) UUID parentId,
            @Schema(description = AMOUNT_DESCRIPTION, nullable = true)
                    @Positive(message = AMOUNT_ABOVE_ZERO)
                    @Max(value = MOST_MONEY, message = AMOUNT_TOO_LARGE)
                    Long amount,
            @Schema(nullable = true) PaymentKind paymentKind,
            @Schema(description = PAID_ON_DESCRIPTION, nullable = true) LocalDate paidOn)
            implements EntryFields {}

    @Schema(name = "EntryStatusChange")
    record StatusChange(@NotNull Status status) {}

    @Schema(name = "EntryConversion")
    record Conversion(@Schema(description = "What the entry should become.") @NotNull Kind kind) {}

    @Schema(name = "Entry", description = "A plan item or a task.")
    record EntryResponse(
            UUID id,
            Kind kind,
            String title,
            String notes,
            @Schema(nullable = true) UUID categoryId,
            @Schema(description = "Plan items only; null for a task.", nullable = true) LocalDate startDate,
            @Schema(description = "The day a task is due, or the day a plan item ends; null while not scheduled.", nullable = true)
                    LocalDate dueDate,
            Status status,
            @Schema(description = "When it was marked done; null otherwise.", nullable = true) Instant completedAt,
            PriorityMode priorityMode,
            @Schema(description = "The level chosen by a person (MANUAL) or worked out for today (AUTO).") Priority priority,
            @Schema(description = "Why the entry has this level.") Reason priorityReason,
            @Schema(description = "The members who do it: none, one or both.") List<UUID> assigneeIds,
            @Schema(description = "Tasks only: the plan item it belongs to.", nullable = true) UUID parentId,
            @Schema(
                            description = "For a payment: what it costs, in the smallest unit of the wedding's currency "
                                    + "(grosze). null for an entry that carries no money.",
                            nullable = true)
                    Long amount,
            @Schema(description = "For a payment: which part of the price it is, if that was said.", nullable = true)
                    PaymentKind paymentKind,
            @Schema(description = "The day a payment was paid; null until it is done.", nullable = true) LocalDate paidOn,
            @Schema(description = "For a plan item: how many tasks belong to it.") int taskCount,
            @Schema(description = "The plan items this one waits for: it can only start when they are finished.")
                    List<UUID> dependsOnIds,
            @Schema(description = "Those of dependsOnIds that end after this entry is meant to begin.") List<UUID> conflictsWithIds,
            @Schema(description = "Send this back with a change; it tells whether someone else saved in between.") int version,
            Instant createdAt,
            Instant updatedAt) {

        static EntryResponse of(EntryView view) {
            EntryRow row = view.entry();
            return new EntryResponse(
                    row.id(), row.kind(), row.title(), row.notes(), row.categoryId(), row.startDate(), row.dueDate(),
                    row.status(), row.completedAt(), row.priorityMode(), view.priority(), view.priorityReason(),
                    row.assigneeIds(), row.parentId(), row.amount(), row.paymentKind(), row.paidOn(), row.taskCount(),
                    view.dependsOnIds(), view.conflictsWithIds(),
                    row.version(), row.createdAt(), row.updatedAt());
        }
    }

    @Schema(name = "Home", description = "What the first page shows: how long until the wedding, and what to do next.")
    record HomeResponse(
            @Schema(description = "Today's date where the wedding takes place.") LocalDate today,
            @Schema(description = "The wedding day; null while it is not fixed.", nullable = true) LocalDate weddingDate,
            @Schema(
                            description = "Whole days from today to the wedding day: 0 on the day, negative after it; "
                                    + "null while the day is not fixed.",
                            nullable = true)
                    Long daysLeft,
            @Schema(description = "Every unfinished entry whose date has passed, the longest overdue first.")
                    List<EntryResponse> overdue,
            @Schema(description = "The five unfinished entries to turn to next, by priority and then by date. "
                            + "Overdue entries are not repeated here.")
                    List<EntryResponse> next,
            @Schema(description = "The money at a glance.") BudgetGlance budget,
            @Schema(description = "The five payments still to be made that are due soonest; those without a date come last.")
                    List<EntryResponse> nextPayments) {

        static HomeResponse of(Attention attention, Summary budget) {
            LocalDate weddingDate = attention.weddingDate();
            return new HomeResponse(
                    attention.today(),
                    weddingDate,
                    weddingDate == null ? null : ChronoUnit.DAYS.between(attention.today(), weddingDate),
                    attention.overdue().stream().map(EntryResponse::of).toList(),
                    attention.next().stream().map(EntryResponse::of).toList(),
                    new BudgetGlance(budget.total(), budget.planned(), budget.committed(), budget.paid()),
                    attention.nextPayments().stream().map(EntryResponse::of).toList());
        }
    }

    // ---------------------------------------------------------------- the budget

    @Schema(name = "BudgetGlance", description = "The budget's four sums, in the smallest unit of the wedding's currency.")
    record BudgetGlance(
            @Schema(description = "What the couple means to spend on everything; null while they have not said.", nullable = true)
                    Long total,
            @Schema(description = "The planned amounts of all categories together.") long planned,
            @Schema(description = "Every payment that is not dropped, paid or not.") long committed,
            @Schema(description = "The payments that are done.") long paid) {}

    @Schema(name = "PlannedAmount", description = "What the couple means to spend on one category.")
    record PlannedAmount(
            @NotNull(message = "Choose one of the categories.") UUID categoryId,
            @NotNull(message = AMOUNT_NOT_BELOW_ZERO)
                    @PositiveOrZero(message = AMOUNT_NOT_BELOW_ZERO)
                    @Max(value = MOST_MONEY, message = AMOUNT_TOO_LARGE)
                    Long amount) {}

    @Schema(name = "BudgetChange", description = "The budget as it should be from now on.")
    record BudgetChange(
            @Schema(description = "The version of the budget this change is based on.") @NotNull Integer version,
            @Schema(description = "What the couple means to spend on everything; null for not said.", nullable = true)
                    @PositiveOrZero(message = AMOUNT_NOT_BELOW_ZERO)
                    @Max(value = MOST_MONEY, message = AMOUNT_TOO_LARGE)
                    Long total,
            @Schema(description = "The planned amount of every category that has one. A category left out has none.")
                    @NotNull
                    @Size(max = 500)
                    List<@NotNull @Valid PlannedAmount> planned) {}

    @Schema(name = "BudgetLine", description = "One category of the budget.")
    record BudgetLine(
            UUID categoryId,
            String name,
            @Schema(description = "What the couple means to spend on it; null while they have not said.", nullable = true)
                    Long planned,
            @Schema(description = "Its payments that are not dropped, paid or not.") long committed,
            @Schema(description = "Its payments that are done.") long paid,
            @Schema(description = "How many payments that are not dropped it has.") int payments) {

        static BudgetLine of(Line line) {
            return new BudgetLine(line.categoryId(), line.name(), line.planned(), line.committed(), line.paid(), line.payments());
        }
    }

    @Schema(name = "BudgetRest", description = "The payments that are filed under no category.")
    record BudgetRest(long committed, long paid, int payments) {}

    @Schema(
            name = "Budget",
            description = "Planned, committed and paid, in total and per category. Every amount is a whole number of the "
                    + "smallest unit of the wedding's currency (grosze).")
    record BudgetResponse(
            @Schema(description = "What the couple means to spend on everything; null while they have not said.", nullable = true)
                    Long total,
            @Schema(description = "The planned amounts of all categories together.") long planned,
            @Schema(description = "Every payment that is not dropped, paid or not.") long committed,
            @Schema(description = "The payments that are done.") long paid,
            @Schema(description = "Every category, in the couple's order, with or without money.") List<BudgetLine> categories,
            BudgetRest uncategorised,
            @Schema(description = "Send this back with a change; it tells whether someone else saved in between.") int version) {

        static BudgetResponse of(Summary summary) {
            return new BudgetResponse(
                    summary.total(), summary.planned(), summary.committed(), summary.paid(),
                    summary.categories().stream().map(BudgetLine::of).toList(),
                    new BudgetRest(summary.rest().committed(), summary.rest().paid(), summary.rest().payments()),
                    summary.version());
        }
    }

    @Schema(name = "CategoryName")
    record CategoryName(
            @NotBlank(message = "Enter a name.") @Size(max = 60, message = "Use at most 60 characters.") String name) {}

    @Schema(name = "Category", description = "A heading entries can be filed under, for example Venue.")
    record CategoryResponse(UUID id, String name) {}
}
