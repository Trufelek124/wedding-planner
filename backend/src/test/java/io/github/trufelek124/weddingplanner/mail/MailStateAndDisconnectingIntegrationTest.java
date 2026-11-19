package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What each owner sees of the connections, and disconnecting: what it removes, whose, and when Google is not well. */
class MailStateAndDisconnectingIntegrationTest extends MailTestBase {

    @Test
    void beforeAnyConnectionBothAreNotConnected() throws Exception {
        Map<String, Object> state = mail(wedding.anna);

        assertThat(state).containsEntry("setUp", true);
        assertThat(yours(state)).containsEntry("state", "NOT_CONNECTED").containsEntry("messagesNoted", 0);
        assertThat(yours(state)).containsEntry("address", null).containsEntry("connectedAt", null).containsEntry("lastLookAt", null);
        assertThat(partner(state)).containsEntry("joined", true).containsEntry("state", "NOT_CONNECTED").containsEntry("lastLookAt", null);
    }

    @Test
    void thePartnerSeesWhetherYouAreConnectedButNeverTheAddress() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        jdbc.update("UPDATE mail_connection SET last_look_at = now()");

        HttpResponse<String> piotrs = http.getAs(wedding.piotr, "/api/mail");
        Map<String, Object> state = TestHttp.json(piotrs);

