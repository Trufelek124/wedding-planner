package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.PaymentKind;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the tests of S05 share: a fresh wedding of Anna and Piotr, proposals written the short way and the calls of
 * both owners. Everything is made up: senders are at .example addresses and the mail never existed.
 */
abstract class AddedByItselfTestBase extends PostgresIntegrationTest {

    static final String ANNAS_LINK = "https://mail.google.com/mail/?authuser=anna.mailbox%40planner.example#all/18c0ffee00000001";
    static final String PIOTRS_LINK = "https://mail.google.com/mail/?authuser=piotr.mailbox%40planner.example#all/18c0ffee00000002";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Suggestions suggestions;

    TestHttp http;
    String anna;
    String piotr;
    String annaId;
    String piotrId;
    UUID weddingId;
    int keys;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
        annaId = wedding.annaId;
        piotrId = wedding.piotrId;
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        keys = 0;
    }

    /** A proposal of Anna's inbox, sure to the given percent; change what a test is about with the withers. */
    Proposal proposal(Kind kind, String title, int confidence) {
        return new Proposal(
                weddingId, "mail-" + (++keys), kind, title, "", null, null, null, null, null, null, null, null, null, null,
                "Jan from Palace on the Hill", "jan@palace-hill.example", "Your tasting", Instant.parse("2026-11-12T09:30:00Z"),
                UUID.fromString(annaId), ANNAS_LINK, "We could meet on Friday.", confidence);
    }

    static Proposal due(Proposal p, LocalDate day) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), day, p.amount(),
                p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(),
                p.vendorWebsite(), p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(),
                p.mailLink(), p.passage(), p.confidence());
    }

    static Proposal starting(Proposal p, LocalDate day) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), day, p.dueDate(), p.amount(),
                p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(),
                p.vendorWebsite(), p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(),
                p.mailLink(), p.passage(), p.confidence());
    }

    static Proposal noted(Proposal p, String notes) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), notes, p.startDate(),
                p.dueDate(), p.amount(), p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(),
                p.vendorPhone(), p.vendorWebsite(), p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(),
                p.inboxMemberId(), p.mailLink(), p.passage(), p.confidence());
    }

    static Proposal money(Proposal p, Long amount, PaymentKind kind) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(), amount,
                kind, p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(), p.vendorWebsite(),
                p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(), p.mailLink(), p.passage(),
                p.confidence());
    }

    static Proposal category(Proposal p, UUID category) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(),
                p.amount(), p.paymentKind(), category, p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(),
                p.vendorWebsite(), p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(),
                p.mailLink(), p.passage(), p.confidence());
    }

    /** The same proposal, its passage found in a document that came with the mail (D-014). */
    static Proposal fromDocument(Proposal p) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(), p.amount(),
                p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(), p.vendorWebsite(),
                p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(), p.mailLink(), p.passage(),
                p.confidence(), true);
    }

    static Proposal withVendor(Proposal p, UUID vendor) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(), p.amount(),
                p.paymentKind(), p.categoryId(), vendor, p.vendorName(), p.vendorEmail(), p.vendorPhone(), p.vendorWebsite(),
                p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), p.inboxMemberId(), p.mailLink(), p.passage(),
                p.confidence(), p.fromDocument());
    }

    /** A payment that a document names, sure to the given percent, of this amount and due on this day (null: no day). */
    Proposal paymentFromADocument(String title, long amount, LocalDate day, int confidence) {
        return fromDocument(due(money(proposal(Kind.PAYMENT, title, confidence), amount, PaymentKind.DEPOSIT), day));
    }

    /** The same proposal as if it was found in Piotr's mailbox. */
    Proposal inPiotrsInbox(Proposal p) {
        return new Proposal(p.weddingId(), p.sourceKey(), p.kind(), p.title(), p.notes(), p.startDate(), p.dueDate(),
                p.amount(), p.paymentKind(), p.categoryId(), p.vendorId(), p.vendorName(), p.vendorEmail(), p.vendorPhone(),
                p.vendorWebsite(), p.senderName(), p.senderAddress(), p.subject(), p.receivedAt(), UUID.fromString(piotrId),
                PIOTRS_LINK, p.passage(), p.confidence());
    }

    /** A task the import is sure of: added by itself. */
    UUID added(String title) {
        UUID id = suggestions.handIn(proposal(Kind.TASK, title, 90));
        assertThat(state(id)).isEqualTo("ADDED");
        return id;
    }

    /** A step of the plan the import is sure of: added by itself. */
    UUID addedStep(String title) {
        UUID id = suggestions.handIn(proposal(Kind.PLAN_ITEM, title, 90));
        assertThat(state(id)).isEqualTo("ADDED");
        return id;
    }

    String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM suggestion WHERE id = ?", String.class, id);
    }

    String entryOf(UUID suggestion) {
        UUID entry = jdbc.queryForObject("SELECT made_entry_id FROM suggestion WHERE id = ?", UUID.class, suggestion);
        return entry == null ? null : entry.toString();
    }

    HttpResponse<String> post(String token, String path) throws Exception {
        return http.postJsonAs(token, path, "");
    }

    Map<String, Object> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    Map<String, Object> keep(String token, UUID id) throws Exception {
        return ok(post(token, "/api/suggestions/" + id + "/keep"));
    }

    Map<String, Object> undo(String token, UUID id) throws Exception {
        return ok(post(token, "/api/suggestions/" + id + "/undo"));
    }

    Map<String, Object> one(String token, UUID id) throws Exception {
        return ok(http.getAs(token, "/api/suggestions/" + id));
    }

    /** Asks for a call that must be refused with this status and this kind of problem. */
    void assertProblem(HttpResponse<String> response, int status, String slug) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:" + slug);
    }

    List<String> ids(String token, String query) throws Exception {
        return TestHttp.jsonList(ok2(http.getAs(token, "/api/suggestions" + query))).stream()
                .map(one -> (String) one.get("id"))
                .toList();
    }

    private HttpResponse<String> ok2(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response;
    }

    List<Map<String, Object>> entries() throws Exception {
        return TestHttp.jsonList(ok2(http.getAs(anna, "/api/entries")));
    }

    Map<String, Object> entry(String id) throws Exception {
        return ok(http.getAs(anna, "/api/entries/" + id));
    }

    int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM entry", Integer.class);
    }

    /** The body of a save that changes only the title of an entry, based on the version it has now. */
    Map<String, Object> retitled(Map<String, Object> saved, String title) {
        Map<String, Object> change = new HashMap<>();
        for (String field : List.of("version", "title", "notes", "categoryId", "startDate", "dueDate", "status", "assigneeIds", "priorityMode", "parentId")) {
            change.put(field, saved.get(field));
        }
        change.put("priority", null);
        change.put("title", title);
        return change;
    }

    /** What a person sends to add an entry by hand. */
    static Map<String, Object> newEntry(String kind, String title) {
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

    Map<String, Object> made(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }
}
