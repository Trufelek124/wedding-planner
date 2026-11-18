package io.github.trufelek124.weddingplanner.inspirations.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A web server on the test machine that stands in for the internet: tests fetch previews from it and
 * never from anywhere else. It remembers every request it got, so a test can also say that none came.
 */
public final class StandIn {

    /** What one request looked like. */
    public record Seen(String path, Map<String, List<String>> headers) {}

    /** What to answer. */
    public record Answer(int status, Map<String, String> headers, byte[] body, long delayMillis) {

        public static Answer html(String page) {
            return new Answer(200, Map.of("Content-Type", "text/html; charset=utf-8"), page.getBytes(StandardCharsets.UTF_8), 0);
        }

        public static Answer of(int status, String contentType, byte[] body) {
            return new Answer(status, Map.of("Content-Type", contentType), body, 0);
        }

        public static Answer redirect(String to) {
            return new Answer(302, Map.of("Location", to), new byte[0], 0);
        }

        public static Answer status(int status) {
            return new Answer(status, Map.of(), new byte[0], 0);
        }

        public Answer after(long millis) {
            return new Answer(status, headers, body, millis);
        }

        public Answer with(String header, String value) {
            Map<String, String> all = new java.util.HashMap<>(headers);
            all.put(header, value);
            return new Answer(status, Map.copyOf(all), body, delayMillis);
        }
    }

    private static final StandIn SHARED = new StandIn();

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    public StandIn() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::answer);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stand-in");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    /** One for the whole test run, for tests that share an application. */
    public static StandIn shared() {
        return SHARED;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI at(String path) {
        return URI.create("http://127.0.0.1:" + port() + path);
    }

    public void serve(String path, Answer answer) {
        answers.put(path, answer);
    }

    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    public long hits(String path) {
        return seen.stream().filter(request -> request.path().equals(path)).count();
    }

    public void forget() {
        answers.clear();
        seen.clear();
    }

    public void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        seen.add(new Seen(path, Map.copyOf(exchange.getRequestHeaders())));
        Answer answer = answers.getOrDefault(path, Answer.status(404));
        try {
            Thread.sleep(answer.delayMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        try {
            if (answer.body().length == 0) {
                exchange.sendResponseHeaders(answer.status(), -1);
            } else {
                exchange.sendResponseHeaders(answer.status(), answer.body().length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(answer.body());
                }
            }
        } catch (IOException gone) {
            // The fetcher gave up on us, as some tests want it to.
        } finally {
            exchange.close();
        }
    }
}
