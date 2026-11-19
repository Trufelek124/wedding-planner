package io.github.trufelek124.weddingplanner.suggestions.internal;

/** The small fixed vocabularies of the suggestions, as the API spells them. */
final class SuggestionTypes {

    private SuggestionTypes() {}

    /** What accepting makes. */
    enum Kind {
        /** A task. */
        TASK,
        /** A task with an amount (D-004). */
        PAYMENT,
        /** A step of the plan. */
        PLAN_ITEM,
        /** A vendor, as one that is being considered. */
        VENDOR
    }

    enum State {
        WAITING,
        /** Made at once, because the import was sure enough (S05): nobody has decided it yet. */
        ADDED,
        ACCEPTED,
        REJECTED
    }

    /** Which suggestions a list shows. */
    enum Which {
        WAITING,
        /** Added by themselves, and neither kept nor undone nor older than thirty days. */
        ADDED,
        ACCEPTED,
        REJECTED,
        ALL
    }

    enum PaymentKind {
        DEPOSIT,
        INSTALMENT,
        FINAL
    }
}
