package io.github.trufelek124.weddingplanner;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for tests that need the running application. One real PostgreSQL 18 is
 * started per test JVM and handed to the application through the same environment
 * variables production uses, so the migrations and the configuration are tested as shipped.
 * Google is replaced by {@link TestGoogle}, the clock by {@link TestClock}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestClock.Configuration.class)
// Settings a test class may replace with its own @TestPropertySource. (What is registered in
// database() below cannot be replaced that way: dynamic properties win.)
@TestPropertySource(properties = {
    "APP_JWT_SECRET=a-test-secret-that-is-long-enough-0123456789",
    // Written differently from what Google will say, as an owner might type it into the configuration.
    "ALLOWED_EMAILS=anna@example.com , Piotr@Example.com",
    "GOOGLE_OAUTH_WEB_CLIENT_ID=" + TestGoogle.CLIENT_ID
})
public abstract class PostgresIntegrationTest {

    /** The two owners of the planner in tests; anyone else is a stranger. */
    public static final String ANNA = "anna@example.com";

    public static final String PIOTR = "piotr@example.com";

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the embedded PostgreSQL", e);
        }
    }

    @AfterEach
    void resetTheClock() {
        TestClock.reset();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("DATABASE_USER", () -> "postgres");
        registry.add("DATABASE_PASSWORD", () -> "");
        registry.add("app.identity.google-jwks-uri", TestGoogle::jwksUri);
    }
}
