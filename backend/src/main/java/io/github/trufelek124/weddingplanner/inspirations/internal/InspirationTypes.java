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

    /** How far the server got with fetching a title and a picture for a link. */
    @Schema(name = "PreviewStatus")
    enum PreviewStatus {
        /** There is no link, or the link gave nothing. */
        NONE,
        /** Not tried yet, or to be tried again: ask again in a moment. */
        PENDING,
        /** A title, a picture or both were found. */
        FETCHED
    }

    /** What happens to the inspirations of a board that is removed. */
    @Schema(name = "InspirationsOnDelete")
    enum InspirationsOnDelete {
        /** They stay, without a board. */
        KEEP,
        DELETE
    }
}
