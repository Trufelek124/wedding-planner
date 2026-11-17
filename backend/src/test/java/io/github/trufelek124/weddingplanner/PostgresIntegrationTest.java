package io.github.trufelek124.weddingplanner;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the running application. One real PostgreSQL 18 is
 * started per test JVM and handed to the application through the same environment
 * variables production uses, so the migrations and the configuration are tested as shipped.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("DATABASE_USER", () -> "postgres");
        registry.add("DATABASE_PASSWORD", () -> "");
    }
}
