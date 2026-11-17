package io.github.trufelek124.weddingplanner.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class ApiDocsExposureTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void theApiDescriptionIsNotServedUnlessSwitchedOn() throws Exception {
        assertThat(new TestHttp(port).get("/v3/api-docs").statusCode()).isEqualTo(404);
    }
}
