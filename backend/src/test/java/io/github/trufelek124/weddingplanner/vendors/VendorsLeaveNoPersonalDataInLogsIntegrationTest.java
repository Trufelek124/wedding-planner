package io.github.trufelek124.weddingplanner.vendors;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * A vendor's name and contact details are personal data. With the most talkative logging, saving,
 * reading, changing, refusing and deleting a vendor must leave none of them in the log. At TRACE
 * the web framework prints what every endpoint is called with, through toString(): that is where a
 * record without its guard would give the details away.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.web=TRACE",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class VendorsLeaveNoPersonalDataInLogsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Vendors vendors;

    @Test
    void nothingOfAVendorReachesTheLog(CapturedOutput output) throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TestHttp http = wedding.http;
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", "Fotografia Zielinska");
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "Halina Zielinska");
        vendor.put("phone", "+48 611 222 333");
        vendor.put("email", "halina@zielinska.example");
        vendor.put("website", "https://zielinska.example");
        vendor.put("address", "Ogrodowa 7, Zmyslowo");
        vendor.put("agreedPrice", 450_000);
        vendor.put("notes", "Prefers a call after six");

        HttpResponse<String> created = http.postJsonAs(wedding.anna, "/api/vendors", TestHttp.jsonOf(vendor));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = (String) TestHttp.json(created).get("id");
        http.getAs(wedding.piotr, "/api/vendors/" + id);
        http.getAs(wedding.piotr, "/api/vendors");
        vendor.put("version", 0);
        vendor.put("status", "BOOKED");
        assertThat(http.putJsonAs(wedding.anna, "/api/vendors/" + id, TestHttp.jsonOf(vendor)).statusCode()).isEqualTo(200);
        // A refusal goes through the error handling, which logs too.
        vendor.put("email", "halina at zielinska");
        assertThat(http.putJsonAs(wedding.anna, "/api/vendors/" + id, TestHttp.jsonOf(vendor)).statusCode()).isEqualTo(400);
        vendor.put("version", 99);
        vendor.put("email", "halina@zielinska.example");
        assertThat(http.putJsonAs(wedding.anna, "/api/vendors/" + id, TestHttp.jsonOf(vendor)).statusCode()).isEqualTo(409);
        // The lookups the mail reader makes by address and by name go through a statement parameter only.
        java.util.UUID weddingId = jdbc.queryForObject("SELECT id FROM wedding", java.util.UUID.class);
        assertThat(vendors.withAddress(weddingId, "Halina@Zielinska.example")).isPresent();
        assertThat(vendors.withAddress(weddingId, "someone-else@zielinska.example")).isEmpty();
        assertThat(vendors.named(weddingId, "fotografia zielinska")).isPresent();
        assertThat(http.deleteAs(wedding.anna, "/api/vendors/" + id).statusCode()).isEqualTo(204);

        String log = output.getAll();
        assertThat(log).as("the log was captured, with what the endpoints were called with")
                .contains("/api/vendors")
                .contains("NewVendor[status=CONSIDERING]")
                .contains("VendorChange[version=0, status=BOOKED]");
        assertThat(log)
                .doesNotContain("Zielinska")
                .doesNotContain("zielinska")
                .doesNotContain("Halina")
                .doesNotContain("611 222 333")
                .doesNotContain("Ogrodowa")
                .doesNotContain("after six");
    }
}
