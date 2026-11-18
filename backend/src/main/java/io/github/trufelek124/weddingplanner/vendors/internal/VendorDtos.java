package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.vendors.VendorUse.Use;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorRepository.VendorRow;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * What the vendor endpoints take and give. The messages are shown to people next to the field.
 * Every record here holds names and contact details, so each hides them in toString(): request and
 * response bodies reach debug logs through that method.
 */
final class VendorDtos {

    static final int NAME_LENGTH = 120;
    static final int PHONE_LENGTH = 40;
    static final int EMAIL_LENGTH = 254;
    static final int WEBSITE_LENGTH = 2000;
    static final int ADDRESS_LENGTH = 500;
    static final int NOTES_LENGTH = 10_000;
    /** The same limit as for every amount: ten thousand million of the currency's main unit. */
    static final long MOST_MONEY = 1_000_000_000_000L;

    private static final String NAME_NEEDED = "Enter a name.";
    private static final String NAME_TOO_LONG = "Use at most 120 characters.";
    private static final String PHONE_TOO_LONG = "Use at most 40 characters.";
    private static final String EMAIL_TOO_LONG = "That address is too long to keep.";
    private static final String WEBSITE_TOO_LONG = "That address is too long to keep.";
    private static final String ADDRESS_TOO_LONG = "Use at most 500 characters.";
    private static final String NOTES_TOO_LONG = "Use at most 10,000 characters.";
    private static final String PRICE_NOT_BELOW_ZERO = "Enter an amount, or zero.";
    private static final String PRICE_TOO_LARGE = "That amount is too large.";
    private static final String STATUS_NEEDED = "Say whether you are considering this vendor, have booked it, or dropped it.";
    private static final String PRICE_DESCRIPTION =
            "What was agreed, in the smallest unit of the wedding's currency; null while nothing is agreed.";

    private VendorDtos() {}

    /** The fields a person fills in; the same for a new vendor and for a change. */
    interface VendorFields {
        String name();

        UUID categoryId();

        Status status();

        String contactPerson();

        String phone();

        String email();

        String website();

        String address();

        Long agreedPrice();

        String notes();
    }

    @Schema(name = "NewVendor", description = "A vendor to add to the register.")
    record NewVendor(
            @NotBlank(message = NAME_NEEDED) @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String name,
            @Schema(description = "One of the plan's categories.", nullable = true) UUID categoryId,
            @NotNull(message = STATUS_NEEDED) Status status,
            @Schema(description = "May be empty.") @NotNull @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String contactPerson,
            @Schema(description = "As the person writes it; may be empty.") @NotNull @Size(max = PHONE_LENGTH, message = PHONE_TOO_LONG)
                    String phone,
            @Schema(description = "May be empty.") @NotNull @Size(max = EMAIL_LENGTH, message = EMAIL_TOO_LONG) String email,
            @Schema(description = "A web address; https:// is put in front of one that has no scheme. May be empty.")
                    @NotNull
                    @Size(max = WEBSITE_LENGTH, message = WEBSITE_TOO_LONG)
                    String website,
            @Schema(description = "May be empty.") @NotNull @Size(max = ADDRESS_LENGTH, message = ADDRESS_TOO_LONG) String address,
            @Schema(description = PRICE_DESCRIPTION, nullable = true)
                    @PositiveOrZero(message = PRICE_NOT_BELOW_ZERO)
                    @Max(value = MOST_MONEY, message = PRICE_TOO_LARGE)
                    Long agreedPrice,
            @Schema(description = "Free text; may be empty.") @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes)
            implements VendorFields {

        @Override
        public String toString() {
            return "NewVendor[status=" + status + "]";
        }
    }

    @Schema(name = "VendorChange", description = "All fields of a vendor as they should be from now on.")
    record VendorChange(
            @Schema(description = "The version of the vendor this change is based on.") @NotNull Integer version,
            @NotBlank(message = NAME_NEEDED) @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String name,
            @Schema(nullable = true) UUID categoryId,
            @NotNull(message = STATUS_NEEDED) Status status,
            @NotNull @Size(max = NAME_LENGTH, message = NAME_TOO_LONG) String contactPerson,
            @NotNull @Size(max = PHONE_LENGTH, message = PHONE_TOO_LONG) String phone,
            @NotNull @Size(max = EMAIL_LENGTH, message = EMAIL_TOO_LONG) String email,
            @NotNull @Size(max = WEBSITE_LENGTH, message = WEBSITE_TOO_LONG) String website,
            @NotNull @Size(max = ADDRESS_LENGTH, message = ADDRESS_TOO_LONG) String address,
            @Schema(description = PRICE_DESCRIPTION, nullable = true)
                    @PositiveOrZero(message = PRICE_NOT_BELOW_ZERO)
                    @Max(value = MOST_MONEY, message = PRICE_TOO_LARGE)
                    Long agreedPrice,
            @NotNull @Size(max = NOTES_LENGTH, message = NOTES_TOO_LONG) String notes)
            implements VendorFields {

        @Override
        public String toString() {
            return "VendorChange[version=" + version + ", status=" + status + "]";
        }
    }

    @Schema(
            name = "Vendor",
            description = "Someone the wedding is made with: how to reach them, what was agreed, and what hangs on them. "
                    + "Its plan items and tasks are GET /api/entries?vendorId=…, its inspirations GET /api/inspirations?vendorId=….")
    record VendorResponse(
            UUID id,
            String name,
            @Schema(description = "One of the plan's categories.", nullable = true) UUID categoryId,
            Status status,
            @Schema(description = "May be empty.") String contactPerson,
            @Schema(description = "May be empty.") String phone,
            @Schema(description = "May be empty.") String email,
            @Schema(description = "A web address starting with http:// or https://, or empty.") String website,
            @Schema(description = "May be empty.") String address,
            @Schema(description = PRICE_DESCRIPTION, nullable = true) Long agreedPrice,
            String notes,
            @Schema(description = "How many plan items point to this vendor.") int planItems,
            @Schema(description = "How many tasks point to it, payments among them.") int tasks,
            @Schema(description = "How many of those tasks are payments that are not dropped.") int payments,
            @Schema(description = "What those payments come to, in the smallest unit of the wedding's currency.") long committed,
            @Schema(description = "The part of it that is paid.") long paid,
            @Schema(description = "How many inspirations point to this vendor.") int inspirations,
            @Schema(description = "Send this back with a change; it tells whether someone else saved in between.") int version,
            Instant createdAt,
            Instant updatedAt) {

        static VendorResponse of(VendorRow row, Use use) {
            return new VendorResponse(
                    row.id(), row.name(), row.categoryId(), row.status(), row.contactPerson(), row.phone(), row.email(),
                    row.website(), row.address(), row.agreedPrice(), row.notes(), use.planItems(), use.tasks(), use.payments(),
                    use.committed(), use.paid(), use.inspirations(), row.version(), row.createdAt(), row.updatedAt());
        }

        @Override
        public String toString() {
            return "Vendor[id=" + id + ", status=" + status + ", version=" + version + "]";
        }
    }
}
