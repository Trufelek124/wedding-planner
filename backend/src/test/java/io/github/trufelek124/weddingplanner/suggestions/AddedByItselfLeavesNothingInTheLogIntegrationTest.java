package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import java.time.Instant;
import java.time.LocalDate;
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
 * An entry that the import adds by itself is made of somebody else's words. With the most talkative logging, adding,
 * keeping, undoing and being refused an undo must leave none of it in the log: not the title, the notes, the sender,
 * the passage or the link. The log may say that a suggestion was added, for whose inbox, and of which kind.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.web=TRACE",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class AddedByItselfLeavesNothingInTheLogIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    @Test
    void nothingOfWhatWasAddedByItselfReachesTheLog(CapturedOutput output) throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TestHttp http = wedding.http;
        UUID weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        UUID kept = handIn(weddingId, wedding, "k-1", Kind.TASK, "Choose the Lilac Meadow napkins", 92);
        UUID undone = handIn(weddingId, wedding, "k-2", Kind.PLAN_ITEM, "Lilac Meadow tasting", 92);
        UUID changed = handIn(weddingId, wedding, "k-3", Kind.TASK, "Lilac Meadow parking", 92);
        UUID waiting = handIn(weddingId, wedding, "k-4", Kind.TASK, "Lilac Meadow band", 40);
        UUID refused = suggestions.handIn(new Proposal(weddingId, "k-5", Kind.TASK, "Lilac Meadow bad dates", "Marzipan notes",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1), null, null, null, null, null, null, null, null,
                "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject", Instant.parse("2026-11-12T09:30:00Z"),
                UUID.fromString(wedding.annaId), "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002",
                "Quoted passage about marzipan", 99));

        // D-014: a payment that a document names is added by itself too; its words stay out of the log like the others'.
        UUID payment = suggestions.handIn(new Proposal(weddingId, "k-6", Kind.PAYMENT, "Lilac Meadow deposit", "Marzipan notes",
                null, LocalDate.of(2026, 12, 1), 250_000L, Proposal.PaymentKind.DEPOSIT, null, null, null, null, null, null,
                "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject", Instant.parse("2026-11-12T09:30:00Z"),
                UUID.fromString(wedding.annaId), "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002",
                "Quoted passage about marzipan from the offer", 95, true));
        assertThat(jdbc.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, payment)).isEqualTo("ADDED");
        assertThat(http.postJsonAs(wedding.piotr, "/api/suggestions/" + payment + "/keep", "").statusCode()).isEqualTo(200);
        UUID second = suggestions.handIn(new Proposal(weddingId, "k-7", Kind.PAYMENT, "Lilac Meadow second deposit", "Marzipan notes",
                null, LocalDate.of(2026, 12, 1), 250_000L, Proposal.PaymentKind.DEPOSIT, null, null, null, null, null, null,
                "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject", Instant.parse("2026-11-12T09:30:00Z"),
                UUID.fromString(wedding.annaId), "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002",
                "Quoted passage about marzipan from the offer", 95, true));
        assertThat(jdbc.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, second)).as("the same payment waits").isEqualTo("WAITING");

        assertThat(http.getAs(wedding.anna, "/api/suggestions?state=ALL").statusCode()).isEqualTo(200);
        assertThat(http.getAs(wedding.piotr, "/api/suggestions/" + kept).statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(wedding.piotr, "/api/suggestions/" + kept + "/keep", "").statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + undone + "/undo", "").statusCode()).isEqualTo(200);
        String changedEntry = jdbc.queryForObject("SELECT made_entry_id FROM suggestion WHERE id = ?", String.class, changed);
        assertThat(http.putJsonAs(wedding.anna, "/api/entries/" + changedEntry + "/status", "{\"status\":\"DONE\"}").statusCode()).isEqualTo(200);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + changed + "/undo", "").statusCode()).isEqualTo(409);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + kept + "/undo", "").statusCode()).isEqualTo(409);
        assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + waiting + "/keep", "").statusCode()).isEqualTo(400);
        assertThat(http.putJsonAs(wedding.anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":70}").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, refused)).isEqualTo("WAITING");

        String log = output.getAll();
        assertThat(log).as("the log was captured, and says that something was added by itself, for whom, and what kind")
                .contains("was added by itself for the inbox of member " + wedding.annaId)
                .contains("(TASK)")
                .contains("(PAYMENT)")
                .contains("/api/suggestions");
        assertThat(log)
                .doesNotContain("Quillfeather")
                .doesNotContain("Oskar")
                .doesNotContain("oskar@lilac-meadow.example")
                .doesNotContain("lilac-meadow")
                .doesNotContain("Lilac Meadow")
                .doesNotContain("napkins")
                .doesNotContain("Quill subject")
                .doesNotContain("marzipan")
                .doesNotContain("Marzipan")
                .doesNotContain("mail.google.com/mail/u/0/#inbox");
    }

    private UUID handIn(UUID weddingId, TestWedding wedding, String key, Kind kind, String title, int confidence) {
        return suggestions.handIn(new Proposal(
                weddingId, key, kind, title, "Marzipan notes in the proposal", null, null, null, null, null, null, null, null,
                null, null, "Oskar Quillfeather", "oskar@lilac-meadow.example", "Quill subject",
                Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(wedding.annaId),
                "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000002", "Quoted passage about marzipan", confidence));
    }
}
