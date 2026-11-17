package io.github.trufelek124.weddingplanner.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

class DevLoginIntegrationTest {

    private static final String SIGN_IN_AS_ANNA = "{\"email\":\"" + PostgresIntegrationTest.ANNA + "\"}";

    @Nested
    class AsShipped extends PostgresIntegrationTest {

        @Value("${local.server.port}")
        int port;

        @Test
        void thereIsNoDevelopmentLogin() throws Exception {
            HttpResponse<String> response = new TestHttp(port).postJson("/api/auth/dev", SIGN_IN_AS_ANNA);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).doesNotContain("accessToken");
        }

        @Test
        void clientsAreToldThereIsNone() throws Exception {
            assertThat(new TestHttp(port).get("/api/auth/config").body()).contains("\"devLoginEnabled\":false");
        }
    }

    @Nested
    @TestPropertySource(properties = "DEV_LOGIN_ENABLED=true")
    class SwitchedOn extends PostgresIntegrationTest {

        @Value("${local.server.port}")
        int port;

        @Test
        void anOwnerSignsInWithoutGoogle() throws Exception {
            HttpResponse<String> response = new TestHttp(port).postJson("/api/auth/dev", SIGN_IN_AS_ANNA);

            assertThat(response.statusCode()).isEqualTo(200);
            String accessToken = TestSignIn.tokens(response).accessToken();
            assertThat(new TestHttp(port).getAs(accessToken, "/api/me").body()).contains("\"email\":\"" + ANNA + "\"");
        }

        @Test
        void itIsTheSamePersonAsWithGoogle() throws Exception {
            String withGoogle = TestSignIn.signIn(new TestHttp(port), ANNA).userId();
            String withDevLogin = TestSignIn.tokens(new TestHttp(port).postJson("/api/auth/dev", SIGN_IN_AS_ANNA)).userId();

            assertThat(withDevLogin).isEqualTo(withGoogle);
        }

        @Test
        void theAllowListStillApplies() throws Exception {
            HttpResponse<String> response =
                    new TestHttp(port).postJson("/api/auth/dev", "{\"email\":\"stranger@example.org\"}");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:account-not-allowed");
        }

        @Test
        void whatIsNotAnAddressIsABadRequest() throws Exception {
            assertThat(new TestHttp(port).postJson("/api/auth/dev", "{\"email\":\"not an address\"}").statusCode())
                    .isEqualTo(400);
        }

        @Test
        void clientsAreToldItIsAvailable() throws Exception {
            assertThat(new TestHttp(port).get("/api/auth/config").body()).contains("\"devLoginEnabled\":true");
        }
    }
}
