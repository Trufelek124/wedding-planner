package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.EntryService.Attention;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Reason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
            @Schema(description = "Tasks only: the plan item this task belongs to.", nullable = true) UUID parentId)
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
            @Schema(nullable = true) UUID parentId)
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
                    row.assigneeIds(), row.parentId(), row.taskCount(), view.dependsOnIds(), view.conflictsWithIds(),
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
                    List<EntryResponse> next) {

        static HomeResponse of(Attention attention) {
            LocalDate weddingDate = attention.weddingDate();
            return new HomeResponse(
                    attention.today(),
                    weddingDate,
                    weddingDate == null ? null : ChronoUnit.DAYS.between(attention.today(), weddingDate),
                    attention.overdue().stream().map(EntryResponse::of).toList(),
                    attention.next().stream().map(EntryResponse::of).toList());
        }
    }

    @Schema(name = "CategoryName")
    record CategoryName(
            @NotBlank(message = "Enter a name.") @Size(max = 60, message = "Use at most 60 characters.") String name) {}

    @Schema(name = "Category", description = "A heading entries can be filed under, for example Venue.")
    record CategoryResponse(UUID id, String name) {}
}
