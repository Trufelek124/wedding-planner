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

/**
 * The format of a file of tasks is published (S06): its schema and its example are files of the web app, answered
 * from the web directory without a sign-in. The directory here holds the repository's own two files, as the build
 * puts them.
 */
class FormatsAreServedIntegrationTest extends PostgresIntegrationTest {

    private static final String SCHEMA = "wedding-planner-tasks-1.schema.json";
    private static final String EXAMPLE = "wedding-planner-tasks-1.example.json";
    private static final Path FORMATS = Path.of("..", "web", "public", "formats");
    private static final String PAGE = "<!doctype html><title>the web app's page</title>";
    private static final Path WEB_DIR = webDirectory();

    @Value("${local.server.port}")
    int port;

    private static Path webDirectory() {
        try {
            Path web = Files.createDirectories(Files.createTempDirectory("formats-test").resolve("web"));
            Files.writeString(web.resolve("index.html"), PAGE);
            Path formats = Files.createDirectories(web.resolve("formats"));
            Files.copy(FORMATS.resolve(SCHEMA), formats.resolve(SCHEMA));
            Files.copy(FORMATS.resolve(EXAMPLE), formats.resolve(EXAMPLE));
            return web;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void webDir(DynamicPropertyRegistry registry) {
        registry.add("APP_WEB_DIR", WEB_DIR::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {SCHEMA, EXAMPLE})
    void bothFilesAreAnsweredWithoutASignInAsJsonAndAreTheRepositorysOwn(String name) throws Exception {
        HttpResponse<String> response = get("/formats/" + name);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body()).isEqualTo(Files.readString(FORMATS.resolve(name)));
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void theSchemaNamesTheFormatItDescribes() throws Exception {
        assertThat(get("/formats/" + SCHEMA).body()).contains("\"urn:wedding-planner:tasks-file:1\"").contains("wedding-planner-tasks");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/formats/wedding-planner-tasks-2.schema.json", "/formats/wedding-planner-tasks-1.schema", "/formats/nothing.json",
        "/formats/../index.html.json", "/formats/%2e%2e/secret.json"
    })
    void aPathBesideThemThatIsNoFileIsNotAnsweredWithOneOfThem(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.body()).doesNotContain("urn:wedding-planner:tasks-file").doesNotContain("\"format\": \"wedding-planner-tasks\"");
        assertThat(response.statusCode()).isIn(400, 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/formats", "/formats/", "/formats/other"})
    void aPathWithoutAFileNameIsAPageOfTheWebAppAtMostNeverAFormatFile(String path) throws Exception {
        HttpResponse<String> response = get(path);

        assertThat(response.body()).doesNotContain("urn:wedding-planner:tasks-file").doesNotContain("\"format\": \"wedding-planner-tasks\"");
        assertThat(response.statusCode()).isIn(200, 404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
