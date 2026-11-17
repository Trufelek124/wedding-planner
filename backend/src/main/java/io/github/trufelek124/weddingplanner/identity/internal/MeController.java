package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.Me;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final SignInService signIn;

    MeController(SignInService signIn) {
        this.signIn = signIn;
    }

    @GetMapping(path = "/api/me", produces = MediaType.APPLICATION_JSON_VALUE)
    Me me(UserId userId) {
        return Me.of(signIn.user(userId.value()));
    }
}
