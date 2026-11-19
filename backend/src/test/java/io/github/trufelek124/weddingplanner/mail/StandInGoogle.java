package io.github.trufelek124.weddingplanner.mail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.trufelek124.weddingplanner.TestGoogle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Stands in for Google's OAuth, token, revoke and Gmail addresses in tests. It behaves as Google does where the
 * planner relies on it: a code is good once and only for the client, the return address and the PKCE verifier it was
 * made for; a withdrawn token no longer works. No test ever goes to the real Google. Everything here is made up:
 * the tokens say so, and the mailboxes are at .example addresses.
 */
public final class StandInGoogle {

    public static final String CLIENT_SECRET = "test-client-secret-not-real-0001";
    public static final String READ_ONLY = "https://www.googleapis.com/auth/gmail.readonly";

    private static final StandInGoogle INSTANCE = new StandInGoogle();

    public static StandInGoogle get() {
        return INSTANCE;
    }

    /** One call that reached the stand-in. */
    public record Call(String method, String path, Map<String, String> form, String authorization) {}

    /** What the browser's consent page would remember about one authorization request. */
    private record Consent(String clientId, String redirectUri, String challenge, String account, Set<String> scopes, boolean refresh) {}

    /** A reply a test prepared for the next call to the token address. */
    public record Reply(int status, String contentType, String body, String location, long delayMillis, boolean hangUp) {

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, null, 0, false);
        }

        public static Reply text(int status, String body) {
            return new Reply(status, "text/html", body, null, 0, false);
        }

        public static Reply redirectTo(String location) {
            return new Reply(302, "text/html", "", location, 0, false);
        }

        public static Reply slow(long millis) {
            return new Reply(0, null, null, null, millis, false);
        }

        public static Reply dropped() {
            return new Reply(0, null, null, null, 0, true);
        }

        /** In a prepared series: this call is answered as usual. */
        public static Reply normally() {
            return new Reply(0, null, null, null, 0, false);
        }
    }

    /**
     * A message in a made-up mailbox: what Gmail's list and history say of it, and, for one written with
     * {@link Mailbox#write}, what its thread would say of it in full (headers and parts). {@code payload} is null for
     * one that was only delivered.
     */
    private record Message(String id, String threadId, Set<String> labels, Instant date, long historyId, Map<String, Object> payload) {}

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * A part of a made-up message, as Gmail's {@code format=full} would show it: its type, its headers, its body as
     * base64url in the part's own charset, and its children. Everything in it is made up.
     */
    public static final class Mime {

        private final Map<String, Object> part = new LinkedHashMap<>();

        private Mime(String type, String charset, byte[] bytes, String filename, String attachmentId, List<Mime> children) {
            List<Map<String, String>> headers = new ArrayList<>();
            String contentType = charset == null ? type : type + "; charset=\"" + charset + "\"";
            headers.add(Map.of("name", "Content-Type", "value", contentType));
            part.put("partId", "0");
            part.put("mimeType", type);
            part.put("filename", filename == null ? "" : filename);
            part.put("headers", headers);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("size", bytes == null ? 0 : bytes.length);
            if (attachmentId != null) {
                body.put("attachmentId", attachmentId);
            } else if (bytes != null) {
                body.put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            }
            part.put("body", body);
            if (children != null) {
                part.put("parts", children.stream().map(child -> child.part).toList());
            }
        }

        public static Mime plain(String text) {
            return plain(text, java.nio.charset.StandardCharsets.UTF_8.name());
        }

        public static Mime plain(String text, String charset) {
            return new Mime("text/plain", charset, text.getBytes(java.nio.charset.Charset.forName(charset)), null, null, null);
        }

        /** A part whose charset is named as Gmail would show it, but whose bytes are in another: for a name nobody knows. */
        public static Mime plainClaimingCharset(String text, String claimedCharset) {
            return new Mime("text/plain", claimedCharset, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null, null);
        }

        public static Mime html(String html) {
            return html(html, java.nio.charset.StandardCharsets.UTF_8.name());
        }

        public static Mime html(String html, String charset) {
            return new Mime("text/html", charset, html.getBytes(java.nio.charset.Charset.forName(charset)), null, null, null);
        }

        /** {@code subtype} is "mixed", "alternative", "related" ... */
        public static Mime multipart(String subtype, Mime... parts) {
            return new Mime("multipart/" + subtype, null, null, null, null, List.of(parts));
        }

        /** A file that Gmail gives by an attachment identifier only; whatever it holds is {@code inside} (never shown). */
        public static Mime file(String filename, String type, String attachmentId) {
            return new Mime(type, null, null, filename, attachmentId, null);
        }

        /** A small file that Gmail shows with its data in the thread: still a file, whatever its type says. */
        public static Mime inlineFile(String filename, String type, String text) {
            return new Mime(type, "UTF-8", text.getBytes(java.nio.charset.StandardCharsets.UTF_8), filename, null, null);
        }

        /** The same part with this header added (a disposition, for instance). */
        public Mime with(String name, String value) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> headers = (List<Map<String, String>>) part.get("headers");
            headers.add(Map.of("name", name, "value", value));
            return this;
        }
    }

    /**
     * A made-up mailbox: messages with their labels and dates, the marker (Gmail's history id) that grows with every
     * delivery, pages of its own size, and a way to make the marker too old. Everything made up.
     */
    public static final class Mailbox {

        private final List<Message> messages = new ArrayList<>();
        private long historyId = 1000;
        private long oldestValid = 0;
        private int pageSize = 2;

        /** A message arrives, with these labels (INBOX, SENT, DRAFT, SPAM, TRASH, CHAT ...), written at {@code date}. */
        public synchronized void deliver(String id, String threadId, Instant date, String... labels) {
            historyId++;
            messages.add(new Message(id, threadId, Set.of(labels), date, historyId, null));
        }

        /**
         * A message arrives with headers and a body. {@code from} and {@code subject} are written as given (so a test can
         * give them encoded, as mail has them) and left out when null.
         */
        public synchronized void write(String id, String threadId, Instant date, String from, String subject, Mime body, String... labels) {
            historyId++;
            Map<String, Object> payload = new LinkedHashMap<>(body.part);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> headers = new ArrayList<>((List<Map<String, String>>) payload.get("headers"));
            if (from != null) {
                headers.add(Map.of("name", "From", "value", from));
            }
            if (subject != null) {
                headers.add(Map.of("name", "Subject", "value", subject));
            }
            headers.add(Map.of("name", "Date", "value", "Mon, 1 Jan 2001 00:00:00 +0000 (a date the planner must not use)"));
            payload.put("headers", headers);
            messages.add(new Message(id, threadId, Set.of(labels), date, historyId, payload));
        }

        /** Gmail's {@code users.threads.get} with {@code format=full}; null when there is no such thread (404). */
        private synchronized String thread(String threadId) {
            List<Message> inThread = messages.stream().filter(m -> m.threadId().equals(threadId)).toList();
            if (inThread.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> shown = new ArrayList<>();
            // Gmail shows a thread oldest first; a message that arrived at the same moment keeps the order of delivery.
            inThread.stream().sorted(java.util.Comparator.comparing(Message::date)).forEach(m -> {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("id", m.id());
                message.put("threadId", m.threadId());
                message.put("labelIds", List.copyOf(m.labels()));
                message.put("internalDate", Long.toString(m.date().toEpochMilli()));
                message.put("payload", m.payload() == null ? Map.of("mimeType", "text/plain", "headers", List.of(), "body", Map.of("size", 0)) : m.payload());
                shown.add(message);
            });
            try {
                return JSON.writeValueAsString(Map.of("id", threadId, "historyId", Long.toString(historyId), "messages", shown));
            } catch (RuntimeException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Google no longer knows what happened before now: every marker taken until now is "too old". */
        public synchronized void forgetHistory() {
            oldestValid = historyId;
        }

        /** The marker the mailbox stands at: for a mailbox whose markers must not be mistaken for other numbers. */
        public synchronized void markerIs(long marker) {
            historyId = marker;
            oldestValid = 0;
        }

        public synchronized void pageSize(int size) {
            pageSize = size;
        }

        public synchronized String marker() {
            return Long.toString(historyId);
        }

        private synchronized String list(Map<String, String> query) {
            long after = 0;
            Set<String> without = new java.util.HashSet<>();
            for (String token : query.getOrDefault("q", "").split("\\s+")) {
                if (token.startsWith("after:")) {
                    after = Long.parseLong(token.substring(6));
                } else if (token.startsWith("-in:")) {
                    without.add(token.substring(4).toUpperCase(java.util.Locale.ROOT)
                            .replace("DRAFTS", "DRAFT").replace("CHATS", "CHAT"));
                }
            }
            long since = after;
            // Stricter than Google, which leaves out spam and the bin unless asked: here the planner must say so itself.
            List<Message> matching = new ArrayList<>();
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if (message.date().getEpochSecond() >= since && message.labels().stream().noneMatch(without::contains)) {
                    matching.add(message);
                }
            }
            int from = offset(query.get("pageToken"));
            int to = Math.min(matching.size(), from + pageSize);
            StringBuilder body = new StringBuilder("{");
            if (from < to) {
                body.append("\"messages\":[").append(matching.subList(from, to).stream()
                        .map(m -> "{\"id\":\"" + m.id() + "\",\"threadId\":\"" + m.threadId() + "\"}")
                        .collect(Collectors.joining(","))).append("],");
            }
            if (to < matching.size()) {
                body.append("\"nextPageToken\":\"page-").append(to).append("\",");
            }
            return body.append("\"resultSizeEstimate\":").append(matching.size()).append("}").toString();
        }

        /** Null: the marker is too old (404). */
        private synchronized String history(Map<String, String> query) {
            long start = Long.parseLong(query.getOrDefault("startHistoryId", "0"));
            if (start < oldestValid) {
                return null;
            }
            List<Message> added = messages.stream().filter(m -> m.historyId() > start).toList();
            int from = offset(query.get("pageToken"));
            int to = Math.min(added.size(), from + pageSize);
            StringBuilder body = new StringBuilder("{");
            if (from < to) {
                body.append("\"history\":[").append(added.subList(from, to).stream()
                        .map(m -> "{\"id\":\"" + m.historyId() + "\",\"messagesAdded\":[{\"message\":{\"id\":\"" + m.id()
                                + "\",\"threadId\":\"" + m.threadId() + "\",\"labelIds\":["
                                + m.labels().stream().map(l -> "\"" + l + "\"").collect(Collectors.joining(",")) + "]}}]}")
                        .collect(Collectors.joining(","))).append("],");
            }
            if (to < added.size()) {
                body.append("\"nextPageToken\":\"page-").append(to).append("\",");
            }
            return body.append("\"historyId\":\"").append(historyId).append("\"}").toString();
        }

        private static int offset(String pageToken) {
            return pageToken == null ? 0 : Integer.parseInt(pageToken.substring("page-".length()));
        }
    }

    private final HttpServer server;
    private final String base;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Consent> codes = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
    private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();
    private final Map<String, String> accessTokens = new ConcurrentHashMap<>();
    private final AtomicInteger numbers = new AtomicInteger();
    private volatile Reply nextToken;
    private volatile Reply nextProfile;
    private volatile Reply nextRevoke;
    private volatile Reply everyToken;
    private volatile Reply everyRevoke;
    private volatile Reply everyProfile;
    private final Set<String> verifiersSeen = ConcurrentHashMap.newKeySet();
    private final Map<String, Mailbox> mailboxes = new ConcurrentHashMap<>();
    private final Map<String, Deque<Reply>> series = new ConcurrentHashMap<>();
    private final Map<String, Reply> everyOfAccount = new ConcurrentHashMap<>();

    private StandInGoogle() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "stand-in-google");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/token", this::token);
            server.createContext("/revoke", this::revoke);
            server.createContext("/gmail/v1/users/me/profile", this::profile);
            server.createContext("/gmail/v1/users/me/messages", exchange -> gmail(exchange, "/gmail/v1/users/me/messages", Mailbox::list));
            server.createContext("/gmail/v1/users/me/threads/", exchange -> thread(exchange));
            // What the planner must never ask for: a message's own address (and with it an attachment's) is only counted.
            server.createContext("/gmail/v1/users/me/messages/", exchange -> {
                record(exchange, "/gmail/v1/users/me/messages/", Map.of());
                answer(exchange, 404, "application/json", "{\"error\":{\"code\":404}}");
            });
            server.createContext("/gmail/v1/users/me/history", exchange -> gmail(exchange, "/gmail/v1/users/me/history", Mailbox::history));
            server.createContext("/elsewhere", exchange -> {
                record(exchange, "/elsewhere", Map.of());
                answer(exchange, 200, "application/json", "{\"hello\":\"elsewhere\"}");
            });
            server.start();
            base = "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- the addresses to give the planner

    public String authorizationUri() {
        return base + "/o/oauth2/v2/auth";
    }

    public String tokenUri() {
        return base + "/token";
    }

    public String revokeUri() {
        return base + "/revoke";
    }

    public String gmailUri() {
        return base;
    }

    /** Somewhere a redirect could lead to; every call there is counted. */
    public String elsewhere() {
        return base + "/elsewhere";
    }

    // ---------------------------------------------------------------- what a test does

    /** Forgets everything between tests. */
    public void reset() {
        calls.clear();
        codes.clear();
        refreshTokens.clear();
        withdrawn.clear();
        accessTokens.clear();
        verifiersSeen.clear();
        nextToken = null;
        nextProfile = null;
        nextRevoke = null;
        everyToken = null;
        everyRevoke = null;
        everyProfile = null;
        mailboxes.clear();
        series.clear();
        everyOfAccount.clear();
    }

    /** The made-up mailbox of this account (an address at .example), made when first asked for. */
    public Mailbox mailbox(String account) {
        return mailboxes.computeIfAbsent(account, name -> new Mailbox());
    }

    /**
     * The next calls to this path (a whole one, like "/gmail/v1/users/me/messages") are answered like
     * this, one reply each in turn; {@link Reply#normally()} lets one through. After the last, calls are as usual.
     */
    public void answerNextCallsToWith(String path, Reply... replies) {
        series.computeIfAbsent(path, key -> new java.util.concurrent.ConcurrentLinkedDeque<>()).addAll(List.of(replies));
    }

    /** Every Gmail call made with this account's access tokens is answered like this, until {@link #reset}. */
    public void answerEveryGmailCallOfWith(String account, Reply reply) {
        everyOfAccount.put(account, reply);
    }

    public void stopAnsweringGmailCallsOf(String account) {
        everyOfAccount.remove(account);
    }

    public Map<String, String> query(String authorizationUrl) {
        URI uri = URI.create(authorizationUrl);
        return form(uri.getRawQuery());
    }

    /** What a person does at Google's page: choose an account and allow. Answers with the code Google would send back. */
    public String consent(String authorizationUrl, String account) {
        return consent(authorizationUrl, account, Set.of(READ_ONLY), true);
    }

    public String consent(String authorizationUrl, String account, Set<String> scopesGranted, boolean withRefreshToken) {
        Map<String, String> query = query(authorizationUrl);
        String code = "test-code-not-real-" + numbers.incrementAndGet() + "-" + UUID.randomUUID();
        codes.put(code, new Consent(query.get("client_id"), query.get("redirect_uri"), query.get("code_challenge"), account,
                scopesGranted, withRefreshToken));
        return code;
    }

    /** The next call to the token address is answered like this instead (once). */
    public void answerNextTokenCallWith(Reply reply) {
        nextToken = reply;
    }

    public void answerEveryTokenCallWith(Reply reply) {
        everyToken = reply;
    }

    public void answerNextProfileCallWith(Reply reply) {
        nextProfile = reply;
    }

    public void answerEveryProfileCallWith(Reply reply) {
        everyProfile = reply;
    }

    public void answerEveryRevokeCallWith(Reply reply) {
        everyRevoke = reply;
    }

    /** Google no longer lets this refresh token in, as when the person withdrew access in their Google account. */
    public void withdraw(String refreshToken) {
        withdrawn.add(refreshToken);
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public List<Call> calls(String path) {
        return calls().stream().filter(call -> call.path().equals(path)).toList();
    }

    public int count(String path) {
        return calls(path).size();
    }

    /** Every refresh token Google has handed out, and the access tokens: so tests can look for them elsewhere. */
    public Set<String> issuedSecrets() {
        Set<String> all = new HashSet<>(refreshTokens.keySet());
        all.addAll(accessTokens.keySet());
        return all;
    }

    public Set<String> verifiersSeen() {
        return Set.copyOf(verifiersSeen);
    }

    /** The tokens that were sent to the revoke address. */
    public List<String> revokedTokens() {
        return calls("/revoke").stream().map(call -> call.form().get("token")).toList();
    }

    /** The refresh token Google handed out for this account's mailbox. */
    public String refreshTokenOf(String account) {
        return refreshTokens.entrySet().stream().filter(entry -> entry.getValue().equals(account)).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    public List<String> refreshTokensIssued() {
        return List.copyOf(refreshTokens.keySet());
    }

    // ---------------------------------------------------------------- the addresses

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        record(exchange, "/token", form);
        Reply prepared = nextToken != null ? nextToken : everyToken;
        nextToken = null;
        if (prepared != null && replied(exchange, prepared)) {
            return;
        }
        String clientSecret = form.get("client_secret");
        if (!TestGoogle.CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(clientSecret)) {
            answer(exchange, 401, "application/json", "{\"error\":\"invalid_client\"}");
            return;
        }
        switch (String.valueOf(form.get("grant_type"))) {
            case "authorization_code" -> {
                Consent consent = form.get("code") == null ? null : codes.remove(form.get("code"));
                if (consent == null
                        || !consent.clientId().equals(form.get("client_id"))
                        || !consent.redirectUri().equals(form.get("redirect_uri"))
                        || form.get("code_verifier") == null
                        || !consent.challenge().equals(challengeOf(form.get("code_verifier")))) {
                    answer(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
                    return;
                }
                verifiersSeen.add(form.get("code_verifier"));
                String access = newAccessToken(consent.account());
                StringBuilder body = new StringBuilder("{\"access_token\":\"" + access + "\",\"expires_in\":3599,\"token_type\":\"Bearer\",");
                if (consent.refresh()) {
                    String refresh = "test-refresh-not-real-" + numbers.incrementAndGet() + "-" + UUID.randomUUID();
                    refreshTokens.put(refresh, consent.account());
                    body.append("\"refresh_token\":\"").append(refresh).append("\",");
                }
                body.append("\"scope\":\"").append(String.join(" ", consent.scopes())).append("\"}");
                answer(exchange, 200, "application/json", body.toString());
            }
            case "refresh_token" -> {
                String refresh = form.get("refresh_token");
                if (refresh == null || !refreshTokens.containsKey(refresh) || withdrawn.contains(refresh)) {
                    answer(exchange, 400, "application/json", "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");
                    return;
                }
                answer(exchange, 200, "application/json", "{\"access_token\":\"" + newAccessToken(refreshTokens.get(refresh))
                        + "\",\"expires_in\":3599,\"token_type\":\"Bearer\",\"scope\":\"" + READ_ONLY + "\"}");
            }
            default -> answer(exchange, 400, "application/json", "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private void revoke(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        record(exchange, "/revoke", form);
        Reply prepared = nextRevoke != null ? nextRevoke : everyRevoke;
        nextRevoke = null;
        if (prepared != null && replied(exchange, prepared)) {
            return;
        }
        String token = form.get("token");
        if (token != null && refreshTokens.containsKey(token)) {
            withdrawn.add(token);
        }
        answer(exchange, 200, "application/json", "{}");
    }

    private void profile(HttpExchange exchange) throws IOException {
        record(exchange, "/gmail/v1/users/me/profile", Map.of());
        Reply prepared = nextProfile != null ? nextProfile : everyProfile;
        nextProfile = null;
        if (prepared != null && replied(exchange, prepared)) {
            return;
        }
        String account = accountOf(exchange);
        Reply more = preparedForGmail("/gmail/v1/users/me/profile", account);
        if (more != null && replied(exchange, more)) {
            return;
        }
        if (account == null) {
            answer(exchange, 401, "application/json", "{\"error\":{\"code\":401}}");
            return;
        }
        String marker = mailboxes.containsKey(account) ? mailboxes.get(account).marker() : "4711";
        answer(exchange, 200, "application/json",
                "{\"emailAddress\":\"" + account + "\",\"messagesTotal\":12,\"threadsTotal\":7,\"historyId\":\"" + marker + "\"}");
    }

    /** Messages and history: what the calls of the planner's sync reach. What Google says is made from the account's mailbox. */
    private void gmail(HttpExchange exchange, String path, java.util.function.BiFunction<Mailbox, Map<String, String>, String> what)
            throws IOException {
        Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
        record(exchange, path, query);
        String account = accountOf(exchange);
        Reply prepared = preparedForGmail(path, account);
        if (prepared != null && replied(exchange, prepared)) {
            return;
        }
        if (account == null) {
            answer(exchange, 401, "application/json", "{\"error\":{\"code\":401}}");
            return;
        }
        String body = what.apply(mailbox(account), query);
        if (body == null) {
            answer(exchange, 404, "application/json", "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}");
        } else {
            answer(exchange, 200, "application/json", body);
        }
    }

    /** {@code users.threads.get}: {@code /threads/{id}}, in full. */
    private void thread(HttpExchange exchange) throws IOException {
        String path = "/gmail/v1/users/me/threads";
        Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
        String id = exchange.getRequestURI().getPath().substring("/gmail/v1/users/me/threads/".length());
        Map<String, String> seen = new LinkedHashMap<>(query);
        seen.put("id", id);
        record(exchange, path, seen);
        String account = accountOf(exchange);
        Reply prepared = preparedForGmail(path, account);
        if (prepared != null && replied(exchange, prepared)) {
            return;
        }
        if (account == null) {
            answer(exchange, 401, "application/json", "{\"error\":{\"code\":401}}");
            return;
        }
        String body = mailbox(account).thread(id);
        if (body == null) {
            answer(exchange, 404, "application/json", "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}");
        } else {
            answer(exchange, 200, "application/json", body);
        }
    }

    private String accountOf(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.startsWith("Bearer ") ? accessTokens.get(header.substring(7)) : null;
    }

    private Reply preparedForGmail(String path, String account) {
        Reply every = account == null ? null : everyOfAccount.get(account);
        if (every != null) {
            return every;
        }
        Deque<Reply> next = series.get(path);
        return next == null ? null : next.poll();
    }

    /** Plays a prepared reply when it is a complete one; a "slow" one only waits, and the normal answer follows. */
    private boolean replied(HttpExchange exchange, Reply reply) throws IOException {
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reply.hangUp()) {
            exchange.close();
            return true;
        }
        if (reply.status() == 0) {
            return false;
        }
        if (reply.location() != null) {
            exchange.getResponseHeaders().add("Location", reply.location());
        }
        answer(exchange, reply.status(), reply.contentType(), reply.body());
        return true;
    }

    private String newAccessToken(String account) {
        String access = "test-access-not-real-" + numbers.incrementAndGet() + "-" + UUID.randomUUID();
        accessTokens.put(access, account);
        return access;
    }

    private void record(HttpExchange exchange, String path, Map<String, String> form) {
        calls.add(new Call(exchange.getRequestMethod(), path, new LinkedHashMap<>(form),
                exchange.getRequestHeaders().getFirst("Authorization")));
    }

    private static void answer(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    static Map<String, String> form(String encoded) {
        Map<String, String> fields = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return fields;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            fields.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return fields;
    }

    private static String challengeOf(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
