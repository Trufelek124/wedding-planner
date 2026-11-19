package io.github.trufelek124.weddingplanner.mail.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseCookie;

/**
 * Binds a connection attempt to the browser that started it. The cookie is sent only to the return address, never
 * to scripts, and with Lax so that it comes along when Google sends the browser back (a cross-site visit that
 * navigates), and with nothing else another site starts.
 */
final class ConnectCookie {

    static final String NAME = "wp_mail_connect";
    static final Duration LIFETIME = Duration.ofMinutes(10);

    private ConnectCookie() {}

    static String set(HttpServletRequest request, boolean publicAddressIsSecure, String value) {
        return cookie(request, publicAddressIsSecure, value, LIFETIME).toString();
    }

    static String clear(HttpServletRequest request, boolean publicAddressIsSecure) {
        return cookie(request, publicAddressIsSecure, "", Duration.ZERO).toString();
    }

    /** The cookie's value; nothing when the browser sent none, or sent several (then no one of them is trusted). */
    static java.util.Optional<String> valueIn(HttpServletRequest request) {
        Cookie[] all = request.getCookies();
        if (all == null) {
            return java.util.Optional.empty();
        }
        List<Cookie> ours = Arrays.stream(all).filter(cookie -> NAME.equals(cookie.getName())).toList();
        return ours.size() == 1 ? java.util.Optional.of(ours.get(0).getValue()) : java.util.Optional.empty();
    }

    private static ResponseCookie cookie(HttpServletRequest request, boolean publicAddressIsSecure, String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(request.isSecure() || publicAddressIsSecure)
                .sameSite("Lax")
                .path(MailSetup.RETURN_PATH)
                .maxAge(maxAge)
                .build();
    }
}
