package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.suggestions.Proposal;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S05 section 3: an entry says which suggestion made it, whether the import added it by itself or a person accepted it.
 * An entry made by hand says nothing, editing keeps what it says, and no request can say it from outside.
 */
class EntriesFromSuggestionsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    TestHttp http;
    String anna;
    String annaId;
    UUID weddingId;
    int keys;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        annaId = wedding.annaId;
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    @Test
    void anEntryAddedByItselfSaysWhichSuggestionMadeIt() throws Exception {
        UUID id = handIn(Kind.TASK, "Book the photographer", 90);

        Map<String, Object> entry = TestHttp.json(http.getAs(anna, "/api/entries/" + madeEntry(id)));

        assertThat(entry).containsEntry("fromSuggestionId", id.toString());
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/entries")).get(0)).containsEntry("fromSuggestionId", id.toString());
    }

    @Test
    void anEntryThatAPersonAcceptedSaysSoToo() throws Exception {
        UUID id = handIn(Kind.TASK, "Book the photographer", 40);
        HttpResponse<String> accepted = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", "");
        assertThat(accepted.statusCode()).isEqualTo(200);

        Map<String, Object> entry = TestHttp.json(http.getAs(anna, "/api/entries/" + TestHttp.json(accepted).get("madeEntryId")));

        assertThat(entry).containsEntry("fromSuggestionId", id.toString());
    }

    @Test
    void aPaymentThatAPersonAcceptedAlsoSaysWhereItCameFrom() throws Exception {
        Proposal payment = new Proposal(weddingId, "pay-1", Kind.PAYMENT, "Deposit", "", null, null, 100_000L,
                Proposal.PaymentKind.DEPOSIT, null, null, null, null, null, null, "Jan", "jan@palace-hill.example", "Deposit",
                Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(annaId), "https://mail.google.com/mail/u/0/#inbox/1",
                "Pay by Friday.", 99);
        UUID id = suggestions.handIn(payment);
        HttpResponse<String> accepted = http.postJsonAs(anna, "/api/suggestions/" + id + "/accept", "");

        assertThat(TestHttp.json(http.getAs(anna, "/api/entries/" + TestHttp.json(accepted).get("madeEntryId"))))
                .containsEntry("fromSuggestionId", id.toString()).containsEntry("amount", 100_000);
    }

    @Test
    void anEntryMadeByHandSaysNothingInEveryAnswerThatShowsAnEntry() throws Exception {
        Map<String, Object> made = created(entry("TASK", "Buy rings"));

        assertThat(made).containsKey("fromSuggestionId").containsEntry("fromSuggestionId", null);
        assertThat(TestHttp.json(http.getAs(anna, "/api/entries/" + made.get("id")))).containsEntry("fromSuggestionId", null);
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/entries"))).allSatisfy(e -> assertThat(e).containsEntry("fromSuggestionId", null));
        Map<String, Object> home = TestHttp.json(http.getAs(anna, "/api/home"));
        assertThat(home.toString()).contains("fromSuggestionId=null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theHomePageAnswersItForAnEntryThatCameFromMail() throws Exception {
        UUID id = handIn(Kind.TASK, "Book the photographer", 90);

        Map<String, Object> home = TestHttp.json(http.getAs(anna, "/api/home"));

        List<Map<String, Object>> shown = (List<Map<String, Object>>) home.get("next");
        assertThat(shown).extracting(e -> e.get("fromSuggestionId")).containsExactly(id.toString());
    }

    @Test
    void editingAnEntryKeepsWhereItCameFrom() throws Exception {
        UUID id = handIn(Kind.TASK, "Book the photographer", 90);
        Map<String, Object> saved = TestHttp.json(http.getAs(anna, "/api/entries/" + madeEntry(id)));
        Map<String, Object> change = new HashMap<>();
        for (String field : List.of("version", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds", "priorityMode", "parentId")) {
            change.put(field, saved.get(field));
        }
        change.put("priority", null);
        change.put("title", "Book the photographer for the whole day");

        HttpResponse<String> edited = http.putJsonAs(anna, "/api/entries/" + saved.get("id"), TestHttp.jsonOf(change));
        HttpResponse<String> ticked = http.putJsonAs(anna, "/api/entries/" + saved.get("id") + "/status", "{\"status\":\"DONE\"}");
        HttpResponse<String> converted = http.postJsonAs(anna, "/api/entries/" + saved.get("id") + "/convert", "{\"kind\":\"PLAN_ITEM\"}");

        for (HttpResponse<String> response : List.of(edited, ticked, converted)) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(TestHttp.json(response)).containsEntry("fromSuggestionId", id.toString());
        }
        assertThat(TestHttp.json(converted)).containsEntry("title", "Book the photographer for the whole day");
    }

    @Test
    void noRequestOfTheEntrysOwnApiCanSayWhereAnEntryCameFrom() throws Exception {
        UUID someSuggestion = handIn(Kind.TASK, "Needs a look", 40);
        Map<String, Object> request = entry("TASK", "Buy rings");
        request.put("fromSuggestionId", someSuggestion.toString());

        Map<String, Object> made = created(request);

        assertThat(made).containsEntry("fromSuggestionId", null);
        Map<String, Object> change = new HashMap<>();
        for (String field : List.of("version", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds", "priorityMode", "parentId")) {
            change.put(field, made.get(field));
        }
        change.put("priority", null);
        change.put("fromSuggestionId", someSuggestion.toString());
        HttpResponse<String> edited = http.putJsonAs(anna, "/api/entries/" + made.get("id"), TestHttp.jsonOf(change));
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(TestHttp.json(edited)).containsEntry("fromSuggestionId", null);
        assertThat(jdbc.queryForObject("SELECT from_suggestion_id FROM entry WHERE id = ?", UUID.class, UUID.fromString((String) made.get("id"))))
                .isNull();
    }

    private UUID handIn(Kind kind, String title, int confidence) {
        return suggestions.handIn(new Proposal(
                weddingId, "mail-" + (++keys), kind, title, "", null, null, null, null, null, null, null, null, null, null, "Jan",
                "jan@palace-hill.example", "Your tasting", Instant.parse("2026-11-12T09:30:00Z"), UUID.fromString(annaId),
                "https://mail.google.com/mail/u/0/#inbox/18c0ffee00000001", "We could meet on Friday.", confidence));
    }

    private UUID madeEntry(UUID suggestion) {
        return jdbc.queryForObject("SELECT made_entry_id FROM suggestion WHERE id = ?", UUID.class, suggestion);
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private static Map<String, Object> entry(String kind, String title) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("kind", kind);
        entry.put("title", title);
        entry.put("notes", "");
        entry.put("categoryId", null);
        entry.put("startDate", null);
        entry.put("dueDate", null);
        entry.put("status", "NOT_STARTED");
        entry.put("assigneeIds", List.of());
        entry.put("priorityMode", "AUTO");
        entry.put("priority", null);
        entry.put("parentId", null);
        return entry;
    }
}
