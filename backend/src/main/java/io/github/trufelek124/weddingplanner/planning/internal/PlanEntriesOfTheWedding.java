package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.BeanRules;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.planning.PlanEntries;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Adding an entry for another module: the request a person's form would send, held to the same rules. */
@Component
class PlanEntriesOfTheWedding implements PlanEntries {

    private final EntryService entries;
    private final Validator validator;
    private final Weddings weddings;

    PlanEntriesOfTheWedding(EntryService entries, Validator validator, Weddings weddings) {
        this.entries = entries;
        this.weddings = weddings;
        this.validator = validator;
    }

    @Override
    @Transactional
    public UUID addEntry(UserId user, EntryToAdd entry) {
        return add(entry, request -> entries.create(weddings.membershipOf(user), request, entry.fromSuggestionId()));
    }

    @Override
    @Transactional
    public UUID addEntryFor(UUID weddingId, UUID memberId, EntryToAdd entry) {
        Membership membership = weddings.membershipOfMember(weddingId, memberId)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "The wedding has no such member."));
        return add(entry, request -> entries.create(membership, request, entry.fromSuggestionId()));
    }

    @Override
    @Transactional
    public Removal removeIfUntouched(UUID weddingId, UUID entryId, UUID suggestionId) {
        return entries.removeIfUntouched(weddingId, entryId, suggestionId);
    }

    @Override
    @Transactional
    public boolean hasPayment(UUID weddingId, long amount, java.time.LocalDate dueDate) {
        return entries.hasPayment(weddingId, amount, dueDate);
    }

    private UUID add(EntryToAdd entry, Function<NewEntry, EntryView> making) {
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
        return making.apply(request).entry().id();
    }
}
