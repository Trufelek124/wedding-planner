package io.github.trufelek124.weddingplanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

class StartupIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HealthEndpoint health;

    @Test
    void runsOnPostgres18() {
        Integer version = jdbc.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class);

        assertThat(version / 10000).isEqualTo(18);
    }

    @Test
    void appliesTheMigrationsAtStartup() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    void healthAnswers200WhenTheDatabaseIsReachable() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthDependsOnTheDatabase() {
        // Spring Boot answers 503 as soon as one health component is not UP,
        // so the database being one of them is what ties health to the database.
        assertThat(health.healthForPath("db")).isNotNull();
        assertThat(health.healthForPath("db").getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthDoesNotListItsComponentsToAnonymousCallers() throws Exception {
        assertThat(get("/actuator/health").body()).doesNotContain("components").doesNotContain("PostgreSQL");
    }

    @Test
    void onlyHealthAndInfoAreExposed() throws Exception {
        assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/beans").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
