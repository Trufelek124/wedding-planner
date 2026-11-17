package io.github.trufelek124.weddingplanner.wedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestSignIn;
import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

class WeddingIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Weddings weddings;

    String anna;
    String piotr;

    @BeforeEach
    void noWeddingYet() throws Exception {
        jdbc.update("DELETE FROM wedding");
        anna = TestSignIn.signIn(http(), ANNA).accessToken();
        piotr = TestSignIn.signIn(http(), PIOTR).accessToken();
    }

    @Test
    void untilSomeoneSetsItUpThereIsNoWedding() throws Exception {
        HttpResponse<String> response = http().getAs(anna, "/api/wedding");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:wedding-not-set-up");
    }

    @Test
    void theFirstOwnerSetsTheWeddingUpAndTakesTheFirstPlace() throws Exception {
        HttpResponse<String> response = http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr"));

        assertThat(response.statusCode()).isEqualTo(201);
        Map<String, Object> wedding = TestHttp.json(response);
        assertThat(wedding).containsOnlyKeys("id", "date", "currency", "timeZone", "members");
        assertThat(wedding)
                .containsEntry("date", "2027-06-19")
                .containsEntry("currency", "PLN")
                .containsEntry("timeZone", "Europe/Warsaw");
        assertThat(members(wedding)).hasSize(2);
        assertThat(withoutId(members(wedding).get(0))).isEqualTo(Map.of("displayName", "Anna", "you", true, "signedIn", true));
        assertThat(withoutId(members(wedding).get(1))).isEqualTo(Map.of("displayName", "Piotr", "you", false, "signedIn", false));
    }

    @Test
    void bothOwnersSeeTheSameWedding() throws Exception {
        Map<String, Object> created = TestHttp.json(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr")));

        Map<String, Object> asPiotr = TestHttp.json(http().getAs(piotr, "/api/wedding"));
        Map<String, Object> asAnna = TestHttp.json(http().getAs(anna, "/api/wedding"));

        for (String field : List.of("id", "date", "currency", "timeZone")) {
            assertThat(asPiotr.get(field)).as(field).isEqualTo(created.get(field)).isEqualTo(asAnna.get(field));
        }
        // The second owner took the place that was kept for him; each sees "you" on their own.
        assertThat(withoutId(members(asPiotr).get(1))).isEqualTo(Map.of("displayName", "Piotr", "you", true, "signedIn", true));
        assertThat(withoutId(members(asPiotr).get(0))).isEqualTo(Map.of("displayName", "Anna", "you", false, "signedIn", true));
        assertThat(withoutId(members(asAnna).get(0))).isEqualTo(Map.of("displayName", "Anna", "you", true, "signedIn", true));
        assertThat(withoutId(members(asAnna).get(1))).isEqualTo(Map.of("displayName", "Piotr", "you", false, "signedIn", true));
        assertThat(members(asPiotr).get(0).get("id")).isEqualTo(members(asAnna).get(0).get("id"));
    }

    @Test
    void theWeddingCanBeSetUpOnlyOnce() throws Exception {
        http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr"));

        for (String who : List.of(anna, piotr)) {
            HttpResponse<String> again = http().postJsonAs(who, "/api/wedding", setup("2028-01-01", "EUR", "Europe/Kyiv", "X", "Y"));

            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(TestHttp.json(again)).containsEntry("type", "urn:wedding-planner:problem:wedding-already-set-up");
        }
        assertThat(TestHttp.json(http().getAs(anna, "/api/wedding"))).containsEntry("date", "2027-06-19").containsEntry("currency", "PLN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wedding", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wedding_member", Integer.class)).isEqualTo(2);
    }

    @Test
    void theDayMayStayOpen() throws Exception {
        Map<String, Object> wedding = TestHttp.json(http().postJsonAs(anna, "/api/wedding", setup(null, "PLN", "Europe/Warsaw", "Anna", "Piotr")));

        assertThat(wedding).containsEntry("date", null);
        assertThat(TestHttp.json(http().getAs(piotr, "/api/wedding"))).containsEntry("date", null);
    }

    @Test
    void whatIsWrongWithTheSetupIsSaidPerField() throws Exception {
        assertThat(fieldProblems(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "ZLOTY", "Europe/Warsaw", "Anna", "Piotr"))))
                .containsExactly("currency");
        assertThat(fieldProblems(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Mars/Olympus", "Anna", "Piotr"))))
                .containsExactly("timeZone");
        assertThat(fieldProblems(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "+02:00", "Anna", "Piotr"))))
                .containsExactly("timeZone");
        assertThat(fieldProblems(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", " ", "x".repeat(81)))))
                .containsExactly("partnerName", "yourName");
        assertThat(fieldProblems(http().postJsonAs(anna, "/api/wedding", "{}")))
                .containsExactly("currency", "partnerName", "timeZone", "yourName");
        assertThat(http().postJsonAs(anna, "/api/wedding", "{}").body())
                .as("the messages are for people: they say what to do")
                .contains("Enter your name.", "Enter your partner's name.", "Choose a currency.", "Choose a time zone.")
                .doesNotContain("must not be");

        HttpResponse<String> notADate = http().postJsonAs(anna, "/api/wedding", setup("19 June 2027", "PLN", "Europe/Warsaw", "Anna", "Piotr"));
        assertThat(notADate.statusCode()).isEqualTo(400);
        assertThat(TestHttp.json(notADate)).containsEntry("type", "urn:wedding-planner:problem:bad-request");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM wedding", Integer.class)).as("nothing was set up").isZero();
    }

    @Test
    void theCurrencyAndNamesAreTidiedUp() throws Exception {
        Map<String, Object> wedding = TestHttp.json(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", " pln ", "Europe/Warsaw", "  Anna ", " Piotr  ")));

        assertThat(wedding).containsEntry("currency", "PLN");
        assertThat(members(wedding)).extracting(member -> member.get("displayName")).containsExactly("Anna", "Piotr");
    }

    @Test
    void eitherOwnerChangesTheDetailsForBoth() throws Exception {
        http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr"));

        HttpResponse<String> updated = http().putJsonAs(piotr, "/api/wedding", details("2027-09-04", "EUR", "Europe/Kyiv"));

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(TestHttp.json(http().getAs(anna, "/api/wedding")))
                .containsEntry("date", "2027-09-04")
                .containsEntry("currency", "EUR")
                .containsEntry("timeZone", "Europe/Kyiv");
        assertThat(fieldProblems(http().putJsonAs(piotr, "/api/wedding", details("2027-09-04", "EUR", "Nowhere")))).containsExactly("timeZone");
    }

    @Test
    void aMemberCanBeRenamed() throws Exception {
        Map<String, Object> wedding = TestHttp.json(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr")));
        String second = (String) members(wedding).get(1).get("id");

        HttpResponse<String> renamed = http().putJsonAs(anna, "/api/wedding/members/" + second, "{\"displayName\":\"Piotrek\"}");

        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(members(TestHttp.json(http().getAs(piotr, "/api/wedding")))).extracting(member -> member.get("displayName")).containsExactly("Anna", "Piotrek");
        assertThat(http().putJsonAs(anna, "/api/wedding/members/" + UUID.randomUUID(), "{\"displayName\":\"Nobody\"}").statusCode()).isEqualTo(404);
        assertThat(fieldProblems(http().putJsonAs(anna, "/api/wedding/members/" + second, "{\"displayName\":\"\"}"))).containsExactly("displayName");
    }

    @Test
    void otherModulesLearnTheCallersPlaceInTheWedding() throws Exception {
        UserId annasId = new UserId(UUID.fromString(TestSignIn.signIn(http(), ANNA).userId()));
        assertThatThrownBy(() -> weddings.membershipOf(annasId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.WEDDING_NOT_SET_UP));

        Map<String, Object> wedding = TestHttp.json(http().postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr")));

        Membership membership = weddings.membershipOf(annasId);
        assertThat(membership.weddingId()).hasToString((String) wedding.get("id"));
        assertThat(membership.memberId()).hasToString((String) members(wedding).get(0).get("id"));
        assertThat(membership.timeZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
        assertThat(membership.weddingDate()).isEqualTo(LocalDate.of(2027, 6, 19));
    }

    @Nested
    @TestPropertySource(properties = "ALLOWED_EMAILS=anna@example.com,piotr@example.com,third@example.com")
    class WithAThirdAddressOnTheAllowList extends PostgresIntegrationTest {

        @Value("${local.server.port}")
        int port;

        @Autowired
        JdbcTemplate jdbc;

        @Test
        void aThirdPersonFindsNoPlace() throws Exception {
            jdbc.update("DELETE FROM wedding");
            TestHttp http = new TestHttp(port);
            String anna = TestSignIn.signIn(http, ANNA).accessToken();
            String piotr = TestSignIn.signIn(http, PIOTR).accessToken();
            String third = TestSignIn.signIn(http, "third@example.com").accessToken();
            http.postJsonAs(anna, "/api/wedding", setup("2027-06-19", "PLN", "Europe/Warsaw", "Anna", "Piotr"));
            assertThat(http.getAs(piotr, "/api/wedding").statusCode()).isEqualTo(200);

            HttpResponse<String> response = http.getAs(third, "/api/wedding");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:wedding-full");
            assertThat(response.body()).doesNotContain("Anna").doesNotContain("2027");
        }
    }

    static String setup(String date, String currency, String timeZone, String yourName, String partnerName) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date);
        body.put("currency", currency);
        body.put("timeZone", timeZone);
        body.put("yourName", yourName);
        body.put("partnerName", partnerName);
        return TestHttp.jsonOf(body);
    }

    private static String details(String date, String currency, String timeZone) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", date);
        body.put("currency", currency);
        body.put("timeZone", timeZone);
        return TestHttp.jsonOf(body);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> members(Map<String, Object> wedding) {
        return (List<Map<String, Object>>) wedding.get("members");
    }

    private static Map<String, Object> withoutId(Map<String, Object> member) {
        Map<String, Object> copy = new HashMap<>(member);
        copy.remove("id");
        return copy;
    }

    /** The fields a 400 answer complains about, in the order sent. */
    @SuppressWarnings("unchecked")
    private static List<String> fieldProblems(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        Map<String, Object> problem = TestHttp.json(response);
        assertThat(problem).containsEntry("type", "urn:wedding-planner:problem:bad-request");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.get("fieldProblems");
        assertThat(fields).as(response.body()).isNotNull().allSatisfy(field -> assertThat((String) field.get("message")).isNotBlank());
        return fields.stream().map(field -> (String) field.get("field")).toList();
    }

    private TestHttp http() {
        return new TestHttp(port);
    }
}
