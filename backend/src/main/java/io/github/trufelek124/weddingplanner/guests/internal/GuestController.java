package io.github.trufelek124.weddingplanner.guests.internal;

import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestChange;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestCounts;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestResponse;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.NewGuest;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.StatusChange;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.Filter;
import io.github.trufelek124.weddingplanner.guests.internal.GuestTypes.Status;
import io.github.trufelek124.weddingplanner.identity.UserId;
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
@RequestMapping(path = "/api/guests", produces = MediaType.APPLICATION_JSON_VALUE)
class GuestController {

    private final GuestService guests;

    GuestController(GuestService guests) {
        this.guests = guests;
    }

    /** By name. */
    @GetMapping
    List<GuestResponse> list(
            UserId user,
            @Parameter(description = "Only guests in one of these states.") @RequestParam(required = false) List<Status> status,
            @Parameter(description = "Only the guests of this member.") @RequestParam(required = false) UUID sideMemberId,
            @Parameter(description = "true: only the guests who are both's.") @RequestParam(defaultValue = "false") boolean bothSides,
            @Parameter(description = "Only the guests of this household.") @RequestParam(required = false) UUID householdId,
            @Parameter(description = "true: only the guests who belong to no household.") @RequestParam(defaultValue = "false")
                    boolean withoutHousehold) {
        Filter filter = new Filter(status, sideMemberId, bothSides, householdId, withoutHousehold);
        return guests.list(user, filter).stream().map(GuestResponse::of).toList();
    }

    /** How many people that makes, whatever the list is narrowed down to. */
    @GetMapping("/counts")
    GuestCounts counts(UserId user) {
        return guests.counts(user);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    GuestResponse create(UserId user, @Valid @RequestBody NewGuest request) {
        return GuestResponse.of(guests.create(user, request));
    }

    @GetMapping("/{id}")
    GuestResponse get(UserId user, @PathVariable UUID id) {
        return GuestResponse.of(guests.get(user, id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    GuestResponse change(UserId user, @PathVariable UUID id, @Valid @RequestBody GuestChange request) {
        return GuestResponse.of(guests.change(user, id, request));
    }

    /** Sets the status alone: an answer that came in is recorded in one action. */
    @PutMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    GuestResponse setStatus(UserId user, @PathVariable UUID id, @Valid @RequestBody StatusChange request) {
        return GuestResponse.of(guests.setStatus(user, id, request.status()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(UserId user, @PathVariable UUID id) {
        guests.delete(user, id);
    }
}
