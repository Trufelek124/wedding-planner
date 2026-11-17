package io.github.trufelek124.weddingplanner.appinfo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class AppInfoIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void tellsAnyoneTheServersNameAndVersion() throws Exception {
        HttpResponse<String> response = new TestHttp(port).get("/api/app-info");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"name\":\"wedding-planner\",\"version\":\"0.1.0\"}");
    }
}
