package io.github.trufelek124.weddingplanner.suggestions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the import read out of one message and proposes. Everything that may be missing is null.
 *
 * @param sourceKey what the import calls this proposal; unique within the wedding
 * @param title what is proposed; for a vendor, its name
 * @param startDate a plan item's first day
 * @param dueDate the day it is due or ends
 * @param amount for a payment: in the smallest unit of the currency
 * @param categoryId a category the planner has
 * @param vendorId a vendor the planner has, found by the sender's address
 * @param vendorName a vendor's name where none matched
 * @param inboxMemberId whose inbox the message was in: a member of the wedding
 * @param mailLink opens the message in Gmail: an https address at mail.google.com, made by the server
 * @param passage a short quotation of the message the proposal rests on, never the whole message
 * @param confidence the import's confidence as a whole percentage, 0 to 100
 */
public record Proposal(
        UUID weddingId,
        String sourceKey,
        Kind kind,
        String title,
        String notes,
        LocalDate startDate,
        LocalDate dueDate,
        Long amount,
        PaymentKind paymentKind,
        UUID categoryId,
        UUID vendorId,
        String vendorName,
        String vendorEmail,
        String vendorPhone,
        String vendorWebsite,
        String senderName,
        String senderAddress,
        String subject,
        Instant receivedAt,
        UUID inboxMemberId,
        String mailLink,
        String passage,
        int confidence) {

    public enum Kind {
        TASK,
        PAYMENT,
        PLAN_ITEM,
        VENDOR
    }

    public enum PaymentKind {
        DEPOSIT,
        INSTALMENT,
        FINAL
    }

    /** A proposal holds parts of someone's mail: nothing of it is printed. */
    @Override
    public String toString() {
        return "Proposal[kind=" + kind + "]";
    }
}
