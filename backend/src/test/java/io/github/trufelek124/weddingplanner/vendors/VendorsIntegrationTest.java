package io.github.trufelek124.weddingplanner.vendors;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The register of vendors, and what the plan and the inspirations hang on a vendor. All names are made up. */
class VendorsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    // ---------------------------------------------------------------- the register

    @Test
    void everyFieldOfAVendorComesBackAsSaved() throws Exception {
        String venue = categoryId("Venue");
        Map<String, Object> request = vendor("  Dwór pod Lipami ");
        request.put("categoryId", venue);
        request.put("status", "BOOKED");
        request.put("contactPerson", "Maria Example");
        request.put("phone", "+48 600 000 000");
        request.put("email", "maria@dwor.example");
        request.put("website", "https://dwor.example/wesela");
        request.put("address", "Lipowa 1, 00-001 Przykładowo");
        request.put("agreedPrice", 2_000_000);
        request.put("notes", "Garden only until 22:00.");

        Map<String, Object> saved = created(request);
        Map<String, Object> read = ok(http.getAs(piotr, "/api/vendors/" + id(saved)));

        assertThat(read).isEqualTo(saved);
        assertThat(read)
                .containsEntry("name", "Dwór pod Lipami")
                .containsEntry("categoryId", venue)
                .containsEntry("status", "BOOKED")
                .containsEntry("contactPerson", "Maria Example")
                .containsEntry("phone", "+48 600 000 000")
                .containsEntry("email", "maria@dwor.example")
                .containsEntry("website", "https://dwor.example/wesela")
                .containsEntry("address", "Lipowa 1, 00-001 Przykładowo")
                .containsEntry("agreedPrice", 2_000_000)
                .containsEntry("notes", "Garden only until 22:00.")
                .containsEntry("version", 0);
        // Nothing points to it yet, and the answer says so rather than leaving the fields out.
        assertThat(read)
                .containsEntry("planItems", 0).containsEntry("tasks", 0).containsEntry("payments", 0)
                .containsEntry("committed", 0).containsEntry("paid", 0).containsEntry("inspirations", 0);
    }

    @Test
    void aVendorNeedsOnlyANameAndAStatus() throws Exception {
        Map<String, Object> saved = created(vendor("A florist from the market"));

        assertThat(saved)
                .containsEntry("status", "CONSIDERING")
                .containsEntry("categoryId", null)
                .containsEntry("contactPerson", "")
                .containsEntry("phone", "")
                .containsEntry("email", "")
                .containsEntry("website", "")
                .containsEntry("address", "")
                .containsEntry("agreedPrice", null)
                .containsEntry("notes", "");
    }

    @Test
    void theListIsByNameAndNarrowsDownByStatusAndCategory() throws Exception {
        String music = categoryId("Music");
        created(with(vendor("zespół Echo"), "categoryId", music, "status", "BOOKED"));
        created(with(vendor("DJ Antek"), "categoryId", music, "status", "DROPPED"));
        created(with(vendor("Bistro Catering"), "status", "CONSIDERING"));

        assertThat(names("")).containsExactly("Bistro Catering", "DJ Antek", "zespół Echo");
        assertThat(names("?status=BOOKED")).containsExactly("zespół Echo");
        assertThat(names("?status=BOOKED&status=CONSIDERING")).containsExactly("Bistro Catering", "zespół Echo");
        assertThat(names("?categoryId=" + music)).containsExactly("DJ Antek", "zespół Echo");
        assertThat(names("?categoryId=" + music + "&status=DROPPED")).containsExactly("DJ Antek");
    }

    @Test
    void aChangeIsBasedOnAVersionAndAnOutdatedOneOverwritesNothing() throws Exception {
        Map<String, Object> saved = created(vendor("Bistro Catering"));

        Map<String, Object> booked = ok(http.putJsonAs(anna, "/api/vendors/" + id(saved),
                TestHttp.jsonOf(with(change(saved), "status", "BOOKED", "agreedPrice", 900_000))));
        assertThat(booked).containsEntry("status", "BOOKED").containsEntry("agreedPrice", 900_000).containsEntry("version", 1);

        // Piotr still has the first version on his screen.
        HttpResponse<String> late = http.putJsonAs(piotr, "/api/vendors/" + id(saved),
                TestHttp.jsonOf(with(change(saved), "name", "Another name")));
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(late).get("type")).endsWith(":changed-meanwhile");
        assertThat(ok(http.getAs(piotr, "/api/vendors/" + id(saved)))).containsEntry("name", "Bistro Catering").containsEntry("status", "BOOKED");
    }

    @Test
    void aWebAddressTypedWithoutItsBeginningGetsOne() throws Exception {
        assertThat(created(with(vendor("A"), "website", "www.kwiaty.example/oferta"))).containsEntry("website", "https://www.kwiaty.example/oferta");
        assertThat(created(with(vendor("B"), "website", "http://stara.example"))).containsEntry("website", "http://stara.example");
    }

    @Test
    void whatCannotBeAVendorIsRefusedWithWordsForThePerson() throws Exception {
        assertThat(refused(vendor("  "))).containsExactly("name: Enter a name.");
        assertThat(refused(with(vendor("A"), "status", null)))
                .containsExactly("status: Say whether you are considering this vendor, have booked it, or dropped it.");
        assertThat(refused(with(vendor("A"), "email", "maria at dwor")))
                .containsExactly("email: Enter an e-mail address like name@example.com, or leave it empty.");
        assertThat(refused(with(vendor("A"), "website", "javascript:alert(1)")))
                .containsExactly("website: Enter a web address like example.com, or leave it empty.");
        assertThat(refused(with(vendor("A"), "website", "ftp://files.example/x")))
                .containsExactly("website: Enter a web address like example.com, or leave it empty.");
        assertThat(refused(with(vendor("A"), "website", "not a site")))
                .containsExactly("website: Enter a web address like example.com, or leave it empty.");
        assertThat(refused(with(vendor("A"), "agreedPrice", -1))).containsExactly("agreedPrice: Enter an amount, or zero.");
        assertThat(refused(with(vendor("A"), "agreedPrice", 1_000_000_000_001L))).containsExactly("agreedPrice: That amount is too large.");
        assertThat(refused(with(vendor("A"), "categoryId", "00000000-0000-4000-8000-000000000000")))
                .containsExactly("categoryId: Choose one of the categories.");
        assertThat(refused(with(vendor("A"), "phone", "1".repeat(41)))).containsExactly("phone: Use at most 40 characters.");
        assertThat(names("")).isEmpty();
    }

    @Test
    void anAgreedPriceIsAWholeNumberOfTheSmallestUnitAndAFractionIsRefused() throws Exception {
        String request = TestHttp.jsonOf(with(vendor("A"), "agreedPrice", 7)).replace("\"agreedPrice\":7", "\"agreedPrice\":1999.99");

        assertThat(http.postJsonAs(anna, "/api/vendors", request).statusCode()).isEqualTo(400);
        assertThat(created(with(vendor("B"), "agreedPrice", 199_999))).containsEntry("agreedPrice", 199_999);
    }

    @Test
    void aCategoryThatGoesLeavesItsVendorsWithoutOne() throws Exception {
        String music = categoryId("Music");
        Map<String, Object> band = created(with(vendor("zespół Echo"), "categoryId", music));

        assertThat(http.deleteAs(anna, "/api/categories/" + music).statusCode()).isEqualTo(204);

        assertThat(ok(http.getAs(anna, "/api/vendors/" + id(band)))).containsEntry("categoryId", null);
    }

    @Test
    void aVendorThatIsNotThereIsNotFound() throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/vendors/00000000-0000-4000-8000-000000000000");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat((String) TestHttp.json(response).get("type")).endsWith(":not-found");
    }

    // ---------------------------------------------------------------- what points to a vendor

    @Test
    void aPlanItemATaskAndAnInspirationCanEachPointToAVendor() throws Exception {
        String venue = id(created(vendor("Dwór pod Lipami")));

        Map<String, Object> planItem = entry(with(newEntry("PLAN_ITEM", "Book the venue"), "vendorId", venue));
        Map<String, Object> task = entry(with(newEntry("TASK", "Ask about parking"), "vendorId", venue));
        Map<String, Object> idea = inspiration(with(newInspiration("https://dwor.example/galeria"), "vendorId", venue));
        entry(newEntry("TASK", "Something else"));
        inspiration(newInspiration("https://example.com/elsewhere"));

        assertThat(planItem).containsEntry("vendorId", venue);
        assertThat(task).containsEntry("vendorId", venue);
        assertThat(idea).containsEntry("vendorId", venue);
        // The vendor's page asks for exactly these.
        assertThat(titles(http.getAs(piotr, "/api/entries?vendorId=" + venue + "&sort=TITLE"))).containsExactly("Ask about parking", "Book the venue");
        assertThat(titles(http.getAs(piotr, "/api/entries?vendorId=" + venue + "&kind=PLAN_ITEM"))).containsExactly("Book the venue");
        assertThat(TestHttp.jsonList(http.getAs(piotr, "/api/inspirations?vendorId=" + venue)))
                .extracting(one -> one.get("url")).containsExactly("https://dwor.example/galeria");
    }

    @Test
    void whatIsNotOneOfTheVendorsCannotBePointedTo() throws Exception {
        String nobody = "00000000-0000-4000-8000-000000000000";

        HttpResponse<String> entry = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(with(newEntry("TASK", "Call"), "vendorId", nobody)));
        HttpResponse<String> idea = http.postJsonAs(anna, "/api/inspirations",
                TestHttp.jsonOf(with(newInspiration("https://example.com/x"), "vendorId", nobody)));

        assertThat(TestHttp.fieldMessages(entry)).containsExactly("vendorId: Choose one of your vendors.");
        assertThat(TestHttp.fieldMessages(idea)).containsExactly("vendorId: Choose one of your vendors.");
    }

    @Test
    void aVendorsPageDataCountsWhatPointsToItAndAddsItsPaymentsUp() throws Exception {
        Map<String, Object> venue = created(with(vendor("Dwór pod Lipami"), "agreedPrice", 2_000_000));
        String id = id(venue);
        entry(with(newEntry("PLAN_ITEM", "Book the venue"), "vendorId", id));
        entry(with(newEntry("TASK", "Pay the deposit"), "vendorId", id, "amount", 500_000, "status", "DONE"));
        entry(with(newEntry("TASK", "Pay the rest"), "vendorId", id, "amount", 1_500_000));
        entry(with(newEntry("TASK", "An extra that was given up"), "vendorId", id, "amount", 300_000, "status", "DROPPED"));
        entry(with(newEntry("TASK", "Ask about parking"), "vendorId", id));
        inspiration(with(newInspiration("https://dwor.example/galeria"), "vendorId", id));
        inspiration(with(newInspiration("https://dwor.example/ogrod"), "vendorId", id));
        // Another vendor's money stays its own.
        String band = id(created(vendor("zespół Echo")));
        entry(with(newEntry("TASK", "Pay the band"), "vendorId", band, "amount", 450_000));

        Map<String, Object> page = ok(http.getAs(piotr, "/api/vendors/" + id));

        assertThat(page)
                .containsEntry("agreedPrice", 2_000_000)
                .containsEntry("planItems", 1)
                .containsEntry("tasks", 4)
                .containsEntry("payments", 2)
                .containsEntry("committed", 2_000_000)
                .containsEntry("paid", 500_000)
                .containsEntry("inspirations", 2);
        // The list carries the same numbers, so that it can show them without asking vendor by vendor.
        Map<String, Object> inTheList = TestHttp.jsonList(http.getAs(piotr, "/api/vendors")).stream()
                .filter(one -> id.equals(one.get("id"))).findFirst().orElseThrow();
        assertThat(inTheList).isEqualTo(page);
        assertThat(ok(http.getAs(piotr, "/api/vendors/" + band))).containsEntry("committed", 450_000).containsEntry("paid", 0).containsEntry("tasks", 1);
    }

    // ---------------------------------------------------------------- deleting

    @Test
    void aVendorNothingPointsToIsDeletedAtOnce() throws Exception {
        String id = id(created(vendor("Bistro Catering")));

        assertThat(http.deleteAs(anna, "/api/vendors/" + id).statusCode()).isEqualTo(204);

        assertThat(http.getAs(anna, "/api/vendors/" + id).statusCode()).isEqualTo(404);
    }

    @Test
    void aVendorSomethingPointsToIsNotDeletedUntilTheCallerSaysWhatHappensToThoseLinks() throws Exception {
        String id = id(created(vendor("Dwór pod Lipami")));
        Map<String, Object> planItem = entry(with(newEntry("PLAN_ITEM", "Book the venue"), "vendorId", id));
        Map<String, Object> payment = entry(with(newEntry("TASK", "Pay the deposit"), "vendorId", id, "amount", 500_000));
        Map<String, Object> idea = inspiration(with(newInspiration("https://dwor.example/galeria"), "vendorId", id));

        HttpResponse<String> refusal = http.deleteAs(anna, "/api/vendors/" + id);

        assertThat(refusal.statusCode()).as(refusal.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(refusal).get("type")).endsWith(":vendor-in-use");
        assertThat(http.getAs(anna, "/api/vendors/" + id).statusCode()).isEqualTo(200);

        assertThat(http.deleteAs(anna, "/api/vendors/" + id + "?links=REMOVE").statusCode()).isEqualTo(204);

        // What pointed to it stays, with everything it had, and points to no vendor.
        assertThat(http.getAs(anna, "/api/vendors/" + id).statusCode()).isEqualTo(404);
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(planItem)))).containsEntry("title", "Book the venue").containsEntry("vendorId", null);
        assertThat(ok(http.getAs(anna, "/api/entries/" + id(payment)))).containsEntry("amount", 500_000).containsEntry("vendorId", null);
        assertThat(ok(http.getAs(anna, "/api/inspirations/" + id(idea)))).containsEntry("url", "https://dwor.example/galeria").containsEntry("vendorId", null);
    }

    @Test
    void anInspirationAloneIsEnoughToAskFirst() throws Exception {
        String id = id(created(vendor("A florist from the market")));
        inspiration(with(newInspiration("https://example.com/bouquet"), "vendorId", id));

        assertThat(http.deleteAs(anna, "/api/vendors/" + id).statusCode()).isEqualTo(409);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> vendor(String name) {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", "");
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        return vendor;
    }

    private static Map<String, Object> with(Map<String, Object> request, Object... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            request.put((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return request;
    }

    /** What a client sends to change a vendor it has read: every field, and the version it read. */
    private static Map<String, Object> change(Map<String, Object> saved) {
        Map<String, Object> request = new HashMap<>();
        for (String field : List.of("name", "categoryId", "status", "contactPerson", "phone", "email", "website", "address",
                "agreedPrice", "notes", "version")) {
            request.put(field, saved.get(field));
        }
        return request;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/vendors", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private List<String> refused(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/vendors", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return TestHttp.fieldMessages(response);
    }

    private List<String> names(String query) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/vendors" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(one -> (String) one.get("name")).toList();
    }

    private static Map<String, Object> newEntry(String kind, String title) {
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
        entry.put("vendorId", null);
        entry.put("amount", null);
        entry.put("paymentKind", null);
        entry.put("paidOn", null);
        return entry;
    }

    private Map<String, Object> entry(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/entries", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private static Map<String, Object> newInspiration(String url) {
        Map<String, Object> inspiration = new HashMap<>();
        inspiration.put("url", url);
        inspiration.put("boardId", null);
        inspiration.put("title", "");
        inspiration.put("note", "");
        inspiration.put("tags", List.of());
        inspiration.put("planItemId", null);
        inspiration.put("vendorId", null);
        return inspiration;
    }

    private Map<String, Object> inspiration(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/inspirations", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private static Map<String, Object> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    private static List<String> titles(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(entry -> (String) entry.get("title")).toList();
    }

    private static String id(Map<String, Object> saved) {
        return (String) saved.get("id");
    }

    private String categoryId(String name) throws Exception {
        return TestHttp.jsonList(http.getAs(anna, "/api/categories")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> (String) category.get("id"))
                .findFirst()
                .orElseThrow();
    }
}
