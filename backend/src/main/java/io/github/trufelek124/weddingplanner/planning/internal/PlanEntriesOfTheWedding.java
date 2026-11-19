package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.BeanRules;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.planning.PlanEntries;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Adding an entry for another module: the request a person's form would send, held to the same rules. */
@Component
class PlanEntriesOfTheWedding implements PlanEntries {

    private final EntryService entries;
    private final Validator validator;

    PlanEntriesOfTheWedding(EntryService entries, Validator validator) {
        this.entries = entries;
        this.validator = validator;
    }

    @Override
    @Transactional
    public UUID addEntry(UserId user, EntryToAdd entry) {
        NewEntry request = new NewEntry(
                null,
                Kind.valueOf(entry.kind().name()),
                entry.title(),
                entry.notes() == null ? "" : entry.notes(),
                entry.categoryId(),
                entry.startDate(),
                entry.dueDate(),
                Status.NOT_STARTED,
                List.of(),
                PriorityMode.AUTO,
                null,
                null,
                entry.vendorId(),
                entry.amount(),
                entry.paymentKind() == null ? null : PaymentKind.valueOf(entry.paymentKind().name()),
                null);
        // The same declared rules the endpoint holds a request to (the title, the lengths, the amount) ...
        BeanRules.require(validator, request);
        // ... and the same code that adds an entry for the form.
        return entries.create(user, request).entry().id();
    }
}
