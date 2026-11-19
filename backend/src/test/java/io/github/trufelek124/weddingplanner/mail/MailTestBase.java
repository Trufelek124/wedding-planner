package io.github.trufelek124.weddingplanner.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestGoogle;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The e-mail import, set up the way an installation does it, with Google replaced by {@link StandInGoogle}. Nothing
 * here ever goes to the real Google: the four addresses it uses are the stand-in's. Everything is made up.
 */
@TestPropertySource(properties = {
    "GOOGLE_OAUTH_CLIENT_SECRET=" + StandInGoogle.CLIENT_SECRET,
    "APP_PUBLIC_URL=" + MailTestBase.PUBLIC_URL,
    // 32 bytes, base64: "0123456789abcdef0123456789abcdef" is not a real key.
    "APP_MAIL_TOKEN_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    // A stand-in that is slow is waited for this long, not ten seconds.
    "app.mail.google-timeout=2s"
})
public abstract class MailTestBase extends PostgresIntegrationTest {

    public static final String PUBLIC_URL = "http://localhost:8080";
    public static final String ANNA_MAILBOX = "anna.mailbox@planner.example";
    public static final String PIOTR_MAILBOX = "piotr.mailbox@planner.example";
    public static final String RETURN_PATH = "/api/mail/gmail/return";
    public static final String COOKIE = "wp_mail_connect";

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final StandInGoogle google = StandInGoogle.get();
    protected TestWedding wedding;
    protected TestHttp http;

    @DynamicPropertySource
    static void standInForGoogle(DynamicPropertyRegistry registry) {
        StandInGoogle google = StandInGoogle.get();
        registry.add("app.mail.authorization-uri", google::authorizationUri);
        registry.add("app.mail.token-uri", google::tokenUri);
        registry.add("app.mail.revoke-uri", google::revokeUri);
        registry.add("app.mail.gmail-uri", google::gmailUri);
    }

    @BeforeEach
    void aFreshWeddingAndAQuietGoogle() throws Exception {
        google.reset();
        wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
    }

    /** What starting a connection gave: Google's address, and what the browser kept. */
    protected record Attempt(String url, String state, String cookie, HttpResponse<String> response) {}

    protected Attempt start(String accessToken) throws Exception {
        HttpResponse<String> response = http.post("/api/mail/gmail/connection", Map.of("Authorization", "Bearer " + accessToken));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String url = (String) TestHttp.json(response).get("authorizationUrl");
        return new Attempt(url, google.query(url).get("state"), cookieValue(response).orElseThrow(), response);
    }

    /** The browser comes back from Google with this code, bringing the cookie it was given. */
    protected HttpResponse<String> comeBack(Attempt attempt, String code) throws Exception {
        return comeBack(attempt.state(), attempt.cookie(), code);
    }

    protected HttpResponse<String> comeBack(String state, String cookie, String code) throws Exception {
        StringBuilder path = new StringBuilder(RETURN_PATH).append('?');
        List<String> parts = new java.util.ArrayList<>();
        if (state != null) {
            parts.add("state=" + state);
        }
        if (code != null) {
            parts.add("code=" + code);
        }
        path.append(String.join("&", parts));
        return http.get(path.toString(), cookie == null ? Map.of() : Map.of("Cookie", COOKIE + "=" + cookie));
    }

    /** The whole of connecting, as people do it: start, consent at Google, come back. */
    protected HttpResponse<String> connect(String accessToken, String mailbox) throws Exception {
        Attempt attempt = start(accessToken);
        return comeBack(attempt, google.consent(attempt.url(), mailbox));
    }

    protected static String location(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("a redirect").isEqualTo(303);
        return response.headers().firstValue("Location").orElseThrow();
    }

    protected static final String YES = PUBLIC_URL + "/mail?connected=yes";
    protected static final String REFUSED = PUBLIC_URL + "/mail?connected=refused";
    protected static final String FAILED = PUBLIC_URL + "/mail?connected=failed";

    protected static java.util.Optional<String> cookieValue(HttpResponse<String> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            Matcher matcher = Pattern.compile("^" + COOKIE + "=([^;]*)").matcher(header);
            if (matcher.find()) {
                return java.util.Optional.of(matcher.group(1));
            }
        }
        return java.util.Optional.empty();
    }

    protected Map<String, Object> mail(String accessToken) throws Exception {
        HttpResponse<String> response = http.getAs(accessToken, "/api/mail");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> yours(Map<String, Object> state) {
        return (Map<String, Object>) state.get("yours");
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> partner(Map<String, Object> state) {
        return (Map<String, Object>) state.get("partner");
    }

    protected int connections() {
        return jdbc.queryForObject("SELECT count(*) FROM mail_connection", Integer.class);
    }

    /** Everything the two mail tables hold, as text, for looking for what must not be there. */
    protected String mailTablesAsText() {
        List<String> rows = new java.util.ArrayList<>(jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_connection t", String.class));
        rows.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM mail_connect_ticket t", String.class));
        return String.join("\n", rows);
    }

    /** None of these appears in the two mail tables: as text, as bytes, or as hex. */
    protected void assertNowhereInThePlain(String... secrets) {
        String text = mailTablesAsText();
        List<byte[]> blobs = new java.util.ArrayList<>(jdbc.query("SELECT token_sealed FROM mail_connection WHERE token_sealed IS NOT NULL",
                (rows, number) -> rows.getBytes(1)));
        blobs.addAll(jdbc.query("SELECT verifier_sealed FROM mail_connect_ticket", (rows, number) -> rows.getBytes(1)));
        blobs.addAll(jdbc.query("SELECT state_hash FROM mail_connect_ticket", (rows, number) -> rows.getBytes(1)));
        blobs.addAll(jdbc.query("SELECT cookie_hash FROM mail_connect_ticket", (rows, number) -> rows.getBytes(1)));
        for (String secret : secrets) {
            assertThat(secret).isNotBlank();
            assertThat(text).as("as text").doesNotContain(secret);
            assertThat(text).as("as hex").doesNotContain(java.util.HexFormat.of().formatHex(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (byte[] blob : blobs) {
                assertThat(new String(blob, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .as("as bytes")
                        .doesNotContain(new String(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1));
            }
        }
    }

    protected static Map<String, String> headersOf(HttpResponse<?> response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(", ", values)));
        return headers;
    }

    protected static String clientId() {
        return TestGoogle.CLIENT_ID;
    }
}
