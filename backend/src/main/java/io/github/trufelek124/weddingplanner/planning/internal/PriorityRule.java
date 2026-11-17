package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The automatic priority (S01 section 5). A fixed rule, so that the result is predictable and
 * every level comes with its reason:
 *
 * <pre>
 * CRITICAL  overdue, or due within 3 days, or a payment due within 7 days
 * HIGH      due within 14 days, or another entry due within 30 days depends on it
 * NORMAL    due within 60 days
 * LOW       later, or no date
 * </pre>
 *
 * A plan item that should have started and has not moves up one level. What is done or dropped
 * needs no attention and is LOW. "Due" is the due date of a task and the end date of a plan item.
 */
final class PriorityRule {

    static final int CRITICAL_DAYS = 3;
    static final int PAYMENT_DAYS = 7;
    static final int HIGH_DAYS = 14;
    static final int NEEDED_BY_ANOTHER_DAYS = 30;
    static final int NORMAL_DAYS = 60;

    /** Why an entry has its level. Clients turn the code into words. */
    @Schema(name = "PriorityReason")
    enum Reason {
        OVERDUE,
        DUE_WITHIN_3_DAYS,
        PAYMENT_DUE_WITHIN_7_DAYS,
        DUE_WITHIN_14_DAYS,
        /** Another entry that is due within 30 days cannot start before this one is finished. */
        NEEDED_BY_ANOTHER,
        DUE_WITHIN_60_DAYS,
        DUE_LATER,
        NO_DATE,
        /** A plan item whose start date has passed while nobody has begun: one level up. */
        SHOULD_HAVE_STARTED,
        /** Done or dropped. */
        FINISHED,
        /** A person chose the level. */
        SET_BY_HAND
    }

    /**
     * What the rule looks at.
     *
     * @param payment whether the entry is a payment
     * @param earliestDependentDue the earliest due date among the unfinished entries that depend on this one; null if none
     */
    record Facts(Kind kind, Status status, LocalDate startDate, LocalDate dueDate, boolean payment, LocalDate earliestDependentDue) {}

    record Assessment(Priority priority, Reason reason) {}

    private PriorityRule() {}

    /** @param today the current date where the wedding takes place */
    static Assessment assess(Facts facts, LocalDate today) {
        if (facts.status() == Status.DONE || facts.status() == Status.DROPPED) {
            return new Assessment(Priority.LOW, Reason.FINISHED);
        }
        Assessment byDates = byDates(facts, today);
        boolean shouldHaveStarted = facts.kind() == Kind.PLAN_ITEM
                && facts.status() == Status.NOT_STARTED
                && facts.startDate() != null
                && facts.startDate().isBefore(today);
        if (shouldHaveStarted && byDates.priority() != Priority.CRITICAL) {
            return new Assessment(oneLevelUp(byDates.priority()), Reason.SHOULD_HAVE_STARTED);
        }
        return byDates;
    }

    private static Assessment byDates(Facts facts, LocalDate today) {
        Long days = facts.dueDate() == null ? null : ChronoUnit.DAYS.between(today, facts.dueDate());
        if (days != null && days < 0) {
            return new Assessment(Priority.CRITICAL, Reason.OVERDUE);
        }
        if (days != null && days <= CRITICAL_DAYS) {
            return new Assessment(Priority.CRITICAL, Reason.DUE_WITHIN_3_DAYS);
        }
        if (days != null && facts.payment() && days <= PAYMENT_DAYS) {
            return new Assessment(Priority.CRITICAL, Reason.PAYMENT_DUE_WITHIN_7_DAYS);
        }
        if (days != null && days <= HIGH_DAYS) {
            return new Assessment(Priority.HIGH, Reason.DUE_WITHIN_14_DAYS);
        }
        if (facts.earliestDependentDue() != null
                && ChronoUnit.DAYS.between(today, facts.earliestDependentDue()) <= NEEDED_BY_ANOTHER_DAYS) {
            return new Assessment(Priority.HIGH, Reason.NEEDED_BY_ANOTHER);
        }
        if (days != null && days <= NORMAL_DAYS) {
            return new Assessment(Priority.NORMAL, Reason.DUE_WITHIN_60_DAYS);
        }
        return new Assessment(Priority.LOW, days == null ? Reason.NO_DATE : Reason.DUE_LATER);
    }

    private static Priority oneLevelUp(Priority priority) {
        return switch (priority) {
            case LOW -> Priority.NORMAL;
            case NORMAL -> Priority.HIGH;
            case HIGH, CRITICAL -> Priority.CRITICAL;
        };
    }
}
