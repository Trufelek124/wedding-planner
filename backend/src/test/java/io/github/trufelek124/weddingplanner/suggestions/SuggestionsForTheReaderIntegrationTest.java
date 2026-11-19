package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions.Proposed;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the reader of the mail (S04) asks of the suggestions, and what the two owners are told of a message's link.
 * Made-up mail only: addresses are at .example.
 */
class SuggestionsForTheReaderIntegrationTest extends PostgresIntegrationTest {

    static final String ANNAS_MAILBOX = "anna.mailbox@planner.example";
    /** What the mail module makes for a message in Anna's mailbox. */
    static final String ANNAS_LINK = "https://mail.google.com/mail/?authuser=anna.mailbox%40planner.example#all/18c0ffee00000001";
    static final String WITHOUT_QUERY = "https://mail.google.com/mail/#all/18c0ffee00000001";
    static final String PLAIN_LINK = "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    TestHttp http;
    String anna;
    String piotr;
    UUID annaId;
    UUID piotrId;
    UUID weddingId;
    int keys;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        annaId = UUID.fromString(wedding.annaId);
        piotrId = UUID.fromString(wedding.piotrId);
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    private UUID handIn(String key, UUID inbox, String link) {
        return suggestions.handIn(new Proposal(
                weddingId, key, Kind.TASK, "Task " + (++keys), "", null, null, null, null, null, null, null, null, null, null,
                "Jan from Palace on the Hill", "jan@palace-hill.example", "Your tasting", Instant.parse("2026-11-12T09:30:00Z"),
                inbox, link, "We could meet on Friday.", 80));
    }

    private static void later() {
        TestClock.advance(Duration.ofSeconds(2));
    }

    // ---------------------------------------------------------------- what was proposed

    @Test
    void onlyKeysThatBeginSoAreFoundInEveryStateNewestFirst() throws Exception {
        UUID first = handIn("m1-abc-1", annaId, ANNAS_LINK);
        later();
        UUID second = handIn("m1-abc-2", annaId, ANNAS_LINK);
        later();
        handIn("m1-abd-1", annaId, ANNAS_LINK);
        later();
        UUID third = handIn("m1-abc-3", piotrId, ANNAS_LINK);
        assertThat(http.postJsonAs(anna, "/api/suggestions/" + first + "/accept", "").statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(anna, "/api/suggestions/" + second + "/reject", "").statusCode()).isEqualTo(200);

        List<Proposed> found = suggestions.proposedUnder(weddingId, "m1-abc-", 10);

        assertThat(found.stream().map(Proposed::sourceKey).toList()).containsExactly("m1-abc-3", "m1-abc-2", "m1-abc-1");
        assertThat(found.stream().map(Proposed::title).toList()).containsExactly(
                title(third), title(second), title(first));
        assertThat(suggestions.proposedUnder(weddingId, "m1-abc-", 2).stream().map(Proposed::sourceKey).toList())
                .as("at most so many, the newest").containsExactly("m1-abc-3", "m1-abc-2");
        assertThat(suggestions.proposedUnder(weddingId, "m1-ab", 10)).hasSize(4);
        assertThat(suggestions.proposedUnder(weddingId, "nothing", 10)).isEmpty();
        assertThat(suggestions.proposedUnder(UUID.randomUUID(), "m1-abc-", 10)).as("another wedding").isEmpty();
    }

    @Test
    void theBeginningIsNoPattern() {
        handIn("m1-a_c-1", annaId, ANNAS_LINK);
        handIn("m1-abc-1", annaId, ANNAS_LINK);
        handIn("m1-50%-1", annaId, ANNAS_LINK);
        handIn("m1-500-1", annaId, ANNAS_LINK);
        handIn("t1-x\\y-1", annaId, ANNAS_LINK);
        handIn("t1-xzy-1", annaId, ANNAS_LINK);

        assertThat(keysUnder("m1-a_c")).as("an underscore is an underscore").containsExactly("m1-a_c-1");
        assertThat(keysUnder("m1-50%")).as("a percent sign is a percent sign").containsExactly("m1-50%-1");
        assertThat(keysUnder("m1-%")).isEmpty();
        assertThat(keysUnder("m1-_")).isEmpty();
        assertThat(keysUnder("t1-x\\")).as("a backslash too").containsExactly("t1-x\\y-1");
        assertThat(keysUnder("m1-")).hasSize(4);
    }

