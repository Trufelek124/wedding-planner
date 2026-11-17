package io.github.trufelek124.weddingplanner.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * A browser keeps its refresh token in a cookie that scripts cannot read and that is sent only to
 * the sign-in endpoints of this site, never along with a request that another site started.
 * Anything else that calls the API (the phone app) gets the token in the answer instead.
 */
@Component
class RefreshCookie {

    static final String NAME = "wp_refresh";
    private static final String PATH = "/api/auth";

    private final Duration ttl;

    RefreshCookie(IdentityProperties properties) {
        this.ttl = properties.refreshTokenTtl();
    }

    /** Browsers say where a POST comes from; other clients do not. */
    static boolean isBrowser(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.ORIGIN) != null;
    }

    void set(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, refreshToken, ttl).toString());
    }

    void clear(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
    }

    private static ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
