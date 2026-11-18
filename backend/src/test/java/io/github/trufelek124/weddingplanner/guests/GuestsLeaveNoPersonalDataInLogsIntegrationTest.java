package io.github.trufelek124.weddingplanner.guests;

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
 * Everything about a guest is personal data. With the most talkative logging, saving, reading,
 * changing, refusing and deleting a guest or a household must leave none of it in the log. At TRACE
 * the web framework prints what every endpoint is called with, through toString(): that is where a
 * record without its guard would give a guest away.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
    "logging.level.io.github.trufelek124.weddingplanner=TRACE",
    "logging.level.org.springframework.web=TRACE",
    "logging.level.org.springframework.jdbc=DEBUG"
})
class GuestsLeaveNoPersonalDataInLogsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void nothingOfAGuestOrAHouseholdReachesTheLog(CapturedOutput output) throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        TestHttp http = wedding.http;

        HttpResponse<String> household = http.postJsonAs(wedding.anna, "/api/households", "{\"name\":\"Rodzina Wisniewskich\"}");
        assertThat(household.statusCode()).as(household.body()).isEqualTo(201);
        String householdId = (String) TestHttp.json(household).get("id");
        assertThat(http.putJsonAs(wedding.anna, "/api/households/" + householdId, "{\"name\":\"Wisniewscy z Gdyni\"}").statusCode()).isEqualTo(200);

        Map<String, Object> guest = new HashMap<>();
        guest.put("name", "Bronislawa Wisniewska");
        guest.put("sideMemberId", wedding.annaId);
        guest.put("householdId", householdId);
        guest.put("status", "INVITED");
        guest.put("plusOne", true);
        guest.put("children", 1);
        guest.put("dietaryNeeds", "Coeliac, strictly");
        guest.put("needsAccommodation", true);
        guest.put("phone", "+48 622 333 444");
        guest.put("email", "bronka@wisniewscy.example");
        guest.put("notes", "Uses a wheelchair");
        HttpResponse<String> created = http.postJsonAs(wedding.anna, "/api/guests", TestHttp.jsonOf(guest));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = (String) TestHttp.json(created).get("id");

        http.getAs(wedding.piotr, "/api/guests/" + id);
        http.getAs(wedding.piotr, "/api/guests");
        http.getAs(wedding.piotr, "/api/guests/counts");
        http.getAs(wedding.piotr, "/api/households");
        assertThat(http.putJsonAs(wedding.piotr, "/api/guests/" + id + "/status", "{\"status\":\"CONFIRMED\"}").statusCode()).isEqualTo(200);
        guest.put("version", 1);
        guest.put("notes", "Uses a wheelchair; ramp at the back");
        assertThat(http.putJsonAs(wedding.anna, "/api/guests/" + id, TestHttp.jsonOf(guest)).statusCode()).isEqualTo(200);
        // Refusals go through the error handling, which logs too.
        guest.put("email", "bronka at wisniewscy");
        assertThat(http.putJsonAs(wedding.anna, "/api/guests/" + id, TestHttp.jsonOf(guest)).statusCode()).isEqualTo(400);
        guest.put("email", "bronka@wisniewscy.example");
        guest.put("version", 99);
        assertThat(http.putJsonAs(wedding.anna, "/api/guests/" + id, TestHttp.jsonOf(guest)).statusCode()).isEqualTo(409);
        assertThat(http.deleteAs(wedding.anna, "/api/guests/" + id).statusCode()).isEqualTo(204);
        assertThat(http.deleteAs(wedding.anna, "/api/households/" + householdId).statusCode()).isEqualTo(204);

        String log = output.getAll();
        assertThat(log).as("the log was captured, with what the endpoints were called with")
                .contains("/api/guests")
                .contains("NewGuest[status=INVITED]")
                .contains("GuestChange[version=1, status=INVITED]")
                .contains("HouseholdName[]");
        assertThat(log)
                .doesNotContain("Wisniewsk")
                .doesNotContain("wisniewsc")
                .doesNotContain("Wisniewscy")
                .doesNotContain("Bronislawa")
                .doesNotContain("bronka")
                .doesNotContain("622 333 444")
                .doesNotContain("Coeliac")
                .doesNotContain("wheelchair")
                .doesNotContain("Gdyni");
    }
}
