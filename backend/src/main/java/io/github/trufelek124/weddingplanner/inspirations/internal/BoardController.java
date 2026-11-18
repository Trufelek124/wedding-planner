package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.BoardName;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.BoardResponse;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.InspirationsOnDelete;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/boards", produces = MediaType.APPLICATION_JSON_VALUE)
class BoardController {

    private final BoardService boards;

    BoardController(BoardService boards) {
        this.boards = boards;
    }

    /** The first call for a wedding fills the list with a starter set. */
    @GetMapping
    List<BoardResponse> list(UserId user) {
        return boards.list(user).stream().map(BoardResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    BoardResponse add(UserId user, @Valid @RequestBody BoardName request) {
        return BoardResponse.of(boards.add(user, request.name()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    BoardResponse rename(UserId user, @PathVariable UUID id, @Valid @RequestBody BoardName request) {
        return BoardResponse.of(boards.rename(user, id, request.name()));
    }

    /** Answers the board-has-inspirations problem while the board has some and the request does not say what happens to them. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            UserId user,
            @PathVariable UUID id,
            @Parameter(description = "For a board with inspirations: keep them without a board, or delete them too.")
                    @RequestParam(required = false)
                    InspirationsOnDelete inspirations) {
        boards.delete(user, id, inspirations);
    }
}
