package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Connecting a mailbox, end to end against a stand-in for Google: the address Google is sent to, the happy way, and
 * every way a return must fail and store nothing. The return address is the one path that answers without a sign-in,
 * so most of the tests here are about what it refuses.
 */
class MailConnectingIntegrationTest extends MailTestBase {

    // ---------------------------------------------------------------- the address Google is sent to

    @Test
    void googleIsSentToWithEveryParameterAndTheReadOnlyScopeAlone() throws Exception {
        Attempt attempt = start(wedding.anna);

        URI url = URI.create(attempt.url());
        assertThat(attempt.url()).startsWith(google.authorizationUri() + "?");
        Map<String, String> query = google.query(attempt.url());
        assertThat(query.keySet()).containsExactlyInAnyOrder(
                "client_id", "redirect_uri", "response_type", "scope", "access_type", "prompt", "include_granted_scopes",
                "state", "code_challenge", "code_challenge_method");
        assertThat(query)
                .containsEntry("client_id", clientId())
                .containsEntry("redirect_uri", PUBLIC_URL + RETURN_PATH)
                .containsEntry("response_type", "code")
                .containsEntry("scope", "https://www.googleapis.com/auth/gmail.readonly")
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent")
                .containsEntry("include_granted_scopes", "false")
                .containsEntry("code_challenge_method", "S256");
        assertThat(url.getRawQuery()).as("encoded, not glued").contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fmail%2Fgmail%2Freturn");
        // At least 256 bits, in a form that cannot be mistaken for anything else.
        assertThat(Base64.getUrlDecoder().decode(query.get("state"))).hasSize(32);
        assertThat(Base64.getUrlDecoder().decode(query.get("code_challenge"))).hasSize(32);
        assertThat(attempt.cookie()).hasSize(43).isNotEqualTo(query.get("state"));
    }

