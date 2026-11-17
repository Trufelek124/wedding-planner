package io.github.trufelek124.weddingplanner.identity;

import static io.github.trufelek124.weddingplanner.TestHttp.FROM_A_BROWSER;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestGoogle;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import io.github.trufelek124.weddingplanner.TestSignIn.Tokens;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

class RefreshIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aRefreshTokenBuysANewSessionForTheSamePerson() throws Exception {
        Tokens first = TestSignIn.signIn(http(), ANNA);

        HttpResponse<String> response = refresh(first.refreshToken());

        assertThat(response.statusCode()).isEqualTo(200);
        Tokens second = TestSignIn.tokens(response);
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(http().getAs(second.accessToken(), "/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void aSessionOutlivesItsAccessTokenThroughRefreshing() throws Exception {
        Tokens tokens = TestSignIn.signIn(http(), ANNA);
        TestClock.advance(Duration.ofMinutes(16));
        assertThat(http().getAs(tokens.accessToken(), "/api/me").statusCode()).isEqualTo(401);

        Tokens renewed = TestSignIn.tokens(refresh(tokens.refreshToken()));

        assertThat(http().getAs(renewed.accessToken(), "/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void aRefreshTokenUsedASecondTimeLaterEndsTheWholeSession() throws Exception {
        Tokens first = TestSignIn.signIn(http(), ANNA);
        Tokens second = TestSignIn.tokens(refresh(first.refreshToken()));

        // Someone presents the old token again, long after it was replaced: one of the two holders stole it.
        TestClock.advance(Duration.ofMinutes(5));
        HttpResponse<String> replay = refresh(first.refreshToken());

        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(TestHttp.json(replay)).containsEntry("type", "urn:wedding-planner:problem:unauthenticated");
        assertThat(refresh(second.refreshToken()).statusCode()).as("the successor is revoked too").isEqualTo(401);
    }

    @Test
    void twoRequestsAtTheSameMomentMayUseTheSameToken() throws Exception {
        // Two browser tabs wake up together and both refresh with the token they share.
        Tokens tokens = TestSignIn.signIn(http(), ANNA);

        HttpResponse<String> oneTab = refresh(tokens.refreshToken());
        TestClock.advance(Duration.ofSeconds(5));
        HttpResponse<String> otherTab = refresh(tokens.refreshToken());

        assertThat(oneTab.statusCode()).isEqualTo(200);
        assertThat(otherTab.statusCode()).isEqualTo(200);
        assertThat(refresh(TestSignIn.tokens(otherTab).refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void aRefreshTokenExpiresAfterSixtyDays() throws Exception {
        Tokens tokens = TestSignIn.signIn(http(), ANNA);

        TestClock.advance(Duration.ofDays(61));

        assertThat(refresh(tokens.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void usingTheSessionKeepsItAlive() throws Exception {
        Tokens tokens = TestSignIn.signIn(http(), ANNA);
        for (int week = 0; week < 12; week++) {
            TestClock.advance(Duration.ofDays(7));
            HttpResponse<String> response = refresh(tokens.refreshToken());
            assertThat(response.statusCode()).as("week " + week).isEqualTo(200);
            tokens = TestSignIn.tokens(response);
        }
    }

    @Test
    void whatIsNotARefreshTokenBuysNothing() throws Exception {
        String accessToken = TestSignIn.signIn(http(), ANNA).accessToken();

        assertThat(refresh("made-up").statusCode()).isEqualTo(401);
        assertThat(refresh(accessToken).statusCode()).as("an access token is not a refresh token").isEqualTo(401);
        assertThat(http().postJson("/api/auth/refresh", "{}").statusCode()).isEqualTo(401);
        assertThat(http().post("/api/auth/refresh", Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        Tokens tokens = TestSignIn.signIn(http(), ANNA);
        Tokens renewed = TestSignIn.tokens(refresh(tokens.refreshToken()));

        HttpResponse<String> signOut = http().postJson("/api/auth/sign-out", TestHttp.jsonOf(Map.of("refreshToken", renewed.refreshToken())));

        assertThat(signOut.statusCode()).isEqualTo(204);
        assertThat(refresh(renewed.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    void signingOutOneDeviceLeavesTheOtherSignedIn() throws Exception {
        Tokens phone = TestSignIn.signIn(http(), ANNA);
        Tokens tablet = TestSignIn.signIn(http(), ANNA);

        http().postJson("/api/auth/sign-out", TestHttp.jsonOf(Map.of("refreshToken", phone.refreshToken())));

        assertThat(refresh(phone.refreshToken()).statusCode()).isEqualTo(401);
        assertThat(refresh(tablet.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void signingOutWithNothingToSignOutIsNotAnError() throws Exception {
        assertThat(http().postJson("/api/auth/sign-out", "{\"refreshToken\":\"made-up\"}").statusCode()).isEqualTo(204);
        assertThat(http().post("/api/auth/sign-out", Map.of()).statusCode()).isEqualTo(204);
    }

    @Test
    void aBrowserRefreshesAndSignsOutWithItsCookieAlone() throws Exception {
        HttpResponse<String> signIn = TestSignIn.withGoogle(http(), TestGoogle.idToken(PIOTR).build(), FROM_A_BROWSER);
        String cookie = cookieOf(signIn);

        HttpResponse<String> refreshed = http().post("/api/auth/refresh", withCookie(cookie));

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(refreshed)).containsEntry("refreshToken", null);
        String rotated = cookieOf(refreshed);
        assertThat(rotated).isNotEqualTo(cookie);

        HttpResponse<String> signOut = http().post("/api/auth/sign-out", withCookie(rotated));

        assertThat(signOut.statusCode()).isEqualTo(204);
        assertThat(signOut.headers().firstValue("Set-Cookie").orElse("")).startsWith("wp_refresh=;").contains("Max-Age=0");
        assertThat(http().post("/api/auth/refresh", withCookie(rotated)).statusCode()).isEqualTo(401);
    }

    @Test
    void onlyHashesOfRefreshTokensAreStored() throws Exception {
        Tokens tokens = TestSignIn.signIn(http(), ANNA);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_token WHERE token_hash = ?", Integer.class, tokens.refreshToken()))
                .isZero();
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_token", String.class))
                .isNotEmpty()
                .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return http().postJson("/api/auth/refresh", TestHttp.jsonOf(Map.of("refreshToken", refreshToken)));
    }

    private static String cookieOf(HttpResponse<String> response) {
        String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }

    private static Map<String, String> withCookie(String cookie) {
        Map<String, String> headers = new HashMap<>(FROM_A_BROWSER);
        headers.put("Cookie", cookie);
        return headers;
    }

    private TestHttp http() {
        return new TestHttp(port);
    }
}
