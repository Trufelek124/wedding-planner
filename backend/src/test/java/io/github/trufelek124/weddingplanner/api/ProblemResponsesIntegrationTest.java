package io.github.trufelek124.weddingplanner.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Import(ProblemResponsesIntegrationTest.FailingEndpoints.class)
class ProblemResponsesIntegrationTest extends PostgresIntegrationTest {

    private static final String SECRET = "the database password is hunter2";

    @RestController
    static class FailingEndpoints {

        record Wish(String text) {}

        @GetMapping("/api/test/known")
        String known() {
            throw new ApiException(ProblemCode.NOT_FOUND, "There is no such plan item.");
        }

        @GetMapping("/api/test/unexpected")
        String unexpected() {
            throw new IllegalStateException(SECRET);
        }

        @PostMapping("/api/test/wishes")
        Wish wish(@RequestBody Wish wish) {
            return wish;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void anErrorAModuleRaisesKeepsItsCodeAndItsDetail() throws Exception {
        HttpResponse<String> response = http().getAs(accessToken(), "/api/test/known");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(problem(response))
                .containsEntry("type", "urn:wedding-planner:problem:not-found")
                .containsEntry("title", "Not found")
                .containsEntry("status", 404)
                .containsEntry("detail", "There is no such plan item.")
                .containsEntry("instance", "/api/test/known");
    }

    @Test
    void anUnexpectedErrorTellsTheClientNothingAboutItsCause() throws Exception {
        HttpResponse<String> response = http().getAs(accessToken(), "/api/test/unexpected");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("hunter2").doesNotContain("IllegalStateException");
        assertThat(problem(response))
                .containsEntry("type", "urn:wedding-planner:problem:internal-error")
                .containsEntry("status", 500)
                .doesNotContainKey("detail");
    }

    @Test
    void anUnknownApiPathIsNotFound() throws Exception {
        HttpResponse<String> response = http().getAs(accessToken(), "/api/no-such-endpoint");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(problem(response)).containsEntry("type", "urn:wedding-planner:problem:not-found");
    }

    @Test
    void theWrongMethodIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = http().postJson("/api/app-info", "{}");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(problem(response)).containsEntry("type", "urn:wedding-planner:problem:method-not-allowed");
    }

    @Test
    void aBodyThatIsNotJsonIsABadRequest() throws Exception {
        HttpResponse<String> response =
                http().postJson("/api/test/wishes", "{ not json", Map.of("Authorization", "Bearer " + accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(problem(response)).containsEntry("type", "urn:wedding-planner:problem:bad-request");
        assertThat(response.body()).doesNotContain("Exception");
    }

    private String accessToken() throws Exception {
        return TestSignIn.signIn(http(), ANNA).accessToken();
    }

    private TestHttp http() {
        return new TestHttp(port);
    }

    private static Map<String, Object> problem(HttpResponse<String> response) {
        return TestHttp.json(response);
    }
}