    @Test
    void theCookieIsOneScriptsCannotReadAndTheBrowserSendsOnlyToTheReturnAddress() throws Exception {
        Attempt attempt = start(wedding.anna);

        String header = attempt.response().headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(COOKIE + "=")).findFirst().orElseThrow();
        assertThat(header)
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=" + RETURN_PATH)
                .contains("Max-Age=600");
        // http://localhost in a test: not Secure here; with an https address it is (see ConnectCookieTest).
        assertThat(attempt.response().headers().firstValue("Cache-Control")).hasValue("no-store");
    }

    @Test
    void theTicketIsKeptOnlyAsHashesAndRunsOutAfterTenMinutes() throws Exception {
        Attempt attempt = start(wedding.anna);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM mail_connect_ticket");
        assertThat((byte[]) row.get("state_hash")).isEqualTo(sha256(attempt.state()));
        assertThat((byte[]) row.get("cookie_hash")).isEqualTo(sha256(attempt.cookie()));
        assertThat(jdbc.queryForObject("SELECT expires_at - created_at FROM mail_connect_ticket", String.class)).isEqualTo("00:10:00");
        assertThat(row.get("used_at")).isNull();
        assertNowhereInThePlain(attempt.state(), attempt.cookie());
    }

    @Test
    void startingAgainSupersedesTheEarlierTicketAndRemovesTheOnesThatRanOut() throws Exception {
        Attempt first = start(wedding.anna);
        Attempt second = start(wedding.anna);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connect_ticket", Integer.class)).isEqualTo(1);

        // The first can no longer be used, whatever its code.
        assertThat(location(comeBack(first.state(), second.cookie(), google.consent(first.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        TestClock.advance(Duration.ofMinutes(11));
        start(wedding.piotr);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connect_ticket", Integer.class))
                .as("the expired one is gone, Piotr's is new").isEqualTo(1);
    }

    // ---------------------------------------------------------------- the happy way

    @Test
    void connectingStoresTheTokenEncryptedAndNowhereInThePlain() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);

        HttpResponse<String> back = comeBack(attempt, code);

        assertThat(location(back)).isEqualTo(YES);
        assertThat(back.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(back.headers().allValues("Referrer-Policy")).containsExactly("no-referrer");
        assertThat(back.headers().allValues("Set-Cookie")).anyMatch(cookie -> cookie.startsWith(COOKIE + "=;") && cookie.contains("Max-Age=0"));
        Map<String, Object> yours = yours(mail(wedding.anna));
        assertThat(yours).containsEntry("state", "CONNECTED").containsEntry("address", ANNA_MAILBOX).containsEntry("messagesNoted", 0);
        assertThat(yours.get("connectedAt")).isNotNull();
        assertThat(yours.get("lastLookAt")).isNull();

        // What Google was asked, as Google would insist on it.
        List<StandInGoogle.Call> exchanges = google.calls("/token");
        assertThat(exchanges).hasSize(1);
        assertThat(exchanges.get(0).form())
                .containsEntry("grant_type", "authorization_code")
                .containsEntry("code", code)
                .containsEntry("redirect_uri", PUBLIC_URL + RETURN_PATH)
                .containsEntry("client_id", clientId());
        assertThat(google.count("/gmail/v1/users/me/profile")).isEqualTo(1);

        // What the database holds.
        String refresh = google.refreshTokensIssued().get(0);
        byte[] sealed = jdbc.queryForObject("SELECT token_sealed FROM mail_connection", byte[].class);
        assertThat(sealed[0]).as("version byte").isEqualTo((byte) 1);
        assertThat(sealed.length).isGreaterThan(1 + 12 + 16 + refresh.length() - 1);
        assertNowhereInThePlain(refresh, code, StandInGoogle.CLIENT_SECRET, attempt.state(), attempt.cookie());
        assertNowhereInThePlain(google.verifiersSeen().toArray(String[]::new));
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM mail_connect_ticket", Boolean.class)).isTrue();
        // The access token Google gave is not kept anywhere.
        for (String secret : google.issuedSecrets()) {
            assertNowhereInThePlain(secret);
        }
    }

    // ---------------------------------------------------------------- what a return must refuse

    @Test
    void aReturnWithNoTicketStoresNothingAndAsksNothingOfGoogle() throws Exception {
        assertThat(location(comeBack((String) null, null, "test-code-not-real"))).isEqualTo(FAILED);

        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void anUnknownTicketIsRefused() throws Exception {
        Attempt attempt = start(wedding.anna);
        String unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(location(comeBack(unknown, attempt.cookie(), google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);
        // Not even shaped like one.
        assertThat(location(comeBack("short", attempt.cookie(), "test-code"))).isEqualTo(FAILED);
        assertThat(location(comeBack("a".repeat(5000), attempt.cookie(), "test-code"))).isEqualTo(FAILED);

        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void aTicketThatRanOutIsRefused() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);

        TestClock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);
        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void aTicketJustBeforeItRunsOutStillWorks() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);

        TestClock.advance(Duration.ofMinutes(9).plusSeconds(50));

        assertThat(location(comeBack(attempt, code))).isEqualTo(YES);
    }

    @Test
    void aTicketUsedBeforeIsRefusedAndTheConnectionItMadeIsLeftAlone() throws Exception {
        Attempt attempt = start(wedding.anna);
        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(YES);
        String sealedBefore = jdbc.queryForObject("SELECT encode(token_sealed, 'hex') FROM mail_connection", String.class);

        // The same address again, as when someone presses "back" or replays what they saw in the history.
        assertThat(location(comeBack(attempt, google.consent(attempt.url(), PIOTR_MAILBOX)))).isEqualTo(FAILED);

        assertThat(google.count("/token")).as("Google was asked once").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT encode(token_sealed, 'hex') FROM mail_connection", String.class)).isEqualTo(sealedBefore);
        assertThat(jdbc.queryForObject("SELECT address FROM mail_connection", String.class)).isEqualTo(ANNA_MAILBOX);
    }

    @Test
    void ofTwoReturnsAtTheSameMomentExactlyOneWins() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        // Google answers slowly, so that the second return arrives while the first is still on its way.
        google.answerNextTokenCallWith(Reply.slow(400));
        int returns = 6;
        ExecutorService pool = Executors.newFixedThreadPool(returns);
        try {
            CountDownLatch together = new CountDownLatch(1);
            List<Future<String>> outcomes = new ArrayList<>();
            for (int i = 0; i < returns; i++) {
                outcomes.add(pool.submit(() -> {
                    together.await();
                    return location(comeBack(attempt, code));
                }));
            }
            together.countDown();
            List<String> locations = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                locations.add(outcome.get());
            }

            assertThat(locations).containsOnlyOnce(YES).filteredOn(location -> !location.equals(YES)).containsOnly(FAILED).hasSize(returns - 1);
            assertThat(google.count("/token")).as("Google was asked once").isEqualTo(1);
            assertThat(connections()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aReturnWithoutTheCookieIsRefused() throws Exception {
        Attempt attempt = start(wedding.anna);

        assertThat(location(comeBack(attempt.state(), null, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void aReturnFromAnotherBrowserIsRefusedAndDoesNotSpendTheTicket() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        String anotherBrowsersCookie = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

        assertThat(location(comeBack(attempt.state(), anotherBrowsersCookie, code))).isEqualTo(FAILED);
        assertNothingStoredAndGoogleLeftAlone();

        // Whoever got hold of the address cannot use it up: the browser it was made for still can.
        assertThat(location(comeBack(attempt, code))).isEqualTo(YES);
    }

    @Test
    void aTicketMadeForTheOtherOwnerIsRefusedWithThisOnesCookie() throws Exception {
        Attempt annas = start(wedding.anna);
        Attempt piotrs = start(wedding.piotr);

        // Piotr's address, with the cookie Anna's browser holds: the way somebody would try to put their own
        // mailbox into another person's connection.
        assertThat(location(comeBack(piotrs.state(), annas.cookie(), google.consent(piotrs.url(), PIOTR_MAILBOX)))).isEqualTo(FAILED);
        assertThat(location(comeBack(annas.state(), piotrs.cookie(), google.consent(annas.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertNothingStoredAndGoogleLeftAlone();
        // Each one's own browser still works, and each connection lands in its own owner's row.
        assertThat(location(comeBack(piotrs, google.consent(piotrs.url(), PIOTR_MAILBOX)))).isEqualTo(YES);
        assertThat(yours(mail(wedding.piotr))).containsEntry("address", PIOTR_MAILBOX);
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NOT_CONNECTED");
    }

    @Test
    void severalCookiesOrSeveralStatesAreNoTicket() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);

        HttpResponse<String> twoCookies = http.get(RETURN_PATH + "?state=" + attempt.state() + "&code=" + code,
                Map.of("Cookie", COOKIE + "=" + attempt.cookie() + "; " + COOKIE + "=" + attempt.cookie()));
        HttpResponse<String> twoStates = http.get(RETURN_PATH + "?state=" + attempt.state() + "&state=" + attempt.state() + "&code=" + code,
                Map.of("Cookie", COOKIE + "=" + attempt.cookie()));

        assertThat(location(twoCookies)).isEqualTo(FAILED);
        assertThat(location(twoStates)).isEqualTo(FAILED);
        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void aHeadRequestTakesNothing() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        HttpResponse<String> head = client.send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + RETURN_PATH + "?state=" + attempt.state() + "&code=" + code))
                        .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                        .header("Cookie", COOKIE + "=" + attempt.cookie())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(location(head)).isEqualTo(FAILED);
        assertNothingStoredAndGoogleLeftAlone();
        assertThat(location(comeBack(attempt, code))).isEqualTo(YES);
    }

    // ---------------------------------------------------------------- what Google says

    @Test
    void thePersonSayingNoAtGoogleEndsRefusedAndStoresNothing() throws Exception {
        Attempt attempt = start(wedding.anna);

        HttpResponse<String> back = http.get(RETURN_PATH + "?state=" + attempt.state() + "&error=access_denied",
                Map.of("Cookie", COOKIE + "=" + attempt.cookie()));

        assertThat(location(back)).isEqualTo(REFUSED);
        assertNothingStoredAndGoogleLeftAlone();
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM mail_connect_ticket", Boolean.class)).as("the ticket is spent").isTrue();
    }

    @Test
    void anyOtherErrorFromGoogleIsAFailureAndNoTicketMeansNoWordOfRefusal() throws Exception {
        Attempt attempt = start(wedding.anna);

        HttpResponse<String> other = http.get(RETURN_PATH + "?state=" + attempt.state() + "&error=server_error",
                Map.of("Cookie", COOKIE + "=" + attempt.cookie()));
        // Anybody can type this: without a ticket it is not even "refused".
        HttpResponse<String> nobody = http.get(RETURN_PATH + "?error=access_denied");

        assertThat(location(other)).isEqualTo(FAILED);
        assertThat(location(nobody)).isEqualTo(FAILED);
        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void aReturnWithoutACodeStoresNothing() throws Exception {
        Attempt attempt = start(wedding.anna);

        assertThat(location(comeBack(attempt, null))).isEqualTo(FAILED);

        assertNothingStoredAndGoogleLeftAlone();
    }

    @Test
    void googleAnsweringWithoutARefreshTokenStoresNothingAndTakesBackWhatItGave() throws Exception {
        Attempt attempt = start(wedding.anna);

        String code = google.consent(attempt.url(), ANNA_MAILBOX, Set.of(StandInGoogle.READ_ONLY), false);

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);
        assertThat(connections()).isZero();
        assertThat(google.count("/gmail/v1/users/me/profile")).as("the mailbox was never looked at").isZero();
        assertThat(google.count("/revoke")).as("the access token was given back").isEqualTo(1);
    }

    @Test
    void googleAnsweringWithoutTheScopeStoresNothingAndWithdrawsWhatItGave() throws Exception {
        Attempt attempt = start(wedding.anna);

        String code = google.consent(attempt.url(), ANNA_MAILBOX, Set.of("openid"), true);

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);
        assertThat(connections()).isZero();
        assertThat(google.count("/gmail/v1/users/me/profile")).isZero();
        assertThat(google.revokedTokens()).containsExactlyElementsOf(google.refreshTokensIssued());
    }

    @Test
    void googleGrantingMoreThanReadingIsRefusedToo() throws Exception {
        Attempt attempt = start(wedding.anna);

        String code = google.consent(attempt.url(), ANNA_MAILBOX, Set.of(StandInGoogle.READ_ONLY, "https://mail.google.com/"), true);

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);
        assertThat(connections()).isZero();
    }

    @Test
    void googleAnsweringWithSomethingThatIsNotJsonStoresNothing() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        google.answerNextTokenCallWith(Reply.text(200, "<html>Sign in to continue</html>"));

        assertThat(location(comeBack(attempt, code))).isEqualTo(FAILED);

        assertThat(connections()).isZero();
        assertThat(google.count("/gmail/v1/users/me/profile")).isZero();
    }

    @Test
    void googleAnsweringWithBrokenJsonStoresNothing() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.json(200, "{\"access_token\": \"test-access-not-real\", "));

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(connections()).isZero();
    }

    @Test
    void anAnswerThatIsTooLargeStoresNothing() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.json(200, "{\"access_token\":\"" + "x".repeat(200_000) + "\"}"));

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(connections()).isZero();
    }

    @Test
    void googleBeingTooSlowStoresNothingAndTheReturnStillEndsOnThePage() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.slow(6_000));
        long began = System.nanoTime();

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - began)).as("gave up after the limit of 2 s, not 6").isLessThan(Duration.ofSeconds(5));
        assertThat(connections()).isZero();
    }

    @Test
    void googleHangingUpOrFailingStoresNothing() throws Exception {
        Attempt hangUp = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.dropped());
        assertThat(location(comeBack(hangUp, google.consent(hangUp.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        Attempt broken = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.json(500, "{\"error\":\"backend_error\"}"));
        assertThat(location(comeBack(broken, google.consent(broken.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(connections()).isZero();
    }

    @Test
    void aRedirectFromGooglesTokenAddressIsNotFollowed() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextTokenCallWith(Reply.redirectTo(google.elsewhere()));

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(google.count("/elsewhere")).as("nothing went where the redirect pointed").isZero();
        assertThat(connections()).isZero();
    }

    @Test
    void aRedirectFromTheProfileAddressIsNotFollowedEither() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextProfileCallWith(Reply.redirectTo(google.elsewhere()));

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(google.count("/elsewhere")).isZero();
        assertThat(connections()).isZero();
        assertThat(google.revokedTokens()).as("what Google gave is given back").hasSize(1);
    }

    @Test
    void aMailboxAddressThatIsNoAddressIsRefused() throws Exception {
        Attempt attempt = start(wedding.anna);
        google.answerNextProfileCallWith(Reply.json(200, "{\"emailAddress\":\"not an address\"}"));

        assertThat(location(comeBack(attempt, google.consent(attempt.url(), ANNA_MAILBOX)))).isEqualTo(FAILED);

        assertThat(connections()).isZero();
    }

    // ---------------------------------------------------------------- where the browser goes

    @Test
    void theRedirectGoesToTheOneFixedPageWhateverTheRequestSays() throws Exception {
        Attempt attempt = start(wedding.anna);
        String code = google.consent(attempt.url(), ANNA_MAILBOX);
        String evil = "https%3A%2F%2Fevil.example%2Fsteal";
        Map<String, String> cookie = Map.of("Cookie", COOKIE + "=" + attempt.cookie());

        String[] attacks = {
            "?next=" + evil,
            "?redirect_uri=" + evil,
            "?redirect=" + evil + "&url=" + evil + "&returnTo=" + evil + "&continue=" + evil,
            "?state=" + attempt.state() + "&next=" + evil + "&redirect_uri=" + evil,
            "?state=" + attempt.state() + "&code=" + code + "&next=//evil.example&redirect_uri=//evil.example&error=" + evil,
            "?state=" + attempt.state() + "&error=" + evil,
        };
        for (String attack : attacks) {
            HttpResponse<String> response = http.get(RETURN_PATH + attack, cookie);
            assertThat(location(response)).as(attack).startsWith(PUBLIC_URL + "/mail?connected=");
            assertThat(location(response)).as(attack).doesNotContain("evil");
        }
        // And the same for a good return, with the junk beside it.
        Attempt another = start(wedding.anna);
        HttpResponse<String> good = http.get(
                RETURN_PATH + "?state=" + another.state() + "&code=" + google.consent(another.url(), ANNA_MAILBOX) + "&next=" + evil,
                Map.of("Cookie", COOKIE + "=" + another.cookie()));
        assertThat(location(good)).isEqualTo(YES);
    }

    @Test
    void theHostHeaderDoesNotChangeWhereItGoes() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(("GET " + RETURN_PATH + " HTTP/1.1\r\nHost: evil.example\r\nX-Forwarded-Host: evil.example\r\n"
                    + "X-Forwarded-Proto: https\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            String answer = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);

            assertThat(answer).startsWith("HTTP/1.1 303");
            assertThat(answer).contains("Location: " + FAILED + "\r\n").doesNotContain("evil.example/");
        }
    }

    // ---------------------------------------------------------------- connecting again

    @Test
    void connectingAnotherMailboxReplacesTheConnectionWithdrawsTheOldTokenAndForgetsWhereTheSyncStood() throws Exception {
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        String first = google.refreshTokensIssued().get(0);
        jdbc.update("UPDATE mail_connection SET sync_marker = 'history-4711', last_look_at = now()");
        assertThat(google.revokedTokens()).isEmpty();

        assertThat(location(connect(wedding.anna, "anna.other@planner.example"))).isEqualTo(YES);

        assertThat(connections()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT address, sync_marker, last_look_at, state FROM mail_connection");
        assertThat(row).containsEntry("address", "anna.other@planner.example").containsEntry("state", "CONNECTED");
        assertThat(row.get("sync_marker")).isNull();
        assertThat(row.get("last_look_at")).isNull();
        assertThat(google.revokedTokens()).containsExactly(first);
        assertThat(yours(mail(wedding.anna))).containsEntry("address", "anna.other@planner.example");
    }

    @Test
    void connectingTheSameMailboxAgainReplacesTheTokenKeepsWhereTheSyncStoodAndDoesNotWithdrawTheSharedPermission() throws Exception {
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        String sealedFirst = jdbc.queryForObject("SELECT encode(token_sealed, 'hex') FROM mail_connection", String.class);
        jdbc.update("UPDATE mail_connection SET sync_marker = 'history-4711', last_look_at = now()");

        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        assertThat(jdbc.queryForObject("SELECT encode(token_sealed, 'hex') FROM mail_connection", String.class)).isNotEqualTo(sealedFirst);
        assertThat(jdbc.queryForObject("SELECT sync_marker FROM mail_connection", String.class)).isEqualTo("history-4711");
        assertThat(google.revokedTokens()).as("the new token would go with it, if Google counts both as one permission").isEmpty();
    }

    @Test
    void aMailboxThatNeededReconnectingIsConnectedAgain() throws Exception {
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        jdbc.update("UPDATE mail_connection SET state = 'NEEDS_RECONNECTING', token_sealed = NULL");
        assertThat(yours(mail(wedding.anna))).containsEntry("state", "NEEDS_RECONNECTING");

        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        assertThat(yours(mail(wedding.anna))).containsEntry("state", "CONNECTED");
    }

    @Test
    void eachOwnerHasTheirOwnConnection() throws Exception {
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
        assertThat(location(connect(wedding.piotr, PIOTR_MAILBOX))).isEqualTo(YES);

        assertThat(connections()).isEqualTo(2);
        assertThat(yours(mail(wedding.anna))).containsEntry("address", ANNA_MAILBOX);
        assertThat(yours(mail(wedding.piotr))).containsEntry("address", PIOTR_MAILBOX);
    }

    // ---------------------------------------------------------------- helpers

    private void assertNothingStoredAndGoogleLeftAlone() {
        assertThat(connections()).as("no connection stored").isZero();
        assertThat(google.calls()).as("nothing asked of Google").isEmpty();
    }

    static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }
}
