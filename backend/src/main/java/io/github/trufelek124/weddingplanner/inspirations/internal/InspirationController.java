package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.InspirationChange;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.InspirationResponse;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.NewInspiration;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.TagResponse;
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
@RequestMapping(path = "/api/inspirations", produces = MediaType.APPLICATION_JSON_VALUE)
class InspirationController {

    private final InspirationService inspirations;

    InspirationController(InspirationService inspirations) {
        this.inspirations = inspirations;
    }

    /** The newest first. */
    @GetMapping
    List<InspirationResponse> list(
            UserId user,
            @Parameter(description = "Only the inspirations on this board.") @RequestParam(required = false) UUID boardId,
            @Parameter(description = "true: only the inspirations that are on no board.") @RequestParam(required = false)
                    Boolean withoutBoard,
            @Parameter(description = "Only the inspirations that carry this tag.") @RequestParam(required = false) String tag,
            @Parameter(description = "Only the inspirations that belong to this plan item.") @RequestParam(required = false)
                    UUID planItemId,
            @Parameter(description = "Only the inspirations that concern this vendor.") @RequestParam(required = false) UUID vendorId) {
        return inspirations.list(user, boardId, withoutBoard, tag, planItemId, vendorId).stream()
                .map(InspirationResponse::of)
                .toList();
    }

    /** Every tag in use, in alphabetical order. */
    @GetMapping("/tags")
    List<TagResponse> tags(UserId user) {
        return inspirations.tags(user).stream().map(TagResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    InspirationResponse add(UserId user, @Valid @RequestBody NewInspiration request) {
        return InspirationResponse.of(inspirations.add(user, request));
    }

    @GetMapping("/{id}")
    InspirationResponse get(UserId user, @PathVariable UUID id) {
        return InspirationResponse.of(inspirations.get(user, id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    InspirationResponse change(UserId user, @PathVariable UUID id, @Valid @RequestBody InspirationChange request) {
        return InspirationResponse.of(inspirations.change(user, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(UserId user, @PathVariable UUID id) {
        inspirations.delete(user, id);
    }
}
