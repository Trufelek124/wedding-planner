package io.github.trufelek124.weddingplanner.webapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientRouteFallbackResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "plan", "plan/12", "guests/households/3/", "apidocs", "api-keys", "actuators"})
    void pagesOfTheWebApp(String path) {
        assertThat(ClientRouteFallbackResolver.isClientRoute(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "api", "api/", "api/plan/12", "actuator", "actuator/health", "missing.png", "assets/missing.js", "plan/v1.2"
    })
    void neverAPageOfTheWebApp(String path) {
        assertThat(ClientRouteFallbackResolver.isClientRoute(path)).isFalse();
    }
}
