package io.github.trufelek124.weddingplanner.wedding.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.DetailsRequest;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.RenameRequest;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.SetUpRequest;
import io.github.trufelek124.weddingplanner.wedding.internal.WeddingDtos.WeddingResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/wedding", produces = MediaType.APPLICATION_JSON_VALUE)
class WeddingController {

    private final WeddingService weddings;

    WeddingController(WeddingService weddings) {
        this.weddings = weddings;
    }

    /** Answers the wedding-not-set-up problem until someone has set the wedding up. */
    @GetMapping
    WeddingResponse get(UserId user) {
        return weddings.get(user);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    WeddingResponse setUp(UserId user, @Valid @RequestBody SetUpRequest request) {
        return weddings.setUp(user, request);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    WeddingResponse update(UserId user, @Valid @RequestBody DetailsRequest request) {
        return weddings.update(user, request);
    }

    @PutMapping(path = "/members/{memberId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    WeddingResponse rename(UserId user, @PathVariable UUID memberId, @Valid @RequestBody RenameRequest request) {
        return weddings.rename(user, memberId, request.displayName());
    }
}
