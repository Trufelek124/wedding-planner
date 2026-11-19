package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionAcceptance;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SettingsChange;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SettingsResponse;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionResponse;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionService.Undone;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Which;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
            @Parameter(description = "Which suggestions: the ones that wait (the default), the ones added by themselves that "
                            + "nobody has kept or undone and that are younger than thirty days (ADDED), the accepted or the "
                            + "rejected ones, or ALL. One that was added by itself and is older than thirty days counts as accepted.")
                    @RequestParam(defaultValue = "WAITING")
                    Which state) {
        return suggestions.list(user, state).stream().map(SuggestionResponse::of).toList();
    }

    @Operation(
            summary = "How sure the import has to be",
            description = "Whether the e-mail import adds tasks and steps of the plan by itself, and at what confidence. The "
                    + "same for both owners. Payments and vendors always wait.")
    @GetMapping("/settings")
    SettingsResponse settings(UserId user) {
        return SettingsResponse.of(suggestions.settings(user));
    }

    @Operation(
            summary = "Changes how sure the import has to be",
            description = "Applies to what is handed in from then on: nothing that waits is added and nothing that was added "
                    + "is undone. A threshold outside 50 to 100 is refused with a message next to the field.")
    @PutMapping("/settings")
    SettingsResponse change(UserId user, @Valid @RequestBody SettingsChange request) {
        return SettingsResponse.of(suggestions.change(user, request.asSettings()));
    }

    @Operation(
            summary = "One suggestion",
            description = "A suggestion of the caller's wedding, as the list gives it. It is where an entry's fromSuggestionId "
                    + "leads. Unknown: not-found.")
    @GetMapping("/{id}")
    SuggestionResponse get(UserId user, @PathVariable UUID id) {
        return SuggestionResponse.of(suggestions.get(user, id));
    }

    @Operation(
            summary = "Keeps what was added by itself",
            description = "Accepts a suggestion that was added by itself, in the caller's name; nothing else changes. Keeping "
                    + "again, or an accepted one, answers it as it is. One that was undone, rejected or is decided otherwise "
                    + "answers already-decided; one that still waits is a bad request.")
    @PostMapping("/{id}/keep")
    SuggestionResponse keep(UserId user, @PathVariable UUID id) {
        return SuggestionResponse.of(suggestions.keep(user, id));
    }

    @Operation(
            summary = "Undoes what was added by itself",
            description = "Removes the entry that was added and rejects the suggestion, in one action; undoing again answers "
                    + "the same. When the entry is no longer what was added (changed, ticked off, something hangs on it or "
                    + "depends on it, or it is gone) it stays, the suggestion counts as kept by the caller, and the answer is "
                    + "entry-changed. A suggestion that was kept or accepted, also by the thirty days passing, answers "
                    + "already-decided; one that still waits is a bad request.")
    @PostMapping("/{id}/undo")
    SuggestionResponse undo(UserId user, @PathVariable UUID id) {
        Undone undone = suggestions.undo(user, id);
        // Thrown here, after the transaction that wrote "kept" has ended: the refusal cannot take it back.
        switch (undone.entry()) {
            case REMOVED -> {}
            case CHANGED -> throw new ApiException(
                    ProblemCode.ENTRY_CHANGED,
                    "This entry was changed after it was added, so it stays in your plan and this suggestion counts as kept. "
                            + "You can change or remove the entry like any other.");
            case GONE -> throw new ApiException(
                    ProblemCode.ENTRY_CHANGED,
                    "This entry is no longer in your plan: one of you removed it already. This suggestion counts as kept.");
        }
        return SuggestionResponse.of(undone.suggestion());
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
