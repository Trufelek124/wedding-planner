package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the suggestion endpoints take and give. Every record here holds parts of someone's mail, so each hides them
 * in toString(): request and response bodies reach debug logs through that method.
 */
final class SuggestionDtos {

    private SuggestionDtos() {}

    @Schema(
            name = "SuggestionAcceptance",
            description = "The proposal's values as the person wants them, when they differ from what was proposed. "
                    + "All of them are given: a value that is left out (null) is made without, not kept from the proposal. "
                    + "The kind cannot be changed. They are checked as the entry's or the vendor's own form is, and "
                    + "answered with the same messages next to the same fields (a vendor's name is `title`).")
    record SuggestionAcceptance(
            @Schema(description = "What is made: for a vendor, its name.") String title,
            @Schema(nullable = true) String notes,
            @Schema(description = "A plan item's first day.", nullable = true) LocalDate startDate,
            @Schema(description = "The day it is due, or a plan item ends.", nullable = true) LocalDate dueDate,
            @Schema(description = "In the smallest unit of the currency; a payment needs one.", nullable = true) Long amount,
            @Schema(nullable = true) PaymentKind paymentKind,
            @Schema(nullable = true) UUID categoryId,
            @Schema(description = "A vendor the entry concerns.", nullable = true) UUID vendorId,
            @Schema(description = "A vendor's name where none matched; nothing is made of it.", nullable = true) String vendorName,
            @Schema(description = "A vendor's e-mail address.", nullable = true) String vendorEmail,
            @Schema(description = "A vendor's phone number.", nullable = true) String vendorPhone,
            @Schema(description = "A vendor's website.", nullable = true) String vendorWebsite) {

        @Override
        public String toString() {
            return "SuggestionAcceptance[]";
        }
    }

    @Schema(
            name = "Suggestion",
            description = "What the e-mail import proposes, where it comes from, and what became of it. "
                    + "Its texts were written by whoever sent the mail: show them as plain text.")
    record SuggestionResponse(
            UUID id,
            @Schema(description = "What accepting makes: a task, a task with an amount, a step of the plan or a vendor.") Kind kind,
            @Schema(
                            description = "WAITING for a person; ADDED when the import was sure enough and the task, step or payment "
                                    + "(from a document) is already in the plan (nobody has decided it: keep or undo it; after thirty days it is "
                                    + "answered as ACCEPTED, with nobody as decidedBy); ACCEPTED or REJECTED once decided.")
                    State state,
            @Schema(description = "What is proposed; for a vendor, its name.") String title,
            String notes,
            @Schema(description = "A plan item's first day.", nullable = true) LocalDate startDate,
            @Schema(nullable = true) LocalDate dueDate,
            @Schema(description = "In the smallest unit of the currency.", nullable = true) Long amount,
            @Schema(nullable = true) PaymentKind paymentKind,
            @Schema(nullable = true) UUID categoryId,
            @Schema(description = "A vendor the planner has, found by the sender's address.", nullable = true) UUID vendorId,
            @Schema(description = "A vendor's name where none matched.", nullable = true) String vendorName,
            @Schema(nullable = true) String vendorEmail,
            @Schema(nullable = true) String vendorPhone,
            @Schema(nullable = true) String vendorWebsite,
            @Schema(description = "May be empty.") String senderName,
            @Schema(description = "May be empty.") String senderAddress,
            @Schema(description = "May be empty.") String subject,
            @Schema(description = "When the message arrived.") Instant receivedAt,
            @Schema(description = "The member whose inbox it was in.") UUID inboxMemberId,
            @Schema(description = "Opens the message in Gmail: always an https address at mail.google.com.") String mailLink,
            @Schema(description = "The quotation the proposal rests on.") String passage,
            @Schema(description = "The import's confidence as a whole percentage, 0 to 100.") int confidence,
            @Schema(
                            description = "The member whose request decided it; null while it waits, while it is ADDED, and "
                                    + "when ACCEPTED because thirty days passed: nobody decided it.",
                            nullable = true)
                    UUID decidedBy,
            @Schema(
                            description = "When it was decided; for one that counts as kept after thirty days, the moment "
                                    + "that came to be. null while it waits or is ADDED.",
                            nullable = true)
                    Instant decidedAt,
            @Schema(
                            description = "The task, payment or plan item that accepting made, or that was added by itself; "
                                    + "null once it was undone.",
                            nullable = true)
                    UUID madeEntryId,
            @Schema(description = "The vendor that accepting made.", nullable = true) UUID madeVendorId,
            Instant createdAt,
            @Schema(
                            description = "Whether the passage stands in a document (a PDF) that came with the e-mail: then the words "
                                    + "are not in the mail that `mailLink` opens. Found by the server, never said by the model.")
                    boolean fromDocument) {

        static SuggestionResponse of(SuggestionRow row) {
            return new SuggestionResponse(
                    row.id(), row.kind(), row.state(), row.title(), row.notes(), row.startDate(), row.dueDate(), row.amount(),
                    row.paymentKind(), row.categoryId(), row.vendorId(), row.vendorName(), row.vendorEmail(), row.vendorPhone(),
                    row.vendorWebsite(), row.senderName(), row.senderAddress(), row.subject(), row.receivedAt(),
                    row.inboxMemberId(), row.mailLink(), row.passage(), row.confidence(), row.decidedBy(), row.decidedAt(),
                    row.madeEntryId(), row.madeVendorId(), row.createdAt(), row.fromDocument());
        }

        @Override
        public String toString() {
            return "Suggestion[id=" + id + ", kind=" + kind + ", state=" + state + "]";
        }
    }

    @Schema(
            name = "SuggestionSettings",
            description = "How sure the e-mail import has to be before it adds a task, a step of the plan or a payment that a "
                    + "document names by itself. One setting for the wedding: both owners see and change the same. Payments that "
                    + "only a mail's own words name, and vendors, always wait.")
    record SettingsResponse(
            @Schema(description = "False: nothing is added by itself, everything waits. The threshold is kept for when it is "
                            + "switched on again.")
                    boolean addsByItself,
            @Schema(description = "From 50 to 100: the least confidence, as a percentage, at which it adds by itself.", minimum = "50", maximum = "100")
                    int threshold) {

        static SettingsResponse of(SuggestionSettingsRepository.Settings settings) {
            return new SettingsResponse(settings.addsByItself(), settings.threshold());
        }
    }

    @Schema(
            name = "SuggestionSettingsChange",
            description = "The settings as they should be from now on. Applies to what is handed in from then on: nothing "
                    + "that waits is added and nothing that was added is undone.")
    record SettingsChange(
            @NotNull(message = "Say whether tasks, steps and payments from documents are added by themselves.") Boolean addsByItself,
            @Schema(minimum = "50", maximum = "100")
                    @NotNull(message = THRESHOLD_MESSAGE)
                    @Min(value = 50, message = THRESHOLD_MESSAGE)
                    @Max(value = 100, message = THRESHOLD_MESSAGE)
                    Integer threshold) {

        SuggestionSettingsRepository.Settings asSettings() {
            return new SuggestionSettingsRepository.Settings(addsByItself, threshold);
        }
    }

    static final String THRESHOLD_MESSAGE = "Choose a number from 50 to 100.";
}
