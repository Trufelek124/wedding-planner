package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether the e-mail import is set up, and with what. It is set up only when the Google client's id and secret, the
 * planner's own public address and the key for the tokens are all there and valid. Otherwise nothing here is ever
 * used, and nothing is asked of Google.
 */
@Component
class MailSetup {

    private static final Logger log = LoggerFactory.getLogger(MailSetup.class);

    /** The names an operator knows the settings by, for saying which one is missing. */
    static final String CLIENT_ID = "GOOGLE_OAUTH_WEB_CLIENT_ID";
    static final String CLIENT_SECRET = "GOOGLE_OAUTH_CLIENT_SECRET";
    static final String PUBLIC_URL = "APP_PUBLIC_URL";
    static final String TOKEN_KEY = "APP_MAIL_TOKEN_KEY";

    static final String RETURN_PATH = "/api/mail/gmail/return";
    private static final Duration LONGEST_WAIT = Duration.ofSeconds(60);

    /** What the import runs on. It holds secrets: it prints nothing of itself. */
    record Google(
            String clientId,
            String clientSecret,
            URI publicUrl,
            byte[] tokenKey,
            URI authorizationUri,
            URI tokenUri,
            URI revokeUri,
            URI gmailUri,
            Duration timeout) {

        /** Where Google sends the browser back to. */
        URI returnAddress() {
            return URI.create(publicUrl + RETURN_PATH);
        }

        /** The one page of the web app every return ends on; only the outcome differs. */
        URI page(Outcome outcome) {
            return URI.create(publicUrl + pageAddress(outcome));
        }

        @Override
        public String toString() {
            return "Google[]";
        }
    }

    enum Outcome {
        YES("yes"),
        REFUSED("refused"),
        FAILED("failed");

        private final String word;

        Outcome(String word) {
            this.word = word;
        }
    }

    static String pageAddress(Outcome outcome) {
        return "/mail?connected=" + outcome.word;
    }

    /** What was made of the settings: the import's means, or why it is off. */
    record Parsed(Google google, List<String> missing, List<String> invalid) {}

    /** The import's means: what it runs on, and what seals the tokens. */
    record Ready(Google google, TokenVault vault) {

        @Override
        public String toString() {
            return "Ready[]";
        }
    }

    private final Ready ready;

    MailSetup(MailProperties properties) {
        Parsed parsed = parse(properties);
        if (parsed.google() == null) {
            // Names only, never a value.
            if (parsed.invalid().isEmpty()) {
                log.info("The e-mail import is off: not set: {}", String.join(", ", parsed.missing()));
            } else {
                log.warn(
                        "The e-mail import is off: not valid: {}{}",
                        String.join(", ", parsed.invalid()),
                        parsed.missing().isEmpty() ? "" : "; not set: " + String.join(", ", parsed.missing()));
            }
            this.ready = null;
        } else {
            log.info("The e-mail import is set up");
            this.ready = new Ready(parsed.google(), new TokenVault(parsed.google().tokenKey()));
        }
    }

    Optional<Ready> ready() {
        return Optional.ofNullable(ready);
    }

    /** The import's means, or the answer that says it is off. */
    Ready require() {
        if (ready == null) {
            throw new ApiException(ProblemCode.MAIL_NOT_SET_UP, "The e-mail import is not set up on this server.");
        }
        return ready;
    }

    static Parsed parse(MailProperties properties) {
        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        String clientId = text(properties.googleClientId());
        String clientSecret = text(properties.googleClientSecret());
        String publicUrl = text(properties.publicUrl());
        String tokenKey = text(properties.tokenKey());
        if (clientId.isEmpty()) {
            missing.add(CLIENT_ID);
        }
        if (clientSecret.isEmpty()) {
            missing.add(CLIENT_SECRET);
        }
        URI base = null;
        if (publicUrl.isEmpty()) {
            missing.add(PUBLIC_URL);
        } else {
            base = webAddress(publicUrl);
            // Only an address that is a place and nothing else: the pages and the return address are made from it.
            if (base == null || base.getRawQuery() != null || base.getRawFragment() != null) {
                invalid.add(PUBLIC_URL);
                base = null;
            } else {
                base = URI.create(base.toString().replaceAll("/+$", ""));
            }
        }
        byte[] key = null;
        if (tokenKey.isEmpty()) {
            missing.add(TOKEN_KEY);
        } else {
            key = decodeKey(tokenKey);
            if (key == null) {
                invalid.add(TOKEN_KEY);
            }
        }
        URI authorization = google("app.mail.authorization-uri", properties.authorizationUri(), invalid);
        URI token = google("app.mail.token-uri", properties.tokenUri(), invalid);
        URI revoke = google("app.mail.revoke-uri", properties.revokeUri(), invalid);
        URI gmail = google("app.mail.gmail-uri", properties.gmailUri(), invalid);
        Duration timeout = properties.googleTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(LONGEST_WAIT) > 0) {
            invalid.add("app.mail.google-timeout");
        }
        if (!missing.isEmpty() || !invalid.isEmpty()) {
            return new Parsed(null, List.copyOf(missing), List.copyOf(invalid));
        }
        return new Parsed(
                new Google(clientId, clientSecret, base, key, authorization, token, revoke, gmail, timeout), List.of(), List.of());
    }

    private static URI google(String name, String value, List<String> invalid) {
        URI address = value == null ? null : webAddress(text(value));
        if (address == null) {
            invalid.add(name);
        }
        return address;
    }

    /**
     * An address the server may use: https, or http on this machine for a developer. No user name, no fragment.
     *
     * @return null when it is not one
     */
    static URI webAddress(String text) {
        try {
            URI address = new URI(text);
            String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
            String host = address.getHost();
            if (host == null || address.getRawUserInfo() != null || address.getRawFragment() != null) {
                return null;
            }
            boolean https = scheme.equals("https");
            boolean here = scheme.equals("http") && host.equalsIgnoreCase("localhost");
            return https || here ? address : null;
        } catch (URISyntaxException notAnAddress) {
            return null;
        }
    }

    /** 32 bytes, base64. Anything else is no key. */
    private static byte[] decodeKey(String text) {
        try {
            byte[] key = Base64.getDecoder().decode(text);
            return key.length == TokenVault.KEY_BYTES ? key : null;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
