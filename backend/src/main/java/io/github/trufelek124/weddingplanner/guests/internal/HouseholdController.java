package io.github.trufelek124.weddingplanner.guests.internal;

import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HouseholdName;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HouseholdResponse;
import io.github.trufelek124.weddingplanner.identity.UserId;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Households: people who are invited together. */
@RestController
@RequestMapping(path = "/api/households", produces = MediaType.APPLICATION_JSON_VALUE)
class HouseholdController {

    private final GuestService guests;

    HouseholdController(GuestService guests) {
        this.guests = guests;
    }

    /** By name. */
    @GetMapping
    List<HouseholdResponse> list(UserId user) {
        return guests.households(user).stream().map(HouseholdResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    HouseholdResponse add(UserId user, @Valid @RequestBody HouseholdName request) {
        return HouseholdResponse.of(guests.addHousehold(user, request.name()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    HouseholdResponse rename(UserId user, @PathVariable UUID id, @Valid @RequestBody HouseholdName request) {
        return HouseholdResponse.of(guests.renameHousehold(user, id, request.name()));
    }

    /** Its guests stay on the list, without a household. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(UserId user, @PathVariable UUID id) {
        guests.deleteHousehold(user, id);
    }
}
