package io.github.trufelek124.weddingplanner.guests.internal;

import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.GuestRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.HouseholdRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestTypes.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the guest endpoints take and give. The messages are shown to people next to the field.
 * Everything about a guest is personal data, so every record here hides it in toString(): the web
 * framework prints what an endpoint is called with through that method once its logging is at TRACE.
 */
final class GuestDtos {

    static final int NAME_LENGTH = 120;
    static final int PHONE_LENGTH = 40;
    static final int EMAIL_LENGTH = 254;
    static final int NEEDS_LENGTH = 500;
    static final int NOTES_LENGTH = 10_000;
    static final int MOST_CHILDREN = 20;

    private static final String NAME_NEEDED = "Enter a name.";
    private static final String NAME_TOO_LONG = "Use at most 120 characters.";
    private static final String STATUS_NEEDED = "Say where this guest stands: not invited yet, invited, confirmed, declined or maybe.";
    private static final String CHILDREN_RANGE = "Enter a number from 0 to 20.";
    private static final String YES_OR_NO = "Say yes or no.";
    private static final String PHONE_TOO_LONG = "Use at most 40 characters.";
    private static final String EMAIL_TOO_LONG = "That address is too long to keep.";
    private static final String NEEDS_TOO_LONG = "Use at most 500 characters.";
    private static final String NOTES_TOO_LONG = "Use at most 10,000 characters.";
    private static final String SIDE_DESCRIPTION = "The member whose guest this is; null for a guest of both.";

    private GuestDtos() {}

    /** The fields a person fills in; the same for a new guest and for a change. */
    interface GuestFields {
        String name();

        UUID sideMemberId();

        UUID householdId();

        Status status();

        Boolean plusOne();

        Integer children();

        String dietaryNeeds();

        Boolean needsAccommodation();

        String phone();

        String email();

        String notes();
    }

    @Schema(name = "NewGuest", description = "A guest to put on the list.")
    record NewGuest(
            @NotBlank(message = NAME_NEEDED) @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String name,
            @Schema(description = SIDE_DESCRIPTION, nullable = true) UUID sideMemberId,
            @Schema(description = "The household the guest is invited with.", nullable = true) UUID householdId,
            @NotNull(message = STATUS_NEEDED) Status status,
            @Schema(description = "Comes with one more person who is not on the list by name.") @NotNull(message = YES_OR_NO) Boolean plusOne,
            @Schema(description = "How many children come along.")
                    @NotNull(message = CHILDREN_RANGE)
                    @Min(value = 0, message = CHILDREN_RANGE)
                    @Max(value = MOST_CHILDREN, message = CHILDREN_RANGE)
                    Integer children,
            @Schema(description = "May be empty.") @NotNull @Size(max = NEEDS_LENGTH, message = NEEDS_TOO_LONG) String dietaryNeeds,
            @NotNull(message = YES_OR_NO) Boolean needsAccommodation,
            @Schema(description = "As the person writes it; may be empty.") @NotNull @Size(max = PHONE_LENGTH, message = PHONE_TOO_LONG)
                    String phone,
            @Schema(description = "May be empty.") @NotNull @Size(max = EMAIL_LENGTH, message = EMAIL_TOO_LONG) String email,
            @Schema(description = "Free text; may be empty.") @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes)
            implements GuestFields {

        @Override
        public String toString() {
            return "NewGuest[status=" + status + "]";
        }
    }

    @Schema(name = "GuestChange", description = "All fields of a guest as they should be from now on.")
    record GuestChange(
            @Schema(description = "The version of the guest this change is based on.") @NotNull Integer version,
            @NotBlank(message = NAME_NEEDED) @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String name,
            @Schema(nullable = true) UUID sideMemberId,
            @Schema(nullable = true) UUID householdId,
            @NotNull(message = STATUS_NEEDED) Status status,
            @NotNull(message = YES_OR_NO) Boolean plusOne,
            @NotNull(message = CHILDREN_RANGE)
                    @Min(value = 0, message = CHILDREN_RANGE)
                    @Max(value = MOST_CHILDREN, message = CHILDREN_RANGE)
                    Integer children,
            @NotNull @Size(max = NEEDS_LENGTH, message = NEEDS_TOO_LONG) String dietaryNeeds,
            @NotNull(message = YES_OR_NO) Boolean needsAccommodation,
            @NotNull @Size(max = PHONE_LENGTH, message = PHONE_TOO_LONG) String phone,
            @NotNull @Size(max = EMAIL_LENGTH, message = EMAIL_TOO_LONG) String email,
            @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes)
            implements GuestFields {

        @Override
        public String toString() {
            return "GuestChange[version=" + version + ", status=" + status + "]";
        }
    }

