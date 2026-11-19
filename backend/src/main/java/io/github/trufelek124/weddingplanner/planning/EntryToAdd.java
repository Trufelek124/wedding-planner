package io.github.trufelek124.weddingplanner.planning;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An entry as another module proposes it. Every field is as the entry's own form has it, and is checked as that is.
 * What is left out is null.
 *
 * @param startDate a plan item's first day; a task has none
 * @param dueDate the day a task is due, or the day a plan item ends
 * @param amount with one, a task is a payment: in the smallest unit of the currency
 */
public record EntryToAdd(
        Kind kind,
        String title,
        String notes,
        UUID categoryId,
        LocalDate startDate,
        LocalDate dueDate,
        UUID vendorId,
        Long amount,
        PaymentKind paymentKind) {

    public enum Kind {
        PLAN_ITEM,
        TASK
    }

    public enum PaymentKind {
        DEPOSIT,
        INSTALMENT,
        FINAL
    }

    /** A title and notes may come out of someone's mail: only the kind is printed. */
    @Override
    public String toString() {
        return "EntryToAdd[kind=" + kind + "]";
    }
}
