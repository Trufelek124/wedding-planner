package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionAcceptance;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionResponse;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Which;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** There is deliberately no call here that adds a suggestion: the import hands them in inside the server. */
@RestController
@RequestMapping(path = "/api/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
class SuggestionController {

    private final SuggestionService suggestions;

    SuggestionController(SuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    /** The newest message first. */
    @GetMapping
    List<SuggestionResponse> list(
            UserId user,
            @Parameter(description = "Which suggestions: the ones that wait (the default), the accepted or the rejected ones, or ALL.")
                    @RequestParam(defaultValue = "WAITING")
                    Which state) {
        return suggestions.list(user, state).stream().map(SuggestionResponse::of).toList();
    }

    @Operation(
            summary = "Accepts a suggestion",
            description = "Makes what it proposes (or what the body says) and answers with the suggestion, which now names "
                    + "what was made. Accepting again answers the same and makes nothing more. A suggestion that was "
                    + "rejected answers already-decided. What would be made is checked like adding by hand: if it is "
                    + "not valid the answer lists the fields, nothing is made and the suggestion still waits.")
    @PostMapping("/{id}/accept")
    SuggestionResponse accept(UserId user, @PathVariable UUID id, @RequestBody(required = false) SuggestionAcceptance values) {
        return SuggestionResponse.of(suggestions.accept(user, id, values));
    }

    @Operation(
            summary = "Rejects a suggestion",
            description = "Makes nothing. Rejecting again answers the same; a suggestion that was accepted answers already-decided.")
    @PostMapping("/{id}/reject")
    SuggestionResponse reject(UserId user, @PathVariable UUID id) {
        return SuggestionResponse.of(suggestions.reject(user, id));
    }
}
