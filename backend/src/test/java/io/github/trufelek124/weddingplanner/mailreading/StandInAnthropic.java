package io.github.trufelek124.weddingplanner.mailreading;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the AI service's address in tests: {@code POST /v1/messages}, answered the way the service answers
 * (a message with its stop reason and its usage, or an error with its type), and every request it was asked kept, so
 * that a test can look at exactly what would have left the server. No test ever goes to the real service.
 *
 * <p>Everything in it is made up. Unless a test prepares an answer, a call is answered with a message that says no
 * proposals and "does not concern the wedding", which is what a quiet reply looks like for either step.
 */
public final class StandInAnthropic {

    public static final String KEY = "sk-test-not-a-real-key-0123456789abcdef";

    private static final StandInAnthropic INSTANCE = new StandInAnthropic();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static StandInAnthropic get() {
        return INSTANCE;
    }

    /** One request that reached the stand-in: where, with which headers (names in lower case), and what body. */
    public record Call(String method, String path, String query, Map<String, String> headers, String body, Map<String, Object> json) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String name) {
            return (Map<String, Object>) json.get(name);
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> messages() {
            return (List<Map<String, Object>>) json.get("messages");
        }

        /** The text of the one user turn. */
        @SuppressWarnings("unchecked")
        public String userTurn() {
            Object content = messages().get(0).get("content");
            if (content instanceof String text) {
                return text;
            }
            StringBuilder text = new StringBuilder();
            for (Map<String, Object> block : (List<Map<String, Object>>) content) {
                text.append(block.get("text"));
            }
            return text.toString();
        }

        public String system() {
            Object system = json.get("system");
            if (system instanceof String text) {
                return text;
            }
            StringBuilder text = new StringBuilder();
            for (Object block : (List<?>) system) {
                text.append(((Map<?, ?>) block).get("text"));
            }
            return text.toString();
        }
    }

    /** What the stand-in answers one call with. {@code %MODEL%} in a body becomes the model that was asked for. */
    public record Reply(int status, String body, boolean hangUp) {

        /** A message that ended its turn with this text. */
        public static Reply says(String text) {
            return message("end_turn", text, 120, 40, "%MODEL%");
        }

        public static Reply says(String text, String model, long in, long out) {
            return message("end_turn", text, in, out, model);
        }

        public static Reply message(String stopReason, String text, long in, long out, String model) {
            return new Reply(200, "{\"id\":\"msg_test_0001\",\"type\":\"message\",\"role\":\"assistant\",\"model\":" + quote(model)
                    + ",\"content\":" + (text == null ? "[]" : "[{\"type\":\"text\",\"text\":" + quote(text) + "}]")
                    + ",\"stop_reason\":" + quote(stopReason) + ",\"stop_sequence\":null,\"usage\":{\"input_tokens\":" + in
                    + ",\"output_tokens\":" + out + "}}", false);
        }

        /** An answer whose content is whatever this JSON array says. */
        public static Reply withContent(String stopReason, String contentJson) {
            return new Reply(200, "{\"id\":\"msg_test_0001\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"%MODEL%\",\"content\":"
                    + contentJson + ",\"stop_reason\":" + quote(stopReason) + ",\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":5}}", false);
        }

        public static Reply refusal() {
            return message("refusal", null, 77, 0, "%MODEL%");
        }

        /** The service's own error, with the text it would put in it. */
        public static Reply error(int status, String type, String message) {
            return new Reply(status, "{\"type\":\"error\",\"error\":{\"type\":" + quote(type) + ",\"message\":" + quote(message) + "}}", false);
        }

        public static Reply dropped() {
            return new Reply(0, null, true);
        }
    }

    private final HttpServer server;
    private final String base;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Reply> series = new ArrayDeque<>();
    private volatile Reply every;

    private StandInAnthropic() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "stand-in-anthropic");
                thread.setDaemon(true);
                return thread;
            }));
            server.createContext("/", this::serve);
            server.start();
            base = "http://localhost:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the stand-in for the AI service", e);
        }
    }

    public String baseUrl() {
        return base;
    }

    public void reset() {
        calls.clear();
        synchronized (series) {
            series.clear();
        }
        every = null;
    }

    /** The next calls are answered with these, in order; after them, the usual answer. */
    public void answerNextCallsWith(Reply... replies) {
        synchronized (series) {
            series.addAll(List.of(replies));
        }
    }

    public void answerEveryCallWith(Reply reply) {
        every = reply;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public Call onlyCall() {
        List<Call> all = calls();
        if (all.size() != 1) {
            throw new AssertionError("one call was expected, there were " + all.size());
        }
        return all.get(0);
    }

    @SuppressWarnings("unchecked")
    private void serve(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(", ", values)));
        Map<String, Object> json;
        try {
            json = body.isBlank() ? Map.of() : JSON.readValue(body, Map.class);
        } catch (RuntimeException notJson) {
            json = Map.of();
        }
        Call call = new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(), headers, body, json);
        calls.add(call);
        Reply reply = every;
        if (reply == null) {
            synchronized (series) {
                reply = series.poll();
            }
        }
        if (reply == null) {
            reply = Reply.says("{\"concernsTheWedding\":false,\"howSure\":10,\"proposals\":[]}");
        }
        if (reply.hangUp()) {
            exchange.close();
            return;
        }
        String model = json.get("model") instanceof String asked ? asked : "unknown";
        byte[] bytes = reply.body().replace("%MODEL%", model).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // So that the SDK's own retries do not make a test wait.
        exchange.getResponseHeaders().add("retry-after-ms", "1");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String quote(String text) {
        return JSON.writeValueAsString(text);
    }
}
