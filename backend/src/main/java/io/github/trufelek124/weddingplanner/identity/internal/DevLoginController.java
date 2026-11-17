package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.DevSignInRequest;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing in as an allowed address without Google, for development and automated tests.
 * This endpoint does not exist unless DEV_LOGIN_ENABLED is true; it must never be in production.
 */
@RestController
@ConditionalOnProperty(name = "app.identity.dev-login-enabled", havingValue = "true")
class DevLoginController {

    private final SignInService signIn;
    private final AuthController auth;

    DevLoginController(SignInService signIn, AuthController auth) {
        this.signIn = signIn;
        this.auth = auth;
        LoggerFactory.getLogger(DevLoginController.class)
                .warn("The development login is ON: anyone who can reach this server can sign in as an allowed address");
    }

    @PostMapping(path = "/api/auth/dev", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    SessionResponse dev(@Valid @RequestBody DevSignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        return auth.answer(signIn.signInForDevelopment(body.email()), request, response);
    }
}
