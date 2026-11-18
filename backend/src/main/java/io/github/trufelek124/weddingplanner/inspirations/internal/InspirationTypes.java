package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** The small fixed lists of the inspirations module. */
final class InspirationTypes {

    private InspirationTypes() {}

    /** Where an inspiration comes from. Worked out from its link; PHOTO when it has none. */
    @Schema(name = "InspirationSource")
    enum Source {
        INSTAGRAM,
        TIKTOK,
        PINTEREST,
        WEB,
        PHOTO
    }

    /** What happens to the inspirations of a board that is removed. */
    @Schema(name = "InspirationsOnDelete")
    enum InspirationsOnDelete {
        /** They stay, without a board. */
        KEEP,
        DELETE
    }
}
