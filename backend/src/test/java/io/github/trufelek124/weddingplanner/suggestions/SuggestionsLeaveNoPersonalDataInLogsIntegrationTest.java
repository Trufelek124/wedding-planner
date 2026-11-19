package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.api.ApiException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A suggestion holds parts of someone's mail. With the most talkative logging, handing one in, listing, accepting
 * with changes, refusing and rejecting must leave none of it in the log. At TRACE the web framework prints what every
 * endpoint is called with, through toString(): that is where a record without its guard would give the mail away.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.web=TRACE",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class SuggestionsLeaveNoPersonalDataInLogsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    @Test
    void nothingOfASuggestionReachesTheLog(CapturedOutput output) throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TestHttp http = wedding.http;
        UUID weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        UUID accepted = handIn(weddingId, wedding, "k-1", Proposal.Kind.PAYMENT, "Deposit for Lilac Meadow Barn");
        UUID rejected = handIn(weddingId, wedding, "k-2", Proposal.Kind.TASK, "Choose the Lilac Meadow napkins");
        UUID vendor = handIn(weddingId, wedding, "k-3", Proposal.Kind.VENDOR, "Lilac Meadow Barn");
        UUID refused = handIn(weddingId, wedding, "k-4", Proposal.Kind.PAYMENT, "Lilac Meadow second deposit");
        // A hand-in that is refused logs too, if anything does.
        try {
            suggestions.handIn(new Proposal(weddingId, "k-5", Proposal.Kind.TASK, "Lilac Meadow bad link", "", null, null, null, null,
                    null, null, null, null, null, null, "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject",
                    Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(wedding.annaId), "https://lilac-meadow.example/x",
                    "Quoted passage about marzipan", 50));
        } catch (ApiException expected) {
            // Refused, as it should be.
        }

        http.getAs(wedding.anna, "/api/suggestions?state=ALL");
        HttpResponse<String> acceptedNow = http.postJsonAs(wedding.piotr, "/api/suggestions/" + accepted + "/accept",
                TestHttp.jsonOf(Map.of("title", "Deposit for Lilac Meadow Barn, changed", "notes", "Marzipan notes", "amount", 250_000)));
        assertThat(acceptedNow.statusCode()).as(acceptedNow.body()).isEqualTo(200);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + vendor + "/accept", "").statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + rejected + "/reject", "").statusCode()).isEqualTo(200);
        // A refusal of an acceptance, and of the other decision, go through the error handling, which logs too.
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + refused + "/accept", "").statusCode()).isEqualTo(400);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + rejected + "/accept", "").statusCode()).isEqualTo(409);

        String log = output.getAll();
        assertThat(log).as("the log was captured, with what the endpoints were called with")
                .contains("/api/suggestions")
                .contains("SuggestionAcceptance[]");
        assertThat(log)
                .doesNotContain("Quillfeather")
                .doesNotContain("Oskar")
                .doesNotContain("oskar@lilac-meadow.example")
                .doesNotContain("lilac-meadow")
                .doesNotContain("Lilac Meadow")
                .doesNotContain("Quill subject")
                .doesNotContain("marzipan")
                .doesNotContain("Marzipan")
                .doesNotContain("mail.google.com/mail/u/0/#inbox");
    }

    private UUID handIn(UUID weddingId, TestWedding wedding, String key, Proposal.Kind kind, String title) {
        return suggestions.handIn(new Proposal(
                weddingId, key, kind, title, "Marzipan notes in the proposal", null, null, null, null, null, null, null,
                "oskar@lilac-meadow.example", null, null, "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject",
                Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(wedding.annaId),
                "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002", "Quoted passage about marzipan", 70));
    }
}
