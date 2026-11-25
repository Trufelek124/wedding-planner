package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.BoardRepository.BoardRow;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.InspirationsOnDelete;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The boards ideas are collected on. The couple edits the list; a starter list is offered once. */
@Service
class BoardService {

    static final List<String> STARTER =
            List.of("Dress", "Suit", "Hair and make-up", "Flowers", "Decor", "Venue", "Cake", "Invitations", "First dance");

    private static final String NAME_TAKEN = "There is a board of that name already.";

    private final BoardRepository boards;
    private final InspirationRepository inspirations;
    private final PictureRepository pictures;
    private final PictureService pictureFiles;
    private final Weddings weddings;
    private final Clock clock;

    BoardService(
            BoardRepository boards,
            InspirationRepository inspirations,
            PictureRepository pictures,
            PictureService pictureFiles,
            Weddings weddings,
            Clock clock) {
        this.boards = boards;
        this.inspirations = inspirations;
        this.pictures = pictures;
        this.pictureFiles = pictureFiles;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<BoardRow> list(UserId user) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        if (boards.markOffered(weddingId, clock.instant())) {
            STARTER.forEach(name -> boards.insert(weddingId, UUID.randomUUID(), name));
        }
        return boards.list(weddingId);
    }

    @Transactional
    BoardRow add(UserId user, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        UUID id = UUID.randomUUID();
        if (!boards.insert(weddingId, id, name.strip())) {
            throw ApiException.invalidField("name", NAME_TAKEN);
        }
        return one(weddingId, id);
    }

    @Transactional
    BoardRow rename(UserId user, UUID id, String name) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        int renamed = boards.rename(weddingId, id, name.strip());
        if (renamed < 0) {
            throw ApiException.invalidField("name", NAME_TAKEN);
        }
        if (renamed == 0) {
            throw noSuchBoard();
        }
        return one(weddingId, id);
    }

    /**
     * @param onDelete what happens to the inspirations on the board; while it has some and this is null,
     *     nothing is removed and the caller is asked to choose
     */
    @Transactional
    void delete(UserId user, UUID id, InspirationsOnDelete onDelete) {
        UUID weddingId = weddings.membershipOf(user).weddingId();
        BoardRow board = boards.list(weddingId).stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow(BoardService::noSuchBoard);
        if (board.inspirationCount() > 0) {
            if (onDelete == null) {
                throw new ApiException(
                        ProblemCode.BOARD_HAS_INSPIRATIONS,
                        "This board has inspirations. Say whether to keep them without a board or delete them too.");
            }
            if (onDelete == InspirationsOnDelete.DELETE) {
                List<UUID> theirPictures = pictures.idsOfBoard(weddingId, id);
                inspirations.deleteOfBoard(weddingId, id);
                pictureFiles.forgetOnceDeleted(theirPictures);
            }
        }
        boards.delete(weddingId, id);
    }

    private BoardRow one(UUID weddingId, UUID id) {
        return boards.list(weddingId).stream().filter(board -> board.id().equals(id)).findFirst().orElseThrow();
    }

    private static ApiException noSuchBoard() {
        return new ApiException(ProblemCode.NOT_FOUND, "There is no such board.");
    }
}
