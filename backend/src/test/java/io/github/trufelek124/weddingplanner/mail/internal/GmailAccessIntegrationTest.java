package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.MailTestBase;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Access;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Granted;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.NeedsReconnecting;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.NotConnected;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Unavailable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What the sync will call: an access token for an owner's mailbox, and what happens when Google no longer lets in. */
class GmailAccessIntegrationTest extends MailTestBase {

    @Autowired
    GmailAccess access;

    @Autowired
    ConnectionRepository connections;

    UUID anna;

    @BeforeEach
    void annaIsConnected() throws Exception {
        anna = UUID.fromString(wedding.annaId);
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
    }

    private int refreshes() {
        return (int) google.calls("/token").stream().filter(call -> "refresh_token".equals(call.form().get("grant_type"))).count();
    }

    @Test
    void theTokenIsAskedOnceAndReusedWhileItLives() {
        Access first = access.accessTokenFor(anna);
        Access second = access.accessTokenFor(anna);
        TestClock.advance(Duration.ofMinutes(30));
        Access third = access.accessTokenFor(anna);

        assertThat(first).isInstanceOf(Granted.class);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(refreshes()).isEqualTo(1);
        // And what Google was shown is the refresh token the connection was made with, unsealed.
        assertThat(google.calls("/token").get(1).form().get("refresh_token")).isEqualTo(google.refreshTokensIssued().get(0));
    }

    @Test
    void itIsAskedAgainOnceGoogleSaysTheOldOneEnded() {
        Access first = access.accessTokenFor(anna);

        // Google said 3599 seconds; one minute's margin is kept.
        TestClock.advance(Duration.ofSeconds(3599 - 61));
        assertThat(access.accessTokenFor(anna)).isEqualTo(first);
        assertThat(refreshes()).isEqualTo(1);

        TestClock.advance(Duration.ofSeconds(2));
        Access later = access.accessTokenFor(anna);

        assertThat(later).isInstanceOf(Granted.class).isNotEqualTo(first);
        assertThat(refreshes()).isEqualTo(2);
    }

    @Test
    void theAccessTokenIsOnlyInMemoryNeverInTheDatabase() {
        Access granted = access.accessTokenFor(anna);

        assertThat(granted).isInstanceOf(Granted.class);
        assertNowhereInThePlain(((Granted) granted).accessToken());
        assertThat(granted.toString()).doesNotContain("test-access");
    }

    @Test
    void googleWithdrawingAccessMarksTheConnectionAndTheTokenIsDropped() throws Exception {
        google.withdraw(google.refreshTokensIssued().get(0));

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);

        assertThat(jdbc.queryForMap("SELECT state, token_sealed FROM mail_connection"))
                .containsEntry("state", "NEEDS_RECONNECTING").containsEntry("token_sealed", null);
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NEEDS_RECONNECTING").containsEntry("address", ANNA_MAILBOX);
        assertThat(partner(mail(wedding.piotr))).containsEntry("state", "NEEDS_RECONNECTING");
        // From then on the sync leaves it alone: Google is not asked again.
        int asked = refreshes();
        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);
        assertThat(refreshes()).isEqualTo(asked);
    }

    @Test
    void aChangedKeyMakesTheConnectionNeedReconnectingAndGoogleIsNotAsked() throws Exception {
        byte[] otherKey = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
        byte[] sealedUnderAnotherKey = new TokenVault(otherKey).seal(google.refreshTokensIssued().get(0), TokenVault.memberAad(anna));
        jdbc.update("UPDATE mail_connection SET token_sealed = ?", (Object) sealedUnderAnotherKey);

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);

        assertThat(refreshes()).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM mail_connection", String.class)).isEqualTo("NEEDS_RECONNECTING");
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NEEDS_RECONNECTING");
        // No error page for the person, and the page of the state still works for the partner.
        assertThat(http.getAs(wedding.piotr, "/api/mail").statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenSealedForAnotherMemberIsNotUsedForThisOne() {
        byte[] sealedForPiotr = new TokenVault("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))
                .seal(google.refreshTokensIssued().get(0), TokenVault.memberAad(UUID.fromString(wedding.piotrId)));
        jdbc.update("UPDATE mail_connection SET token_sealed = ?", (Object) sealedForPiotr);

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);

        assertThat(refreshes()).isZero();
    }

    @Test
    void aTokenThatIsTamperedWithMakesItNeedReconnecting() {
        jdbc.update("UPDATE mail_connection SET token_sealed = set_byte(token_sealed, 20, get_byte(token_sealed, 20) # 1)");

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);
        assertThat(refreshes()).isZero();
    }

    @Test
    void googleNotBeingWellIsNotTheConnectionBeingBad() {
        google.answerEveryTokenCallWith(Reply.dropped());

        assertThat(access.accessTokenFor(anna)).isInstanceOf(Unavailable.class);
        google.answerEveryTokenCallWith(Reply.json(503, "{\"error\":\"backend_error\"}"));
        assertThat(access.accessTokenFor(anna)).isInstanceOf(Unavailable.class);
        google.answerEveryTokenCallWith(Reply.text(200, "<html>nope</html>"));
        assertThat(access.accessTokenFor(anna)).isInstanceOf(Unavailable.class);

        assertThat(jdbc.queryForObject("SELECT state FROM mail_connection", String.class)).isEqualTo("CONNECTED");
        google.answerEveryTokenCallWith(null);
        assertThat(access.accessTokenFor(anna)).isInstanceOf(Granted.class);
    }

    @Test
    void aRefreshThatGrantsMoreOrOtherThanReadingMarksTheConnection() {
        google.answerEveryTokenCallWith(Reply.json(200,
                "{\"access_token\":\"test-access-not-real-x\",\"expires_in\":3599,\"scope\":\"https://mail.google.com/\",\"token_type\":\"Bearer\"}"));

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);
        assertThat(jdbc.queryForObject("SELECT state FROM mail_connection", String.class)).isEqualTo("NEEDS_RECONNECTING");
    }

    @Test
    void aDisconnectedOwnerGetsNothingEvenWhileATokenWasInMemory() throws Exception {
        assertThat(access.accessTokenFor(anna)).isInstanceOf(Granted.class);

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(access.accessTokenFor(anna)).isInstanceOf(NotConnected.class);
    }

    @Test
    void anOwnerWhoNeverConnectedGetsNothing() {
        assertThat(access.accessTokenFor(UUID.fromString(wedding.piotrId))).isInstanceOf(NotConnected.class);
        assertThat(access.accessTokenFor(UUID.randomUUID())).isInstanceOf(NotConnected.class);
    }

    @Test
    void connectingAgainAfterNeedingItWorksAndIsServed() throws Exception {
        google.withdraw(google.refreshTokensIssued().get(0));
        assertThat(access.accessTokenFor(anna)).isInstanceOf(NeedsReconnecting.class);

        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        assertThat(access.accessTokenFor(anna)).isInstanceOf(Granted.class);
    }

    @Test
    void aConnectionMadeAgainWhileATokenFailedIsNotMarkedByTheLateFailure() throws Exception {
        // The guard of the marking: it only changes the token that was found wanting.
        byte[] old = jdbc.queryForObject("SELECT token_sealed FROM mail_connection", byte[].class);
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        boolean marked = connections.markNeedsReconnecting(anna, old);

        assertThat(marked).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM mail_connection", String.class)).isEqualTo("CONNECTED");
    }
}