    @Schema(name = "GuestStatusChange")
    record StatusChange(@NotNull(message = STATUS_NEEDED) Status status) {}

    @Schema(name = "Guest", description = "Someone on the guest list.")
    record GuestResponse(
            UUID id,
            String name,
            @Schema(description = SIDE_DESCRIPTION, nullable = true) UUID sideMemberId,
            @Schema(description = "The household the guest is invited with.", nullable = true) UUID householdId,
            Status status,
            @Schema(description = "Comes with one more person who is not on the list by name.") boolean plusOne,
            @Schema(description = "How many children come along.") int children,
            @Schema(description = "May be empty.") String dietaryNeeds,
            boolean needsAccommodation,
            @Schema(description = "May be empty.") String phone,
            @Schema(description = "May be empty.") String email,
            String notes,
            @Schema(description = "How many people this entry stands for: the guest, the plus-one and the children.") int heads,
            @Schema(description = "Send this back with a change; it tells whether someone else saved in between.") int version,
            Instant createdAt,
            Instant updatedAt) {

        static GuestResponse of(GuestRow row) {
            return new GuestResponse(
                    row.id(), row.name(), row.sideMemberId(), row.householdId(), row.status(), row.plusOne(), row.children(),
                    row.dietaryNeeds(), row.needsAccommodation(), row.phone(), row.email(), row.notes(),
                    1 + (row.plusOne() ? 1 : 0) + row.children(), row.version(), row.createdAt(), row.updatedAt());
        }

        @Override
        public String toString() {
            return "Guest[id=" + id + ", status=" + status + ", version=" + version + "]";
        }
    }

    @Schema(name = "HouseholdName")
    record HouseholdName(
            @NotBlank(message = "Enter a name.") @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String name) {

        @Override
        public String toString() {
            return "HouseholdName[]";
        }
    }

    @Schema(name = "Household", description = "People who are invited together: a family, a couple.")
    record HouseholdResponse(UUID id, String name, @Schema(description = "How many guests belong to it.") int guests) {

        static HouseholdResponse of(HouseholdRow row) {
            return new HouseholdResponse(row.id(), row.name(), row.guests());
        }

        @Override
        public String toString() {
            return "Household[id=" + id + ", guests=" + guests + "]";
        }
    }

    // ---------------------------------------------------------------- counts

    @Schema(name = "HeadCount", description = "How many people: the guests themselves, their plus-ones and their children.")
    record HeadCount(
            @Schema(description = "The guests on the list by name.") int guests,
            int plusOnes,
            int children,
            @Schema(description = "All three together.") int heads) {

        static final HeadCount NOBODY = new HeadCount(0, 0, 0, 0);

        static HeadCount of(int guests, int plusOnes, int children) {
            return new HeadCount(guests, plusOnes, children, guests + plusOnes + children);
        }

        HeadCount plus(HeadCount other) {
            return of(guests + other.guests, plusOnes + other.plusOnes, children + other.children);
        }
    }

    @Schema(name = "GuestsByStatus")
    record ByStatus(Status status, HeadCount count) {}

    @Schema(name = "GuestsBySide")
    record BySide(
            @Schema(description = SIDE_DESCRIPTION, nullable = true) UUID sideMemberId,
            HeadCount count,
            @Schema(description = "The same side status by status, in the order of the statuses.") List<ByStatus> byStatus) {}

    @Schema(
            name = "GuestCounts",
            description = "The head count: in all, status by status, and side by side. Every status and every side is "
                    + "there, also with nobody in it.")
    record GuestCounts(
            HeadCount total,
            @Schema(description = "In the order: not invited, invited, confirmed, declined, maybe.") List<ByStatus> byStatus,
            @Schema(description = "The two members in the wedding's order, then the guests of both.") List<BySide> bySide) {}
}
