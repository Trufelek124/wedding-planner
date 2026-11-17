package io.github.trufelek124.weddingplanner.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * api/openapi.json is the contract both clients generate their types from. This test fails
 * when the code and the committed file disagree, so an endpoint cannot change unnoticed.
 * To accept a change: ./gradlew updateApiContract, then regenerate the clients.
 */
// The development login is switched on here so that its endpoint is part of the description.
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "DEV_LOGIN_ENABLED=true"})
class ApiContractTest extends PostgresIntegrationTest {

    private static final Path CONTRACT = Path.of("api", "openapi.json");

    @Value("${local.server.port}")
    int port;

    @Test
    void theCommittedApiDescriptionMatchesTheCode() throws Exception {
        String generated = stable(new TestHttp(port).get("/v3/api-docs").body());

        if (Boolean.getBoolean("api.contract.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, generated);
            return;
        }

        assertThat(CONTRACT)
                .as("backend/api/openapi.json is missing: run ./gradlew updateApiContract")
                .exists();
        assertThat(generated)
                .as("The API changed. Run ./gradlew updateApiContract, regenerate the clients, commit all of it.")
                .isEqualTo(Files.readString(CONTRACT));
    }

    /** The same description always gives the same text: keys sorted, fixed indentation, final newline. */
    private static String stable(String json) {
        JsonMapper mapper = JsonMapper.builder()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .build();
        return mapper.writeValueAsString(mapper.readValue(json, Object.class)).replace("\r\n", "\n") + "\n";
    }
}
