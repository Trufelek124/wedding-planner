package io.github.trufelek124.weddingplanner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Plain HTTP calls against the application under test, as a real client makes them. */
public final class TestHttp {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private final String base;

    public TestHttp(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
