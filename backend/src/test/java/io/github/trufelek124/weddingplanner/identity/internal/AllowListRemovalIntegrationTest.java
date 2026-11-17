package io.github.trufelek124.weddingplanner.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

/** An address that was an owner's and is taken off the list: this server no longer has it on the list. */
@TestPropertySource(properties = "ALLOWED_EMAILS=someone.else@example.com")
class AllowListRemovalIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokens refreshTokens;

    @Autowired
    Clock clock;

    @Test
    void aSessionFromBeforeTheRemovalCannotBeRefreshed() throws Exception {
        UserRepository.User former = users.findByEmail("former.owner@example.com")
                .orElseGet(() -> users.create("former.owner@example.com", "google-former", "Former", clock.instant()));
        String refreshToken = refreshTokens.issue(former.id());

        HttpResponse<String> response =
                new TestHttp(port).postJson("/api/auth/refresh", TestHttp.jsonOf(Map.of("refreshToken", refreshToken)));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("accessToken");
    }
}
