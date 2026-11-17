package io.github.trufelek124.weddingplanner;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Path;

/**
 * A PostgreSQL 18 for running the application on a developer's machine, without Docker:
 * {@code ./gradlew devDatabase}. The data survives restarts in build/dev-database.
 */
public final class DevDatabase {

    private DevDatabase() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("DEV_DATABASE_PORT", "5433"));
        Path data = Path.of(System.getenv().getOrDefault("DEV_DATABASE_DIR", "build/dev-database"));
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(data)
                .setCleanDataDirectory(false)
                .start()) {
            System.out.println("PostgreSQL 18 is listening. Run the application with:");
            System.out.println("  DATABASE_URL=" + postgres.getJdbcUrl("postgres", "postgres")
                    + " DATABASE_USER=postgres DATABASE_PASSWORD=");
            Thread.currentThread().join();
        }
    }
}
