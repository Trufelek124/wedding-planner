package io.github.trufelek124.weddingplanner.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class AccessControlIntegrationTest extends PostgresIntegrationTest {

    /** The API paths that answer without a sign-in. A new entry here is a security decision. */
    private static final List<String> PUBLIC = List.of("/api/auth/", "/api/app-info", "/api/mail/gmail/return");

    @Value("${local.server.port}")
    int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyApiEndpointThatIsNotPublicRefusesACallerWithoutAToken() throws Exception {
        List<String> checked = new ArrayList<>();
        for (var mapping : mappings.getHandlerMethods().keySet()) {
            for (String pattern : mapping.getPatternValues()) {
                if (!pattern.startsWith("/api/") || PUBLIC.stream().anyMatch(pattern::startsWith)) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                var methods = mapping.getMethodsCondition().getMethods();
                for (RequestMethod method : methods.isEmpty() ? List.of(RequestMethod.GET) : methods) {
                    HttpResponse<String> response = http().send(method.name(), path);
                    assertThat(response.statusCode()).as(method + " " + pattern).isEqualTo(401);
                    checked.add(method + " " + pattern);
                }
            }
        }
        assertThat(checked).as("the protected endpoints that were checked").contains("GET /api/me");
    }

    @Test
    void aRefusalIsAProblemThatTellsTheClientToSignIn() throws Exception {
        HttpResponse<String> response = http().get("/api/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).startsWith("Bearer");
        assertThat(TestHttp.json(response))
                .containsEntry("type", "urn:wedding-planner:problem:unauthenticated")
                .containsEntry("status", 401)
                .containsEntry("instance", "/api/me");
    }

    @Test
    void anApiPathThatDoesNotExistAlsoNeedsASignIn() throws Exception {
        // Otherwise the difference between 401 and 404 would tell a stranger which paths exist.
        assertThat(http().get("/api/no-such-endpoint").statusCode()).isEqualTo(401);
    }

    @Test
    void whatIsNotTheApiStaysOpen() throws Exception {
        assertThat(http().get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(http().get("/api/app-info").statusCode()).isEqualTo(200);
        assertThat(http().get("/api/auth/config").statusCode()).isEqualTo(200);
    }

    @Test
    void aValidTokenOpensTheApi() throws Exception {
        String accessToken = TestSignIn.signIn(http(), ANNA).accessToken();

        assertThat(http().getAs(accessToken, "/api/me").statusCode()).isEqualTo(200);
    }

    @Test
    void anAccessTokenStopsWorkingAfterFifteenMinutes() throws Exception {
        String accessToken = TestSignIn.signIn(http(), ANNA).accessToken();

        TestClock.advance(Duration.ofMinutes(14));
        assertThat(http().getAs(accessToken, "/api/me").statusCode()).isEqualTo(200);

        TestClock.advance(Duration.ofMinutes(2));
        assertThat(http().getAs(accessToken, "/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    void tokensNobodyIssuedOpenNothing() throws Exception {
        String userId = TestSignIn.signIn(http(), ANNA).userId();
        String real = TestSignIn.signIn(http(), ANNA).accessToken();
        String[] parts = real.split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());

        assertThat(http().getAs("not-a-token", "/api/me").statusCode()).isEqualTo(401);
        assertThat(http().getAs(signedWith("another-secret-of-sufficient-length-0123456789", userId, "wedding-planner"), "/api/me").statusCode())
                .as("signed with another secret").isEqualTo(401);
        assertThat(http().getAs(signedWith("a-test-secret-that-is-long-enough-0123456789", userId, "someone-else"), "/api/me").statusCode())
                .as("right secret, wrong issuer").isEqualTo(401);
        assertThat(http().getAs(noneHeader + "." + parts[1] + ".", "/api/me").statusCode())
                .as("unsigned").isEqualTo(401);
        assertThat(http().getAs(parts[0] + "." + parts[1] + "." + parts[2].substring(1) + "A", "/api/me").statusCode())
                .as("signature altered").isEqualTo(401);
    }

    @Test
    void aTokenForSomeoneWhoDoesNotExistOpensNothing() throws Exception {
        String ghost = signedWith("a-test-secret-that-is-long-enough-0123456789", UUID.randomUUID().toString(), "wedding-planner");

        assertThat(http().getAs(ghost, "/api/me").statusCode()).isEqualTo(401);
    }

    private static String signedWith(String secret, String subject, String issuer) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(subject)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(600)))
                        .build());
        jwt.sign(new MACSigner(secret.getBytes()));
        return jwt.serialize();
    }

    private TestHttp http() {
        return new TestHttp(port);
    }
}
