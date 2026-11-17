package io.github.trufelek124.weddingplanner.webapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class WebAppIntegrationTest extends PostgresIntegrationTest {

    private static final String PAGE = "<!doctype html><title>the web app's page</title>";
    private static final String SECRET = "a file outside the web directory";
    private static final Path WEB_DIR = webDirectory();

    @Value("${local.server.port}")
    int port;

    private static Path webDirectory() {
        try {
            Path parent = Files.createTempDirectory("webapp-test");
            Path web = Files.createDirectories(parent.resolve("web"));
            Files.writeString(web.resolve("index.html"), PAGE);
            Files.createDirectories(web.resolve("assets"));
            Files.writeString(web.resolve("assets/index-abc123.js"), "console.log('app')");
            Files.writeString(parent.resolve("secret.txt"), SECRET);
            return web;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void webDir(DynamicPropertyRegistry registry) {
        registry.add("APP_WEB_DIR", WEB_DIR::toString);
    }

    @Test
    void servesThePageAtTheRootAndNeverLetsItBeCached() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PAGE);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/plan", "/plan/12", "/guests/households/3/", "/inspirations?board=dress"})
    void answersAPageOfTheWebAppWithThePage(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PAGE);
    }

    @Test
    void servesBuiltFilesAndLetsThemBeCachedForAYear() throws Exception {
        HttpResponse<String> response = get("/assets/index-abc123.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("console.log('app')");
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("javascript");
        assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                .contains("max-age=31536000")
                .contains("immutable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/missing.js", "/missing.png", "/favicon.ico", "/actuator/no-such-endpoint"})
    void neverAnswersAMissingFileWithThePage(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("the web app's page");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/", "/api/no-such-endpoint", "/api/plan/12"})
    void neverAnswersAnApiPathWithThePage(String path) throws Exception {
        // Without a sign-in an API path answers 401 whether it exists or not; never the page.
        HttpResponse<String> response = get(path);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("the web app's page");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/../secret.txt", "/assets/../../secret.txt", "/%2e%2e/secret.txt", "/assets/%2e%2e/%2e%2e/secret.txt",
        "/..%2fsecret.txt", "/assets/..%5c..%5csecret.txt"
    })
    void neverServesAFileOutsideTheWebDirectory(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.body()).doesNotContain(SECRET);
        assertThat(response.statusCode()).isIn(400, 404);
    }

    @Test
    void healthStillAnswers() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
