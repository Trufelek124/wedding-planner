package io.github.trufelek124.weddingplanner.vendors;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finding a vendor by its address or its name, for the part of the server that works without a signed-in person. */
class VendorLookupsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Vendors vendors;

    TestWedding wedding;
    UUID weddingId;

    @BeforeEach
    void aFreshWedding() throws Exception {
        wedding = TestWedding.fresh(jdbc, port);
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    private UUID add(String name, String email, String status) throws Exception {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", status);
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", email);
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        HttpResponse<String> created = wedding.http.postJsonAs(wedding.anna, "/api/vendors", TestHttp.jsonOf(vendor));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString((String) TestHttp.json(created).get("id"));
    }

    // ---------------------------------------------------------------- by address

    @Test
    void aVendorIsFoundByItsAddressWhateverTheCaseAndTheSpacesAroundIt() throws Exception {
        UUID palace = add("Palace on the Hill", "Events@Palace-Hill.example", "CONSIDERING");
        add("Garden Band", "band@garden.example", "CONSIDERING");

        assertThat(vendors.withAddress(weddingId, "events@palace-hill.example")).contains(palace);
        assertThat(vendors.withAddress(weddingId, "  EVENTS@PALACE-HILL.EXAMPLE\t")).contains(palace);
        assertThat(vendors.withAddress(weddingId, "events@palace-hill.example.")).isEmpty();
        assertThat(vendors.withAddress(weddingId, "nobody@palace-hill.example")).isEmpty();
    }

    @Test
    void twoVendorsWithOneAddressAreNoAnswer() throws Exception {
        add("Palace on the Hill", "events@palace-hill.example", "CONSIDERING");
        add("Palace on the Hill, the other hall", "Events@Palace-Hill.example", "BOOKED");

        assertThat(vendors.withAddress(weddingId, "events@palace-hill.example")).isEmpty();
    }

    @Test
    void aBlankAddressIsNoAnswerEvenWhenVendorsHaveNone() throws Exception {
        add("Palace on the Hill", "", "CONSIDERING");

        assertThat(vendors.withAddress(weddingId, "")).isEmpty();
        assertThat(vendors.withAddress(weddingId, "   ")).isEmpty();
        assertThat(vendors.withAddress(weddingId, null)).isEmpty();
    }

    @Test
    void anotherWeddingsIdFindsNothing() throws Exception {
        add("Palace on the Hill", "events@palace-hill.example", "CONSIDERING");

        assertThat(vendors.withAddress(UUID.randomUUID(), "events@palace-hill.example")).isEmpty();
        assertThat(vendors.named(UUID.randomUUID(), "Palace on the Hill")).isEmpty();
    }

    @Test
    void aDroppedVendorIsStillThatVendor() throws Exception {
        UUID dropped = add("Palace on the Hill", "events@palace-hill.example", "DROPPED");

        assertThat(vendors.withAddress(weddingId, "events@palace-hill.example")).contains(dropped);
        assertThat(vendors.named(weddingId, "Palace on the Hill")).contains(dropped);
    }

    // ---------------------------------------------------------------- by name

    @Test
    void aVendorIsFoundByItsNameWhateverTheCaseAndTheSpacesAroundIt() throws Exception {
        UUID palace = add("Palace on the Hill", "", "CONSIDERING");
        add("Garden Band", "", "CONSIDERING");

        assertThat(vendors.named(weddingId, "palace on the hill")).contains(palace);
        assertThat(vendors.named(weddingId, "  PALACE ON THE HILL ")).contains(palace);
        assertThat(vendors.named(weddingId, "Palace on the")).isEmpty();
        assertThat(vendors.named(weddingId, "Palace%")).as("no pattern").isEmpty();
        assertThat(vendors.named(weddingId, "Palace on_the Hill")).as("no pattern").isEmpty();
    }

    @Test
    void twoVendorsOfOneNameAreNoAnswerAndABlankNameIsNone() throws Exception {
        add("Palace on the Hill", "", "CONSIDERING");
        add("palace on the hill", "", "BOOKED");

        assertThat(vendors.named(weddingId, "Palace on the Hill")).isEmpty();
        assertThat(vendors.named(weddingId, " ")).isEmpty();
        assertThat(vendors.named(weddingId, null)).isEmpty();
    }
}
