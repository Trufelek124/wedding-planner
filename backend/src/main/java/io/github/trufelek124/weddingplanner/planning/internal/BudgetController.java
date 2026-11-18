package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.BudgetChange;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.BudgetResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The money: what is planned, what the payments come to, and what is paid. */
@RestController
@RequestMapping(path = "/api/budget", produces = MediaType.APPLICATION_JSON_VALUE)
class BudgetController {

    private final BudgetService budget;

    BudgetController(BudgetService budget) {
        this.budget = budget;
    }

    @GetMapping
    BudgetResponse get(UserId user) {
        return BudgetResponse.of(budget.summary(user));
    }

    /** Sets the total and the planned amount of every category, all at once. */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    BudgetResponse change(UserId user, @Valid @RequestBody BudgetChange request) {
        return BudgetResponse.of(budget.change(user, request));
    }
}
