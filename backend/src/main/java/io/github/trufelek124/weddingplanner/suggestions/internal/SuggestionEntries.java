package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionAcceptance;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;

/** What a suggestion makes in the plan: one way for a person's acceptance and for adding by itself. */
final class SuggestionEntries {

    private SuggestionEntries() {}

    /** The values as they were proposed. */
    static SuggestionAcceptance asProposed(SuggestionRow s) {
        return new SuggestionAcceptance(
                s.title(), s.notes(), s.startDate(), s.dueDate(), s.amount(),
                s.paymentKind() == null ? null : PaymentKind.valueOf(s.paymentKind().name()), s.categoryId(), s.vendorId(),
                s.vendorName(), s.vendorEmail(), s.vendorPhone(), s.vendorWebsite());
    }

    /** The entry that a suggestion of this kind makes with these values, and that remembers which suggestion made it. */
    static EntryToAdd entry(Kind kind, SuggestionAcceptance wanted, java.util.UUID suggestionId) {
        EntryToAdd.Kind entryKind = kind == Kind.PLAN_ITEM ? EntryToAdd.Kind.PLAN_ITEM : EntryToAdd.Kind.TASK;
        return new EntryToAdd(
                entryKind, wanted.title(), wanted.notes(), wanted.categoryId(), wanted.startDate(), wanted.dueDate(),
                wanted.vendorId(), wanted.amount(),
                wanted.paymentKind() == null ? null : EntryToAdd.PaymentKind.valueOf(wanted.paymentKind().name()),
                suggestionId);
    }
}
