package io.github.trufelek124.weddingplanner.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestGoogle;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import io.github.trufelek124.weddingplanner.TestSignIn.Tokens;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/** Everything a sign-in can go through, with the most talkative logging, must leave no address, name or token in the log. */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.security=DEBUG",
    "logging.level.org.springframework.web=DEBUG"
})
class NoPersonalDataInLogsIntegrationTest extends PostgresIntegrationTest {

    private static final String STRANGER = "nosy.stranger@example.org";

    @Value("${local.server.port}")
    int port;

    @Test
    void signingInRefusalsRefreshingAndSigningOutLogNoAddressNameOrToken(CapturedOutput output) throws Exception {
        TestHttp http = new TestHttp(port);
        String idToken = TestGoogle.idToken(ANNA).name("Anna Kowalska").build();
        Tokens session = TestSignIn.tokens(TestSignIn.withGoogle(http, idToken, Map.of()));
        String strangersIdToken = TestGoogle.idToken(STRANGER).name("Nosy Stranger").build();
        TestSignIn.withGoogle(http, strangersIdToken, Map.of());
        TestSignIn.withGoogle(http, TestGoogle.idToken(ANNA).signedByAStranger().build(), Map.of());
        http.getAs(session.accessToken(), "/api/me");
        http.getAs("forged." + session.accessToken(), "/api/me");
        Tokens renewed = TestSignIn.tokens(http.postJson("/api/auth/refresh", TestHttp.jsonOf(Map.of("refreshToken", session.refreshToken()))));
        TestClock.advance(Duration.ofMinutes(5));
        http.postJson("/api/auth/refresh", TestHttp.jsonOf(Map.of("refreshToken", session.refreshToken())));
        http.postJsonAs(renewed.accessToken(), "/api/wedding", "{\"date\":\"2027-06-19\",\"currency\":\"PLN\","
                + "\"timeZone\":\"Europe/Warsaw\",\"yourName\":\"Anusia\",\"partnerName\":\"Piotrus\"}");
        http.getAs(renewed.accessToken(), "/api/wedding");
        http.postJson("/api/auth/sign-out", TestHttp.jsonOf(Map.of("refreshToken", renewed.refreshToken())));

        String log = output.getAll();
        assertThat(log).as("the log was captured").contains("signed in with Google").contains("allow-list");
        assertThat(log)
                .doesNotContain(ANNA)
                .doesNotContain("anna@")
                .doesNotContain(STRANGER)
                .doesNotContain("nosy.stranger")
                .doesNotContain("Kowalska")
                .doesNotContain("Nosy Stranger")
                .doesNotContain("Anusia")
                .doesNotContain("Piotrus")
                .doesNotContain(idToken)
                .doesNotContain(strangersIdToken)
                .doesNotContain(session.accessToken())
                .doesNotContain(session.refreshToken())
                .doesNotContain(renewed.accessToken())
                .doesNotContain(renewed.refreshToken())
                .doesNotContain("a-test-secret-that-is-long-enough");
    }
}
