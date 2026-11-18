package io.github.trufelek124.weddingplanner.guests.internal;

/** The small fixed vocabularies of the guest list, as the API spells them. */
final class GuestTypes {

    private GuestTypes() {}

    /** Where a guest stands; the order of the constants is the order counts are listed in. */
    enum Status {
        /** On the list, not asked yet. */
        NOT_INVITED,
        INVITED,
        CONFIRMED,
        DECLINED,
        MAYBE
    }
}
