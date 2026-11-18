package io.github.trufelek124.weddingplanner.vendors.internal;

/** The small fixed vocabularies of the vendor register, as the API spells them. */
final class VendorTypes {

    private VendorTypes() {}

    enum Status {
        /** One of several the couple is looking at. */
        CONSIDERING,
        /** Agreed with. */
        BOOKED,
        /** Not the one after all. */
        DROPPED
    }

    /** What happens to the plan items, tasks and inspirations that point to a vendor that is deleted. */
    enum LinksOnDelete {
        /** They stay, and no longer point to a vendor. */
        REMOVE
    }
}
