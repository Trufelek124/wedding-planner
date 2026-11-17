package io.github.trufelek124.weddingplanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** A fresh wedding of Anna and Piotr for a test, set up through the real endpoints. */
public final class TestWedding {

    public final TestHttp http;
    /** Access tokens. */
    public final String anna;
    public final String piotr;
    /** Member ids, in the wedding's order: Anna first. */
    public final String annaId;
    public final String piotrId;

    private TestWedding(TestHttp http, String anna, String piotr, String annaId, String piotrId) {
        this.http = http;
        this.anna = anna;
        this.piotr = piotr;
        this.annaId = annaId;
        this.piotrId = piotrId;
    }

    /** Removes whatever wedding an earlier test left behind (with everything that hangs off it) and sets up a new one. */
    @SuppressWarnings("unchecked")
    public static TestWedding fresh(JdbcTemplate jdbc, int port) throws Exception {
        jdbc.update("DELETE FROM wedding");
        TestHttp http = new TestHttp(port);
        String anna = TestSignIn.signIn(http, PostgresIntegrationTest.ANNA).accessToken();
        String piotr = TestSignIn.signIn(http, PostgresIntegrationTest.PIOTR).accessToken();
        HttpResponse<String> created = http.postJsonAs(anna, "/api/wedding", TestHttp.jsonOf(Map.of(
                "date", "2027-06-19", "currency", "PLN", "timeZone", "Europe/Warsaw", "yourName", "Anna", "partnerName", "Piotr")));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(http.getAs(piotr, "/api/wedding").statusCode()).isEqualTo(200);
        List<Map<String, Object>> members = (List<Map<String, Object>>) TestHttp.json(created).get("members");
        return new TestWedding(http, anna, piotr, (String) members.get(0).get("id"), (String) members.get(1).get("id"));
    }
}
