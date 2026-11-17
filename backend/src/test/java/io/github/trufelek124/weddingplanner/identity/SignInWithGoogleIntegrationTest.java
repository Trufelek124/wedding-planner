package io.github.trufelek124.weddingplanner.identity;

import static io.github.trufelek124.weddingplanner.TestHttp.FROM_A_BROWSER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestGoogle;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignInWithGoogleIntegrationTest extends PostgresIntegrationTest {

    private static final String STRANGER = "stranger@example.org";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anOwnerReceivesASessionThatWorks() throws Exception {
        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken(ANNA).name("Anna").build(), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> session = TestHttp.json(response);
        assertThat(session).containsOnlyKeys("accessToken", "accessTokenExpiresAt", "refreshToken", "user");
        assertThat(Instant.parse((String) session.get("accessTokenExpiresAt")))
                .isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(16 * 60));
        assertThat(session.get("user")).isEqualTo(Map.of("id", TestSignIn.tokens(response).userId(), "displayName", "Anna", "email", ANNA));

        HttpResponse<String> me = http().getAs((String) session.get("accessToken"), "/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(me)).isEqualTo(session.get("user"));
    }

    @Test
    void theAllowListDoesNotCareHowAnAddressIsCapitalised() throws Exception {
        // The configuration says "Piotr@Example.com", Google says "PIOTR@example.com".
        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken("PIOTR@example.com").build(), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"email\":\"" + PIOTR + "\"");
    }

    @Test
    void signingInAgainIsTheSamePerson() throws Exception {
        String first = TestSignIn.signIn(http(), ANNA).userId();
        String second = TestSignIn.signIn(http(), ANNA).userId();

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, ANNA)).isEqualTo(1);
    }

    @Test
    void aPhoneAppGetsItsRefreshTokenInTheAnswerAndNoCookie() throws Exception {
        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken(ANNA).build(), Map.of());

        assertThat(TestSignIn.tokens(response).refreshToken()).hasSizeGreaterThan(40);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void aBrowserGetsItsRefreshTokenInACookieThatScriptsCannotRead() throws Exception {
        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken(ANNA).build(), FROM_A_BROWSER);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(response)).containsEntry("refreshToken", null);
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(1);
        assertThat(cookies.get(0))
                .startsWith("wp_refresh=")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth")
                .contains("Max-Age=5184000");
        assertThat(response.body()).doesNotContain(cookies.get(0).substring("wp_refresh=".length(), 30));
    }

    @Test
    void behindTheHttpsProxyTheCookieIsForHttpsOnly() throws Exception {
        // In production a proxy terminates TLS and tells the application so with this header.
        Map<String, String> headers = Map.of("Origin", "https://planner.example", "X-Forwarded-Proto", "https");

        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken(ANNA).build(), headers);

        assertThat(response.headers().firstValue("Set-Cookie").orElse("")).contains("Secure").contains("HttpOnly");
    }

    @Test
    void someoneWhoIsNotAnOwnerIsRefusedAndLeavesNoTrace() throws Exception {
        HttpResponse<String> response = TestSignIn.withGoogle(http(), TestGoogle.idToken(STRANGER).build(), Map.of());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(TestHttp.json(response))
                .containsEntry("type", "urn:wedding-planner:problem:account-not-allowed")
                .containsEntry("status", 403);
        assertThat(response.body()).doesNotContain("@").doesNotContain("example");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Integer.class, STRANGER)).isZero();
    }

    Stream<Arguments> tokensThatProveNothing() {
        return Stream.of(
                Arguments.of("issued for another app", TestGoogle.idToken(ANNA).audience("another-app.apps.googleusercontent.com").build()),
                Arguments.of("issued by someone else", TestGoogle.idToken(ANNA).issuer("https://accounts.example.org").build()),
                Arguments.of("expired", TestGoogle.idToken(ANNA).expiresAt(Instant.now().minusSeconds(600)).build()),
                Arguments.of("address not verified by Google", TestGoogle.idToken(ANNA).claim("email_verified", false).build()),
                Arguments.of("no address", TestGoogle.idToken(ANNA).claim("email", null).build()),
                Arguments.of("signed with a key Google does not have", TestGoogle.idToken(ANNA).signedByAStranger().build()),
                Arguments.of("unsigned", unsigned(ANNA)),
                Arguments.of("not a token", "anna@example.com"),
                Arguments.of("tampered with", tampered(TestGoogle.idToken(STRANGER).build())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensThatProveNothing")
    void aTokenThatProvesNothingSignsNobodyIn(String why, String idToken) throws Exception {
        HttpResponse<String> response = TestSignIn.withGoogle(http(), idToken, Map.of());

        assertThat(response.statusCode()).as(why).isEqualTo(401);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:unauthenticated");
        assertThat(response.body()).doesNotContain("accessToken");
    }

    @Test
    void aRequestWithoutATokenIsABadRequest() throws Exception {
        HttpResponse<String> response = http().postJson("/api/auth/google", "{\"idToken\":\" \"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:bad-request");
    }

    @Test
    void tellsClientsWhichGoogleClientToUse() throws Exception {
        HttpResponse<String> response = http().get("/api/auth/config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(response))
                .isEqualTo(Map.of("googleClientId", TestGoogle.CLIENT_ID, "devLoginEnabled", false));
    }

    /** Header says "no signature needed"; a careless verifier accepts that. */
    private static String unsigned(String email) {
        String[] parts = TestGoogle.idToken(email).build().split("\\.");
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        return header + "." + parts[1] + ".";
    }

    /** A genuine token for a stranger whose payload was swapped for an owner's. */
    private static String tampered(String strangersToken) {
        String[] forged = strangersToken.split("\\.");
        String[] owners = TestGoogle.idToken(ANNA).signedByAStranger().build().split("\\.");
        return forged[0] + "." + owners[1] + "." + forged[2];
    }

    private TestHttp http() {
        return new TestHttp(port);
    }
}
