package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.BudgetRepository.BudgetRow;
import io.github.trufelek124.weddingplanner.planning.internal.BudgetRepository.Spent;
import io.github.trufelek124.weddingplanner.planning.internal.CategoryRepository.CategoryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.BudgetChange;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.PlannedAmount;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The budget: what the couple means to spend, in all and per category, against what their payments
 * come to. A payment is a task that carries an amount, filed under the task's own category, so
 * nothing is entered twice. Every amount is a whole number of the currency's smallest unit.
 */
@Service
class BudgetService {

    /**
     * One category of the budget.
     *
     * @param planned what the couple means to spend on it; null while they have not said
     */
    record Line(UUID categoryId, String name, Long planned, long committed, long paid, int payments) {}

    /** The payments that are filed under no category. */
    record Rest(long committed, long paid, int payments) {}

    /**
     * @param total what the couple means to spend on everything; null while they have not said
     * @param planned the planned amounts of all categories together
     * @param committed every payment that is not dropped
     * @param paid those of them that are done
     */
    record Summary(Long total, long planned, long committed, long paid, List<Line> categories, Rest rest, int version) {}

    private final BudgetRepository budget;
    private final CategoryRepository categories;
    private final Weddings weddings;
    private final Clock clock;

    BudgetService(BudgetRepository budget, CategoryRepository categories, Weddings weddings, Clock clock) {
        this.budget = budget;
        this.categories = categories;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    Summary summary(UserId user) {
        return summary(weddings.membershipOf(user).weddingId());
    }

    /** Replaces the total and the planned amounts together, if nobody else saved them in between. */
    @Transactional
    Summary change(UserId user, BudgetChange request) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        Set<UUID> known = categories.list(weddingId).stream().map(CategoryRow::id).collect(Collectors.toSet());
        Map<UUID, Long> planned = new LinkedHashMap<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int at = 0; at < request.planned().size(); at++) {
            PlannedAmount one = request.planned().get(at);
            if (!known.contains(one.categoryId())) {
                problems.add(new FieldProblem("planned[" + at + "].categoryId", "Choose one of the categories."));
            } else if (planned.put(one.categoryId(), one.amount()) != null) {
                problems.add(new FieldProblem("planned[" + at + "].categoryId", "This category is named twice."));
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }
        if (!budget.save(weddingId, request.total(), request.version(), clock.instant())) {
            throw new ApiException(
                    ProblemCode.CHANGED_MEANWHILE, "Someone else changed this in the meantime. Load it again, then make your change.");
        }
        budget.replacePlanned(weddingId, planned);
        return summary(weddingId);
    }

    private Summary summary(UUID weddingId) {
        BudgetRow saved = budget.find(weddingId).orElse(new BudgetRow(null, 0));
        Map<UUID, Long> planned = budget.planned(weddingId);
        Map<UUID, Spent> spent = new LinkedHashMap<>();
        Rest rest = new Rest(0, 0, 0);
        for (Spent one : budget.spent(weddingId)) {
            if (one.categoryId() == null) {
                rest = new Rest(one.committed(), one.paid(), one.payments());
            } else {
                spent.put(one.categoryId(), one);
            }
        }
        List<Line> lines = new ArrayList<>();
        long plannedInAll = 0;
        long committed = rest.committed();
        long paid = rest.paid();
        for (CategoryRow category : categories.list(weddingId)) {
            Spent its = spent.getOrDefault(category.id(), new Spent(category.id(), 0, 0, 0));
            Long itsPlan = planned.get(category.id());
            lines.add(new Line(category.id(), category.name(), itsPlan, its.committed(), its.paid(), its.payments()));
            // Each amount is at most MOST_MONEY, so these sums stay far inside what a long holds.
            plannedInAll = Math.addExact(plannedInAll, itsPlan == null ? 0 : itsPlan);
            committed = Math.addExact(committed, its.committed());
            paid = Math.addExact(paid, its.paid());
        }
        return new Summary(saved.total(), plannedInAll, committed, paid, lines, rest, saved.version());
    }
}
