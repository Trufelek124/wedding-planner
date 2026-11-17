package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.AuthConfig;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.GoogleSignInRequest;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.RefreshRequest;
import io.github.trufelek124.weddingplanner.identity.internal.AuthDtos.SessionResponse;
import io.github.trufelek124.weddingplanner.identity.internal.SignInService.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The endpoints that need no sign-in, because they are how one signs in. */
@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
class AuthController {

    private final SignInService signIn;
    private final RefreshCookie cookie;
    private final AuthConfig config;

    AuthController(SignInService signIn, RefreshCookie cookie, IdentityProperties properties) {
        this.signIn = signIn;
        this.cookie = cookie;
        String clientId = properties.googleClientId();
        this.config = new AuthConfig(clientId.isEmpty() ? null : clientId, properties.devLoginEnabled());
    }

    @GetMapping("/config")
    AuthConfig config() {
        return config;
    }

    @PostMapping(path = "/google", consumes = MediaType.APPLICATION_JSON_VALUE)
    SessionResponse google(
            @Valid @RequestBody GoogleSignInRequest body, HttpServletRequest request, HttpServletResponse response) {
        return answer(signIn.signInWithGoogle(body.idToken()), request, response);
    }

    @PostMapping("/refresh")
    SessionResponse refresh(
            @Valid @RequestBody(required = false) RefreshRequest body,
            @CookieValue(name = RefreshCookie.NAME, required = false) String fromCookie,
            HttpServletRequest request,
            HttpServletResponse response) {
        return answer(signIn.refresh(presented(body, fromCookie)), request, response);
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(
            @Valid @RequestBody(required = false) RefreshRequest body,
            @CookieValue(name = RefreshCookie.NAME, required = false) String fromCookie,
            HttpServletRequest request,
            HttpServletResponse response) {
        String token = fromCookie != null && !fromCookie.isBlank() ? fromCookie : body == null ? null : body.refreshToken();
        if (token != null && !token.isBlank()) {
            signIn.signOut(token);
        }
        if (RefreshCookie.isBrowser(request)) {
            cookie.clear(request, response);
        }
    }

    SessionResponse answer(Session session, HttpServletRequest request, HttpServletResponse response) {
        boolean browser = RefreshCookie.isBrowser(request);
        if (browser) {
            cookie.set(request, response, session.refreshToken());
        }
        return SessionResponse.of(session, !browser);
    }

    private static String presented(RefreshRequest body, String fromCookie) {
        if (fromCookie != null && !fromCookie.isBlank()) {
            return fromCookie;
        }
        if (body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
            return body.refreshToken();
        }
        throw new ApiException(ProblemCode.UNAUTHENTICATED, "You are signed out. Sign in again.");
    }
}
