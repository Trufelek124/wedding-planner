package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.BoardRepository.BoardRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.InspirationRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.TagCount;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.Source;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureRepository.PictureRow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the inspirations endpoints take and give. The messages are shown to people next to the field. */
final class InspirationDtos {

    static final int TITLE_LENGTH = 200;
    static final int NOTE_LENGTH = 10_000;
    static final int TAG_LENGTH = 40;
    static final int TAGS = 20;
    static final int BOARD_NAME_LENGTH = 60;
    private static final String LINK_TOO_LONG = "That link is too long to keep.";
    private static final String TITLE_TOO_LONG = "Use at most 200 characters.";
    private static final String NOTE_TOO_LONG = "Use at most 10,000 characters.";
    private static final String TOO_MANY_TAGS = "Use at most 20 tags.";
    private static final String TAG_EMPTY = "A tag cannot be empty.";
    private static final String TAG_TOO_LONG = "Use at most 40 characters for a tag.";

    private InspirationDtos() {}

    /** The fields a person fills in; the same for a new inspiration and for a change. */
    interface InspirationFields {
        String url();

        UUID boardId();

        String title();

        String note();

        List<String> tags();

        UUID planItemId();
    }

    @Schema(name = "NewInspiration", description = "An idea to keep: a link, or the start of one that pictures are added to.")
    record NewInspiration(
            @Schema(description = "The page the idea comes from, starting with http:// or https://; null for pictures of one's own.", nullable = true)
                    @Size(max = Links.MAX_LENGTH, message = LINK_TOO_LONG)
                    String url,
            @Schema(description = "The board it goes on; null for none yet.", nullable = true) UUID boardId,
            @Schema(description = "May be empty.") @NotNull @Size(max = TITLE_LENGTH, message = TITLE_TOO_LONG) String title,
            @Schema(description = "Free text; may be empty.") @NotNull @Size(max = NOTE_LENGTH, message = NOTE_TOO_LONG) String note,
            @Schema(description = "Kept in lower case, without a leading #.") @NotNull @Size(max = TAGS, message = TOO_MANY_TAGS)
                    List<@NotBlank(message = TAG_EMPTY) @Size(max = TAG_LENGTH, message = TAG_TOO_LONG) String> tags,
            @Schema(description = "The plan item the idea belongs to.", nullable = true) UUID planItemId)
            implements InspirationFields {}

    @Schema(name = "InspirationChange", description = "All fields of an inspiration as they should be from now on.")
    record InspirationChange(
            @Schema(description = "The version of the inspiration this change is based on.") @NotNull Integer version,
            @Schema(nullable = true) @Size(max = Links.MAX_LENGTH, message = LINK_TOO_LONG) String url,
            @Schema(nullable = true) UUID boardId,
            @NotNull @Size(max = TITLE_LENGTH, message = TITLE_TOO_LONG) String title,
            @NotNull @Size(max = NOTE_LENGTH, message = NOTE_TOO_LONG) String note,
            @NotNull @Size(max = TAGS, message = TOO_MANY_TAGS)
                    List<@NotBlank(message = TAG_EMPTY) @Size(max = TAG_LENGTH, message = TAG_TOO_LONG) String> tags,
            @Schema(nullable = true) UUID planItemId)
            implements InspirationFields {}

    @Schema(name = "Inspiration", description = "An idea the couple kept: a link or pictures, on a board, with a note and tags.")
    record InspirationResponse(
            UUID id,
            @Schema(description = "The page the idea comes from; null for pictures of one's own.", nullable = true) String url,
            @Schema(description = "Where it comes from, worked out from the link; PHOTO when there is no link.") Source source,
            @Schema(description = "May be empty.") String title,
            String note,
            @Schema(description = "In lower case, in alphabetical order.") List<String> tags,
            @Schema(nullable = true) UUID boardId,
            @Schema(description = "The plan item the idea belongs to.", nullable = true) UUID planItemId,
            @Schema(description = "The member who saved it.", nullable = true) UUID addedById,
            @Schema(description = "Its pictures, in their order; the first is the one a card shows.") List<PictureResponse> pictures,
            @Schema(description = "Send this back with a change; it tells whether someone else saved in between.") int version,
            Instant createdAt,
            Instant updatedAt) {

        static InspirationResponse of(InspirationView view) {
            InspirationRow row = view.inspiration();
            return new InspirationResponse(
                    row.id(), row.url(), row.source(), row.title(), row.note(), row.tags(), row.boardId(), row.planItemId(),
                    row.addedBy(), view.pictures().stream().map(PictureResponse::of).toList(), row.version(),
                    row.createdAt(), row.updatedAt());
        }
    }

    @Schema(
            name = "Picture",
            description = "A picture of an inspiration. Its bytes are at /api/inspirations/{inspirationId}/pictures/{id}.")
    record PictureResponse(
            UUID id,
            @Schema(description = "image/jpeg, image/png, image/gif or image/webp, worked out from the file itself.") String contentType,
            @Schema(description = "In pixels, the right way up; null for a kind the server cannot read.", nullable = true) Integer width,
            @Schema(nullable = true) Integer height) {

        static PictureResponse of(PictureRow row) {
            return new PictureResponse(row.id(), row.format().contentType(), row.width(), row.height());
        }
    }

    @Schema(name = "BoardName")
    record BoardName(
            @NotBlank(message = "Enter a name.") @Size(max = BOARD_NAME_LENGTH, message = "Use at most 60 characters.") String name) {}

    @Schema(name = "Board", description = "A heading inspirations are collected under, for example Flowers.")
    record BoardResponse(UUID id, String name, @Schema(description = "How many inspirations are on it.") int inspirationCount) {

        static BoardResponse of(BoardRow row) {
            return new BoardResponse(row.id(), row.name(), row.inspirationCount());
        }
    }

    @Schema(name = "TagCount", description = "A tag in use and how many inspirations carry it.")
    record TagResponse(String tag, int count) {

        static TagResponse of(TagCount row) {
            return new TagResponse(row.tag(), row.count());
        }
    }
}