        assertThat(piotrs.body()).doesNotContain(ANNA_MAILBOX).doesNotContain("planner.example");
        assertThat(partner(state).keySet()).containsExactlyInAnyOrder("joined", "state", "lastLookAt");
        assertThat(partner(state)).containsEntry("state", "CONNECTED");
        assertThat(partner(state).get("lastLookAt")).isNotNull();
        assertThat(yours(state)).containsEntry("state", "NOT_CONNECTED");
        // Every field is always there, as a value or as null.
        assertThat(state.keySet()).containsExactlyInAnyOrder("setUp", "yours", "partner");
        assertThat(yours(state).keySet()).containsExactlyInAnyOrder("state", "address", "connectedAt", "lastLookAt", "messagesNoted");
    }

    @Test
    void aPartnerWhoHasNotJoinedYetIsSaidNotToHave() throws Exception {
        jdbc.update("DELETE FROM wedding");
        String anna = io.github.trufelek124.weddingplanner.TestSignIn.signIn(http, ANNA).accessToken();
        assertThat(http.postJsonAs(anna, "/api/wedding", TestHttp.jsonOf(Map.of(
                "date", "2027-06-19", "currency", "PLN", "timeZone", "Europe/Warsaw", "yourName", "Anna", "partnerName", "Piotr")))
                .statusCode()).isEqualTo(201);

        Map<String, Object> state = mail(anna);

        assertThat(partner(state)).containsEntry("joined", false).containsEntry("state", "NOT_CONNECTED").containsEntry("lastLookAt", null);
        assertThat(yours(state)).containsEntry("state", "NOT_CONNECTED");
    }

    @Test
    void aConnectionThatNeedsReconnectingIsSaidSoToItsOwnerAndThePartner() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        jdbc.update("UPDATE mail_connection SET state = 'NEEDS_RECONNECTING', token_sealed = NULL");

        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NEEDS_RECONNECTING").containsEntry("address", ANNA_MAILBOX);
        assertThat(partner(mail(wedding.piotr))).containsEntry("state", "NEEDS_RECONNECTING");
    }

    @Test
    void lookingAtTheStateAsksNothingOfGoogle() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        int before = google.calls().size();

        mail(wedding.anna);
        mail(wedding.piotr);

        assertThat(google.calls()).hasSize(before);
    }

    // ---------------------------------------------------------------- disconnecting

    @Test
    void disconnectingWithdrawsThePermissionAndRemovesTheConnectionWithItsToken() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        String refresh = google.refreshTokensIssued().get(0);
        jdbc.update("UPDATE mail_connection SET sync_marker = 'history-4711', last_look_at = now()");

        HttpResponse<String> response = http.deleteAs(wedding.anna, "/api/mail/gmail/connection");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(google.revokedTokens()).containsExactly(refresh);
        assertThat(connections()).isZero();
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NOT_CONNECTED").containsEntry("address", null);
    }

    @Test
    void disconnectingAgainChangesNothingAndAsksNothingOfGoogle() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);
        int calls = google.calls().size();

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(google.calls()).hasSize(calls);
        // Nor does it matter that nothing was ever connected.
        assertThat(http.deleteAs(wedding.piotr, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);
    }

    @Test
    void disconnectingRemovesOnlyTheCallersOwnConnection() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        String annasRefresh = google.refreshTokensIssued().get(0);
        connect(wedding.piotr, PIOTR_MAILBOX);
        String piotrsRefresh = google.refreshTokensIssued().stream().filter(token -> !token.equals(annasRefresh)).findFirst().orElseThrow();

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(connections()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT address FROM mail_connection", String.class)).isEqualTo(PIOTR_MAILBOX);
        assertThat(yours(mail(wedding.piotr))).containsEntry("state", "CONNECTED");
        assertThat(google.revokedTokens()).containsExactly(annasRefresh).doesNotContain(piotrsRefresh);
    }

    @Test
    void disconnectingStillHappensWhenGoogleCannotBeReached() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        google.answerEveryRevokeCallWith(Reply.dropped());

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(google.count("/revoke")).as("it tried").isEqualTo(1);
        assertThat(connections()).isZero();
    }

    @Test
    void disconnectingStillHappensWhenGoogleIsTooSlow() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        google.answerEveryRevokeCallWith(Reply.slow(6_000));
        long began = System.nanoTime();

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - began)).isLessThan(java.time.Duration.ofSeconds(5));
        assertThat(connections()).isZero();
    }

    @Test
    void disconnectingStillHappensWhenGoogleRefuses() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        google.answerEveryRevokeCallWith(Reply.json(400, "{\"error\":\"invalid_token\"}"));

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(connections()).isZero();
    }

    @Test
    void disconnectingAlsoRemovesATicketThatWasStartedAndNotUsed() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);
        assertThat(connections()).isZero();
    }

    @Test
    void aMailboxThatNeededReconnectingCanBeDisconnectedWithoutAskingGoogle() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        jdbc.update("UPDATE mail_connection SET state = 'NEEDS_RECONNECTING', token_sealed = NULL");
        int calls = google.calls().size();

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(google.calls()).hasSize(calls);
        assertThat(connections()).isZero();
    }

    @Test
    void removingTheOwnersMemberRemovesTheirConnectionAndTicketsWithIt() throws Exception {
        connect(wedding.anna, ANNA_MAILBOX);
        start(wedding.piotr);

        jdbc.update("DELETE FROM wedding");

        assertThat(connections()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connect_ticket", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- who may call

    @Test
    void everyCallButTheReturnNeedsASignIn() throws Exception {
        assertThat(http.get("/api/mail").statusCode()).isEqualTo(401);
        assertThat(http.post("/api/mail/gmail/connection", Map.of()).statusCode()).isEqualTo(401);
        assertThat(http.send("DELETE", "/api/mail/gmail/connection").statusCode()).isEqualTo(401);
        // Only exactly this path is open: not what is beside it or below it.
        assertThat(http.get("/api/mail/gmail/return/anything").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/mail/gmail").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/mail/gmail/return2").statusCode()).isEqualTo(401);
        assertThat(http.get("/api/mail/gmail/return").statusCode()).as("the return address answers").isEqualTo(303);
    }

    @Test
    void nothingOtherThanAGetIsServedAtTheReturnAddress() throws Exception {
        HttpResponse<String> post = http.post(RETURN_PATH, Map.of());

        assertThat(post.statusCode()).isNotIn(200, 201, 204, 303);
        assertThat(connections()).isZero();
        assertThat(List.of(post.body())).noneMatch(body -> body.contains("Location"));
    }
}
