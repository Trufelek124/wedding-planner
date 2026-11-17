package io.github.trufelek124.weddingplanner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP calls against the application under test, as a real client makes them. */
public final class TestHttp {

    /** What a browser adds to a POST; the application tells browsers from other clients by it. */
    public static final Map<String, String> FROM_A_BROWSER = Map.of("Origin", "http://localhost");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String base;

    public TestHttp(int port) {
        this.base = "http://localhost:" + port;
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, Map.of());
    }

    public HttpResponse<String> get(String path, Map<String, String> headers) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), headers);
    }

    public HttpResponse<String> getAs(String accessToken, String path) throws IOException, InterruptedException {
        return get(path, Map.of("Authorization", "Bearer " + accessToken));
    }

    public HttpResponse<String> postJson(String path, String body) throws IOException, InterruptedException {
        return postJson(path, body, Map.of());
    }

    public HttpResponse<String> postJson(String path, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return send(request, headers);
    }

    public HttpResponse<String> postJsonAs(String accessToken, String path, String body)
            throws IOException, InterruptedException {
        return postJson(path, body, Map.of("Authorization", "Bearer " + accessToken));
    }

    public HttpResponse<String> putJsonAs(String accessToken, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        return send(request, Map.of("Authorization", "Bearer " + accessToken));
    }

    public HttpResponse<String> deleteAs(String accessToken, String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).DELETE(), Map.of("Authorization", "Bearer " + accessToken));
    }

    /** A POST without a body, as a browser makes when its cookie says everything. */
    public HttpResponse<String> post(String path, Map<String, String> headers) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.noBody()), headers);
    }

    public HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}"));
        return send(request, Map.of());
    }

    private HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> headers)
            throws IOException, InterruptedException {
        headers.forEach(request::header);
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> json(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> jsonList(HttpResponse<String> response) {
        return JSON.readValue(response.body(), List.class);
    }

    /** The fields a 400 answer complains about, in the order sent. */
    @SuppressWarnings("unchecked")
    public static List<String> fieldProblems(HttpResponse<String> response) {
        Map<String, Object> problem = json(response);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getOrDefault("fieldProblems", List.of());
        return fields.stream().map(field -> (String) field.get("field")).toList();
    }

    public static String jsonOf(Map<String, ?> value) {
        return JSON.writeValueAsString(value);
    }
}
