package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The server as it starts when the e-mail import was not given what it needs (here: no client secret, no public
 * address, no key). The sign-in's client id is there, as in the other tests. Nothing is asked of Google, and the
 * addresses it would be are the stand-in's, so that a call would show.
 */
class MailNotSetUpIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final StandInGoogle google = StandInGoogle.get();
    TestWedding wedding;

    @DynamicPropertySource
    static void standInForGoogle(DynamicPropertyRegistry registry) {
        StandInGoogle google = StandInGoogle.get();
        registry.add("app.mail.authorization-uri", google::authorizationUri);
        registry.add("app.mail.token-uri", google::tokenUri);
        registry.add("app.mail.revoke-uri", google::revokeUri);
        registry.add("app.mail.gmail-uri", google::gmailUri);
    }

    @BeforeEach
    void aWeddingAndAQuietGoogle() throws Exception {
        google.reset();
        wedding = TestWedding.fresh(jdbc, port);
        // What the reading used is the server's, not a wedding's: another class's calls must not be counted here,
        // in whichever order the classes run.
        jdbc.update("DELETE FROM reading_usage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theServerStartedAndSaysTheImportIsNotSetUp() throws Exception {
        HttpResponse<String> response = wedding.http.getAs(wedding.anna, "/api/mail");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> state = TestHttp.json(response);
        assertThat(state).containsEntry("setUp", false);
        assertThat((Map<String, Object>) state.get("yours")).containsEntry("state", "NOT_CONNECTED").containsEntry("address", null);
        assertThat((Map<String, Object>) state.get("partner")).containsEntry("joined", false).containsEntry("state", "NOT_CONNECTED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReadingIsOffAndEveryNumberIsThereAsZero() throws Exception {
        Map<String, Object> state = TestHttp.json(wedding.http.getAs(wedding.piotr, "/api/mail"));

        assertThat((Map<String, Object>) state.get("reading")).containsOnly(
                Map.entry("state", "OFF"), Map.entry("waiting", 0), Map.entry("read", 0), Map.entry("aboutTheWedding", 0),
                Map.entry("couldNotBeRead", 0), Map.entry("suggestions", 0), Map.entry("costThisMonthCents", 0),
                Map.entry("costInAllCents", 0), Map.entry("monthlyLimitCents", 2000));
    }

    @Test
    void theOtherCallsAnswerWithTheProblemThatSaysSo() throws Exception {
        HttpResponse<String> connect = wedding.http.post("/api/mail/gmail/connection", Map.of("Authorization", "Bearer " + wedding.anna));
        HttpResponse<String> disconnect = wedding.http.deleteAs(wedding.anna, "/api/mail/gmail/connection");

        HttpResponse<String> lookBack = wedding.http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":30}");

        for (HttpResponse<String> response : java.util.List.of(connect, disconnect, lookBack)) {
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:mail-not-set-up");
        }
        assertThat(google.calls()).isEmpty();
    }

    @Test
    void theReturnAddressStillEndsInARedirectToTheFixedPageAndStoresNothing() throws Exception {
        HttpResponse<String> response = wedding.http.get(
                "/api/mail/gmail/return?state=" + "a".repeat(43) + "&code=test-code-not-real&next=https://evil.example",
                Map.of("Cookie", "wp_mail_connect=" + "b".repeat(43)));

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).hasValue("/mail?connected=failed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connection", Integer.class)).isZero();
        assertThat(google.calls()).isEmpty();
    }
}
