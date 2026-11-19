package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.swagger.v3.oas.annotations.media.Schema;
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
            @Schema(description = "The member whose request decided it; null while it waits.", nullable = true) UUID decidedBy,
            @Schema(nullable = true) Instant decidedAt,
            @Schema(description = "The task, payment or plan item that accepting made.", nullable = true) UUID madeEntryId,
            @Schema(description = "The vendor that accepting made.", nullable = true) UUID madeVendorId,
            Instant createdAt) {

        static SuggestionResponse of(SuggestionRow row) {
            return new SuggestionResponse(
                    row.id(), row.kind(), row.state(), row.title(), row.notes(), row.startDate(), row.dueDate(), row.amount(),
                    row.paymentKind(), row.categoryId(), row.vendorId(), row.vendorName(), row.vendorEmail(), row.vendorPhone(),
                    row.vendorWebsite(), row.senderName(), row.senderAddress(), row.subject(), row.receivedAt(),
                    row.inboxMemberId(), row.mailLink(), row.passage(), row.confidence(), row.decidedBy(), row.decidedAt(),
                    row.madeEntryId(), row.madeVendorId(), row.createdAt());
        }

        @Override
        public String toString() {
            return "Suggestion[id=" + id + ", kind=" + kind + ", state=" + state + "]";
        }
    }
}