    private List<String> keysUnder(String beginning) {
        return suggestions.proposedUnder(weddingId, beginning, 50).stream().map(Proposed::sourceKey).sorted().toList();
    }

    private String title(UUID id) {
        return jdbc.queryForObject("SELECT title FROM suggestion WHERE id = ?", String.class, id);
    }

    @Test
    void aBlankBeginningAndAMostThatIsNotPositiveAreRefused() {
        assertThatThrownBy(() -> suggestions.proposedUnder(weddingId, "", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestions.proposedUnder(weddingId, "  ", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestions.proposedUnder(weddingId, null, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestions.proposedUnder(weddingId, "m1-", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> suggestions.proposedUnder(weddingId, "m1-", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatWasProposedHidesItselfFromToString() {
        assertThat(new Proposed("m1-secret-key", Kind.PAYMENT, "Deposit for the Palace on the Hill", LocalDate.parse("2026-12-01"), null, 250_000L).toString())
                .doesNotContain("secret").doesNotContain("Palace");
    }

    // ---------------------------------------------------------------- what was proposed lately

    private UUID handInPayment(String key, UUID inbox, String title, LocalDate start, LocalDate due, Long amount) {
        return suggestions.handIn(new Proposal(
                weddingId, key, Kind.PAYMENT, title, "", start, due, amount, null, null, null, null, null, null, null,
                "Jan from Palace on the Hill", "jan@palace-hill.example", "Your tasting", Instant.parse("2026-11-12T09:30:00Z"),
                inbox, ANNAS_LINK, "We could meet on Friday.", 80));
    }

    @Test
    void whatWasProposedSinceAMomentComesNewestFirstWithWhatIsNeededToRecogniseItWhateverBecameOfIt() throws Exception {
        UUID old = handIn("m1-old-1", annaId, ANNAS_LINK);
        jdbc.update("UPDATE suggestion SET created_at = created_at - interval '5 days' WHERE id = ?", old);
        Instant since = TestClock.now().minusSeconds(1);
        UUID first = handInPayment("m1-new-1", piotrId, "Pay the deposit", null, LocalDate.parse("2026-12-01"), 500_000L);
        later();
        UUID second = handIn("m1-new-2", annaId, ANNAS_LINK);
        later();
        handInPayment("m1-new-3", annaId, "Pay the hall", LocalDate.parse("2026-11-20"), null, null);
        assertThat(http.postJsonAs(anna, "/api/suggestions/" + second + "/reject", "").statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(piotr, "/api/suggestions/" + first + "/accept", "").statusCode()).isEqualTo(200);

        List<Proposed> lately = suggestions.proposedSince(weddingId, since, 10);

        assertThat(lately.stream().map(Proposed::sourceKey).toList()).as("the newest first, the old one left out, rejected and accepted too")
                .containsExactly("m1-new-3", "m1-new-2", "m1-new-1");
        assertThat(lately.get(0)).isEqualTo(new Proposed("m1-new-3", Kind.PAYMENT, "Pay the hall", LocalDate.parse("2026-11-20"), null, null));
        assertThat(lately.get(1)).isEqualTo(new Proposed("m1-new-2", Kind.TASK, title(second), null, null, null));
        assertThat(lately.get(2)).isEqualTo(new Proposed("m1-new-1", Kind.PAYMENT, "Pay the deposit", null, LocalDate.parse("2026-12-01"), 500_000L));
        assertThat(suggestions.proposedSince(weddingId, since, 2).stream().map(Proposed::sourceKey).toList()).containsExactly("m1-new-3", "m1-new-2");
        assertThat(suggestions.proposedSince(weddingId, since.minus(Duration.ofDays(30)), 10)).as("from the old one on").hasSize(4);
        assertThat(suggestions.proposedSince(UUID.randomUUID(), since, 10)).as("another wedding").isEmpty();
        assertThat(suggestions.proposedSince(weddingId, TestClock.now().plusSeconds(60), 10)).as("nothing yet that new").isEmpty();
        assertThat(old).isNotNull();
        assertThatThrownBy(() -> suggestions.proposedSince(weddingId, since, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatWasProposedUnderAKeyGivesTheSameFacts() {
        handInPayment("m1-abc-1", annaId, "Pay the deposit", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"), 500_000L);

        assertThat(suggestions.proposedUnder(weddingId, "m1-abc-", 5)).containsExactly(new Proposed(
                "m1-abc-1", Kind.PAYMENT, "Pay the deposit", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-12-01"), 500_000L));
    }

    // ---------------------------------------------------------------- how many came into being

    @Test
    void madeSinceCountsOnlyThatInboxAndOnlyFromThatMoment() {
        handIn("m1-old-1", annaId, ANNAS_LINK);
        later();
        Instant since = TestClock.now();
        later();
        handIn("m1-new-1", annaId, ANNAS_LINK);
        handIn("m1-new-2", annaId, ANNAS_LINK);
        handIn("m1-new-3", piotrId, ANNAS_LINK);

        assertThat(suggestions.madeSince(weddingId, annaId, since)).isEqualTo(2);
        assertThat(suggestions.madeSince(weddingId, piotrId, since)).isEqualTo(1);
        assertThat(suggestions.madeSince(weddingId, annaId, since.minus(Duration.ofHours(1)))).isEqualTo(3);
        assertThat(suggestions.madeSince(weddingId, annaId, TestClock.now().plus(Duration.ofHours(1)))).isZero();
        assertThat(suggestions.madeSince(UUID.randomUUID(), annaId, since)).as("another wedding").isZero();
    }

    @Test
    void whatWasDecidedStillCountsAsMade() throws Exception {
        Instant since = TestClock.now().minus(Duration.ofMinutes(1));
        UUID id = handIn("m1-x-1", annaId, ANNAS_LINK);
        assertThat(http.postJsonAs(piotr, "/api/suggestions/" + id + "/reject", "").statusCode()).isEqualTo(200);

        assertThat(suggestions.madeSince(weddingId, annaId, since)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the link

    @Test
    void theOwnerOfTheInboxIsGivenTheWholeLinkAndTheOtherOwnerNotItsQuery() throws Exception {
        UUID id = handIn("m1-link-1", annaId, ANNAS_LINK);

        assertThat(linkOf(anna, "", id)).isEqualTo(ANNAS_LINK);
        assertThat(linkOf(piotr, "", id)).isEqualTo(WITHOUT_QUERY);
        assertThat(jdbc.queryForObject("SELECT mail_link FROM suggestion WHERE id = ?", String.class, id))
                .as("what is kept is whole").isEqualTo(ANNAS_LINK);
    }

    @Test
    void itHoldsInEveryStateOfTheList() throws Exception {
        UUID waiting = handIn("m1-link-1", annaId, ANNAS_LINK);
        UUID accepted = handIn("m1-link-2", annaId, ANNAS_LINK);
        UUID rejected = handIn("m1-link-3", annaId, ANNAS_LINK);
        post(anna, accepted, "accept");
        post(anna, rejected, "reject");

        for (String state : List.of("WAITING", "ACCEPTED", "REJECTED", "ALL")) {
            HttpResponse<String> piotrs = http.getAs(piotr, "/api/suggestions?state=" + state);
            assertThat(piotrs.body()).as("the other owner, " + state).doesNotContain("authuser").doesNotContain("anna.mailbox")
                    .doesNotContain("planner.example");
            assertThat(TestHttp.jsonList(piotrs)).allSatisfy(one -> assertThat(one.get("mailLink")).isEqualTo(WITHOUT_QUERY));
            assertThat(TestHttp.jsonList(http.getAs(anna, "/api/suggestions?state=" + state)))
                    .as("the owner, " + state).allSatisfy(one -> assertThat(one.get("mailLink")).isEqualTo(ANNAS_LINK));
        }
        assertThat(linkOf(piotr, "?state=WAITING", waiting)).isEqualTo(WITHOUT_QUERY);
    }

    @Test
    void itHoldsInTheAnswersOfAcceptAndRejectToo() throws Exception {
        UUID byPiotr = handIn("m1-link-1", annaId, ANNAS_LINK);
        UUID byAnna = handIn("m1-link-2", annaId, ANNAS_LINK);
        UUID rejectedByPiotr = handIn("m1-link-3", annaId, ANNAS_LINK);
        UUID rejectedByAnna = handIn("m1-link-4", annaId, ANNAS_LINK);

        assertThat(post(piotr, byPiotr, "accept")).containsEntry("mailLink", WITHOUT_QUERY);
        assertThat(post(piotr, byPiotr, "accept")).as("again").containsEntry("mailLink", WITHOUT_QUERY);
        assertThat(post(anna, byAnna, "accept")).containsEntry("mailLink", ANNAS_LINK);
        assertThat(post(anna, byAnna, "accept")).as("again").containsEntry("mailLink", ANNAS_LINK);
        assertThat(post(piotr, rejectedByPiotr, "reject")).containsEntry("mailLink", WITHOUT_QUERY);
        assertThat(post(piotr, rejectedByPiotr, "reject")).as("again").containsEntry("mailLink", WITHOUT_QUERY);
        assertThat(post(anna, rejectedByAnna, "reject")).containsEntry("mailLink", ANNAS_LINK);
        assertThat(post(anna, rejectedByAnna, "reject")).as("again").containsEntry("mailLink", ANNAS_LINK);
    }

    @Test
    void theAddressNeverReachesTheOtherOwnerInAnyFieldOfAnAnswer() throws Exception {
        UUID id = handIn("m1-link-1", annaId, ANNAS_LINK);

        HttpResponse<String> accepted = http.postJsonAs(piotr, "/api/suggestions/" + id + "/accept", "");

        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.body()).doesNotContain("anna.mailbox").doesNotContain("authuser").doesNotContain("%40");
    }

    @Test
    void aLinkWithoutAQueryIsTheSameForBoth() throws Exception {
        UUID id = handIn("m1-link-1", annaId, PLAIN_LINK);

        assertThat(linkOf(anna, "", id)).isEqualTo(PLAIN_LINK);
        assertThat(linkOf(piotr, "", id)).isEqualTo(PLAIN_LINK);
    }

    @Test
    void aLinkIsShortenedOnlyAtAQueryThatComesBeforeTheFragment() throws Exception {
        String queryInTheFragment = "https://mail.google.com/mail/#search/from%3Ajan?x=1";
        UUID inFragment = handIn("m1-link-1", annaId, queryInTheFragment);
        UUID noFragment = handIn("m1-link-2", annaId, "https://mail.google.com/mail/?authuser=anna.mailbox%40planner.example");
        UUID otherPath = handIn("m1-link-3", annaId, "https://mail.google.com/mail/u/1/?authuser=anna.mailbox%40planner.example#all/abc");

        assertThat(linkOf(piotr, "", inFragment)).isEqualTo(queryInTheFragment);
        assertThat(linkOf(piotr, "", noFragment)).isEqualTo("https://mail.google.com/mail/");
        assertThat(linkOf(piotr, "", otherPath)).isEqualTo("https://mail.google.com/mail/u/1/#all/abc");
    }

    private String linkOf(String token, String query, UUID id) throws Exception {
        HttpResponse<String> response = http.getAs(token, "/api/suggestions" + (query.isEmpty() ? "?state=ALL" : query));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (String) TestHttp.jsonList(response).stream()
                .filter(one -> id.toString().equals(one.get("id")))
                .findFirst()
                .orElseThrow()
                .get("mailLink");
    }

    private Map<String, Object> post(String token, UUID id, String what) throws Exception {
        HttpResponse<String> response = http.postJsonAs(token, "/api/suggestions/" + id + "/" + what, "");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }
}
