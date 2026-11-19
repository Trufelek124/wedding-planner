package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Google;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the server says to Google, and nothing else: the address a browser is sent to, the exchange of a code
 * for tokens, a new access token, the mailbox's address, and the withdrawal of a permission.
 *
 * <p>It goes only to the addresses it was set up with, with a limit of seconds on every call (the answer included),
 * follows no redirect, and takes no answer that is too large or is not JSON. What goes wrong is a
 * {@link GoogleFailure}, which names the kind of failure and nothing of what was said: an answer, a code and a
 * token never reach a message or a log.
 */
@Component
class GoogleClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleClient.class);

    /** The one permission the planner ever asks for: to read, and nothing else. */
    static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly";

    private static final int MOST_BYTES = 64 * 1024;
    private static final int LONGEST_TOKEN = 4096;
    private static final Duration CONNECT = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Why a call did not give a usable answer. Nothing but the kind. */
    enum Failure {
        UNREACHABLE,
        TOO_SLOW,
        TOO_LARGE,
        NOT_JSON,
        REDIRECTED,
        REFUSED,
        SERVER_TROUBLE,
        UNUSABLE,
        /** 401: the access token is no good (any more). */
        UNAUTHORIZED,
        /** 404: what was asked for is not there; for a sync marker, that it has grown too old. */
        NOT_FOUND
    }

    static final class GoogleFailure extends Exception {
        private final Failure failure;

        GoogleFailure(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    /** What Google gave for a code. A token may be missing: the caller decides whether that is enough. */
    record Granted(String refreshToken, String accessToken, long expiresInSeconds, Set<String> scopes) {

        @Override
        public String toString() {
            return "Granted[]";
        }
    }

    sealed interface Refreshed permits Fresh, Withdrawn, WrongScope {}

    record Fresh(String accessToken, long expiresInSeconds) implements Refreshed {

        @Override
        public String toString() {
            return "Fresh[]";
        }
    }

    /** Google no longer lets this token in: access was withdrawn, or it expired. */
    record Withdrawn() implements Refreshed {}

    /** The token works but for something else than reading. */
    record WrongScope() implements Refreshed {}

    private final MailSetup setup;
    private volatile HttpClient client;

    GoogleClient(MailSetup setup) {
        this.setup = setup;
    }

    /** The address to send the browser to, where Google asks for the permission. */
    String authorizationUrl(String state, String challenge) {
        Google google = setup.require().google();
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", google.clientId());
        query.put("redirect_uri", google.returnAddress().toString());
        query.put("response_type", "code");
        query.put("scope", READ_ONLY);
        query.put("access_type", "offline");
        query.put("prompt", "consent");
        query.put("include_granted_scopes", "false");
        query.put("state", state);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        String base = google.authorizationUri().toString();
        return base + (base.contains("?") ? "&" : "?") + form(query);
    }

    Granted exchange(String code, String verifier) throws GoogleFailure {
        Google google = setup.require().google();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("code_verifier", verifier);
        body.put("redirect_uri", google.returnAddress().toString());
        body.put("client_id", google.clientId());
        body.put("client_secret", google.clientSecret());
        JsonNode answer = succeeded(post(google.tokenUri(), body));
        String access = text(answer, "access_token");
        if (access == null) {
            throw new GoogleFailure(Failure.UNUSABLE);
        }
        return new Granted(text(answer, "refresh_token"), access, seconds(answer), scopes(answer));
    }

    Refreshed refresh(String refreshToken) throws GoogleFailure {
        Google google = setup.require().google();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", google.clientId());
        body.put("client_secret", google.clientSecret());
        Answer answer = post(google.tokenUri(), body);
        if (answer.status() == 400 && answer.json() != null && "invalid_grant".equals(text(answer.json(), "error"))) {
            return new Withdrawn();
        }
        JsonNode json = succeeded(answer);
        String access = text(json, "access_token");
        if (access == null) {
            throw new GoogleFailure(Failure.UNUSABLE);
        }
        Set<String> scopes = scopes(json);
        if (!scopes.isEmpty() && !scopes.equals(Set.of(READ_ONLY))) {
            return new WrongScope();
        }
        return new Fresh(access, seconds(json));
    }

    /** Which address the mailbox has. */
    String mailboxAddress(String accessToken) throws GoogleFailure {
        Google google = setup.require().google();
        String base = google.gmailUri().toString().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/gmail/v1/users/me/profile"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(google.timeout())
                .GET()
                .build();
        String address = text(succeeded(send(request)), "emailAddress");
        if (address == null || address.length() > 254) {
            throw new GoogleFailure(Failure.UNUSABLE);
        }
        return address;
    }

    /**
     * One read of a Gmail address, {@code path} below {@code /gmail/v1/users/me}, with the limits of every call. The
     * answer is a JSON object. A 401 and a 404 are told apart from the rest; too many requests (429, or 403 with a
     * rate limit as its reason) and Google's own failures are {@link Failure#SERVER_TROUBLE}.
     */
    JsonNode gmailGet(String accessToken, String path, Map<String, String> query) throws GoogleFailure {
        Google google = setup.require().google();
        String base = google.gmailUri().toString().replaceAll("/+$", "");
        String address = base + "/gmail/v1/users/me/" + path + (query.isEmpty() ? "" : "?" + form(query));
        HttpRequest request = HttpRequest.newBuilder(URI.create(address))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(google.timeout())
                .GET()
                .build();
        Answer answer = send(request);
        if (answer.status() == 401) {
            throw new GoogleFailure(Failure.UNAUTHORIZED);
        }
        if (answer.status() == 404) {
            throw new GoogleFailure(Failure.NOT_FOUND);
        }
        if (answer.status() == 403 && rateLimited(answer.json())) {
            throw new GoogleFailure(Failure.SERVER_TROUBLE);
        }
        return succeeded(answer);
    }

    private static boolean rateLimited(JsonNode json) {
        if (json == null) {
            return false;
        }
        JsonNode errors = json.path("error").path("errors");
        if (errors.isArray()) {
            for (JsonNode error : errors) {
                String reason = error.path("reason").asString("");
                if (reason.toLowerCase(Locale.ROOT).contains("ratelimit") || reason.toLowerCase(Locale.ROOT).contains("quota")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Asks Google to withdraw the permission this token stands for. Whatever Google answers, nothing else depends on it. */
    void revoke(String token) {
        try {
            Google google = setup.require().google();
            post(google.revokeUri(), Map.of("token", token));
        } catch (GoogleFailure | RuntimeException failed) {
            // Disconnecting and replacing go on without it. Only the kind is worth a line.
            log.info("Google could not be asked to withdraw a permission");
        }
    }

    // ---------------------------------------------------------------- the calls

    private record Answer(int status, JsonNode json) {}

    private Answer post(URI address, Map<String, String> fields) throws GoogleFailure {
        Google google = setup.require().google();
        HttpRequest request = HttpRequest.newBuilder(address)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(google.timeout())
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
                .build();
        return send(request);
    }

    /** Only a 200 with a JSON object is an answer; everything else is a failure of one of the known kinds. */
    private static JsonNode succeeded(Answer answer) throws GoogleFailure {
        int status = answer.status();
        if (status >= 300 && status < 400) {
            throw new GoogleFailure(Failure.REDIRECTED);
        }
        if (status >= 500 || status == 429) {
            throw new GoogleFailure(Failure.SERVER_TROUBLE);
        }
        if (status != 200) {
            throw new GoogleFailure(Failure.REFUSED);
        }
        if (answer.json() == null || !answer.json().isObject()) {
            throw new GoogleFailure(Failure.NOT_JSON);
        }
        return answer.json();
    }

    private Answer send(HttpRequest request) throws GoogleFailure {
        Duration timeout = setup.require().google().timeout();
        CompletableFuture<HttpResponse<byte[]>> call = client().sendAsync(request, info -> new Limited());
        try {
            HttpResponse<byte[]> response = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Answer(response.statusCode(), jsonOf(response));
        } catch (TimeoutException tooSlow) {
            call.cancel(true);
            throw new GoogleFailure(Failure.TOO_SLOW);
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new GoogleFailure(Failure.UNREACHABLE);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof TooLarge) {
                throw new GoogleFailure(Failure.TOO_LARGE);
            }
            if (cause instanceof HttpTimeoutException) {
                throw new GoogleFailure(Failure.TOO_SLOW);
            }
            throw new GoogleFailure(Failure.UNREACHABLE);
        }
    }

    /** The body as JSON; null when it says it is not JSON, or is not. */
    private static JsonNode jsonOf(HttpResponse<byte[]> response) {
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            return null;
        }
        try {
            return JSON.readTree(response.body());
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private HttpClient client() {
        HttpClient made = client;
        if (made == null) {
            synchronized (this) {
                made = client;
                if (made == null) {
                    Duration timeout = setup.require().google().timeout();
                    made = HttpClient.newBuilder()
                            // A redirect is somebody else's address: never followed.
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(timeout.compareTo(CONNECT) < 0 ? timeout : CONNECT)
                            .version(HttpClient.Version.HTTP_1_1)
                            // The JVM's own proxy settings apply, if an operator gave any; no cookies: each call stands by itself.
                            .build();
                    client = made;
                }
            }
        }
        return made;
    }

    // ---------------------------------------------------------------- reading answers

    private static String text(JsonNode object, String field) {
        if (object == null) {
            return null;
        }
        JsonNode value = object.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() || text.length() > LONGEST_TOKEN ? null : text;
    }

    private static long seconds(JsonNode object) {
        JsonNode value = object.get("expires_in");
        return value != null && value.isIntegralNumber() && value.asLong() > 0 ? value.asLong() : 0;
    }

    private static Set<String> scopes(JsonNode object) {
        String scope = text(object, "scope");
        return scope == null
                ? Set.of()
                : java.util.Arrays.stream(scope.split("\\s+")).filter(part -> !part.isEmpty()).collect(Collectors.toSet());
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** The body is larger than any answer of Google's is. */
    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("too large", null, false, false);
        }
    }

    /** Collects the body, and gives up the moment it is larger than allowed. */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MOST_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
