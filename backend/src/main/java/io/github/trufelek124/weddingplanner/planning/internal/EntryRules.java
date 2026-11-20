package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.EntryFields;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules of an entry that need no look at the database, with the words a person reads: the one place that holds
 * them, for the form ({@link EntryService}) and for a file of tasks ({@link TaskFileService}). The problems name
 * the fields as the API spells them.
 */
final class EntryRules {

    private EntryRules() {}

    /** What the dates and the chosen priority must be like. */
    static List<FieldProblem> ofTheDates(Kind kind, EntryFields request) {
        List<FieldProblem> problems = new ArrayList<>();
        if (kind == Kind.TASK && request.startDate() != null) {
            problems.add(new FieldProblem("startDate", "A task has only a due date. Leave the start empty."));
        }
        if (request.startDate() != null && request.dueDate() == null) {
            problems.add(new FieldProblem("dueDate", "Enter the end date too, or leave both dates empty."));
        }
        if (request.startDate() != null && request.dueDate() != null && request.startDate().isAfter(request.dueDate())) {
            problems.add(new FieldProblem("dueDate", "The end date cannot be before the start date."));
        }
        if (request.priorityMode() == PriorityMode.MANUAL && request.priority() == null) {
            problems.add(new FieldProblem("priority", "Choose a priority, or let the planner set it."));
        }
        return problems;
    }

    /** What an amount, a kind of payment and a paid date need of each other and of the status. */
    static List<FieldProblem> ofTheMoney(Kind kind, EntryFields request, LocalDate today) {
        List<FieldProblem> problems = new ArrayList<>();
        if (request.amount() != null && kind == Kind.PLAN_ITEM) {
            problems.add(new FieldProblem("amount", "Only a task can carry an amount. Leave it empty here."));
        }
        if (request.amount() == null && request.paymentKind() != null) {
            problems.add(new FieldProblem("paymentKind", "The kind of payment needs an amount. Enter one, or leave the kind empty."));
        }
        if (request.paidOn() != null) {
            if (request.amount() == null) {
                problems.add(new FieldProblem("paidOn", "Only a payment has a paid date. Enter an amount, or leave the date empty."));
            } else if (request.status() != Status.DONE) {
                problems.add(new FieldProblem("paidOn", "A payment has a paid date once it is done. Mark it done, or leave the date empty."));
            } else if (request.paidOn().isAfter(today)) {
                problems.add(new FieldProblem("paidOn", "The paid date cannot be in the future."));
            }
        }
        return problems;
    }

    /** The current date where the wedding takes place. */
    static LocalDate today(Clock clock, Membership membership) {
        return LocalDate.now(clock.withZone(membership.timeZone()));
    }

    /**
     * A payment is paid when it is done. The day is the one the person gives; otherwise the one
     * already recorded, and for a payment that is only now done, today.
     */
    static LocalDate paidOn(Long amount, Status status, LocalDate given, EntryRow before, LocalDate today) {
        if (amount == null || status != Status.DONE) {
            return null;
        }
        if (given != null) {
            return given;
        }
        return before != null && before.paidOn() != null ? before.paidOn() : today;
    }

    /** "Done" remembers when it was done; a change that leaves it done keeps that moment. */
    static Instant completedAt(Status status, EntryRow before, Instant now) {
        if (status != Status.DONE) {
            return null;
        }
        return before != null && before.completedAt() != null ? before.completedAt() : now;
    }
}
