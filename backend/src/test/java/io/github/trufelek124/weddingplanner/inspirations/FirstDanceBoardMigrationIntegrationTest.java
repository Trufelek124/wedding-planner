package io.github.trufelek124.weddingplanner.inspirations;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V25 on a database where weddings were offered their boards before "First dance" joined the list. */
class FirstDanceBoardMigrationIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> OLD_LIST =
            List.of("Dress", "Suit", "Hair and make-up", "Flowers", "Decor", "Venue", "Cake", "Invitations");

    @Value("${DATABASE_URL}")
    String databaseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    JdbcTemplate server;

    @Test
    void aWeddingWithItsBoardsGetsFirstDanceOnceAtTheEndAndTheOthersAreUntouched() {
        Old db = olderDatabase();
        UUID wedding = db.wedding(true);
        for (int i = 0; i < OLD_LIST.size(); i++) {
            db.board(wedding, OLD_LIST.get(i), i + 1);
        }
        List<UUID> before = db.ids(wedding);

        db.migrate();

        assertThat(db.names(wedding)).containsExactlyElementsOf(withFirstDance(OLD_LIST));
        assertThat(db.ids(wedding).subList(0, 8)).as("the others are the same boards").isEqualTo(before);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM inspirations_setup WHERE wedding_id = ?", Integer.class, wedding))
                .as("still marked as offered, so the first call of the list adds nothing").isEqualTo(1);
    }

    @Test
    void aWeddingWithFirstDanceInAnotherSpellingGetsNone() {
        Old db = olderDatabase();
        UUID wedding = db.wedding(true);
        db.board(wedding, "Dress", 1);
        db.board(wedding, "  FIRST dance ", 2);

        db.migrate();

        assertThat(db.names(wedding)).containsExactly("Dress", "  FIRST dance ");
    }

    @Test
    void aWeddingThatRemovedSomeBoardsStillGetsItAfterTheLastOne() {
        Old db = olderDatabase();
        UUID wedding = db.wedding(true);
        db.board(wedding, "Cake", 3);
        db.board(wedding, "Venue", 7);

        db.migrate();

        assertThat(db.names(wedding)).containsExactly("Cake", "Venue", "First dance");
        assertThat(db.jdbc.queryForObject("SELECT position FROM board WHERE wedding_id = ? AND name = 'First dance'", Integer.class, wedding))
                .isEqualTo(8);
    }

    @Test
    void aWeddingThatRemovedAllItsBoardsGetsJustThisOne() {
        Old db = olderDatabase();
        UUID wedding = db.wedding(true);

        db.migrate();

        assertThat(db.names(wedding)).containsExactly("First dance");
    }

    @Test
    void aWeddingNeverOfferedItsBoardsGetsNothing() {
        Old db = olderDatabase();
        UUID wedding = db.wedding(false);

        db.migrate();

        assertThat(db.names(wedding)).isEmpty();
    }

    private Old olderDatabase() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("CREATE DATABASE " + name);
        String url = databaseUrl.replaceFirst("/postgres\\?", "/" + name + "?");
        Flyway.configure().dataSource(url, "postgres", "").target("24").load().migrate();
        return new Old(url, new JdbcTemplate(new DriverManagerDataSource(url, "postgres", "")));
    }

    private record Old(String url, JdbcTemplate jdbc) {
        void migrate() {
            Flyway.configure().dataSource(url, "postgres", "").load().migrate();
        }

        List<String> names(UUID wedding) {
            return jdbc.queryForList("SELECT name FROM board WHERE wedding_id = ? ORDER BY position", String.class, wedding);
        }

        List<UUID> ids(UUID wedding) {
            return jdbc.queryForList("SELECT id FROM board WHERE wedding_id = ? ORDER BY position", UUID.class, wedding);
        }

        UUID wedding(boolean offered) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO wedding (id, currency, time_zone, created_at, updated_at) VALUES (?, 'PLN', 'Europe/Warsaw', now(), now())", id);
            if (offered) {
                jdbc.update("INSERT INTO inspirations_setup (wedding_id, boards_offered_at) VALUES (?, now())", id);
            }
            return id;
        }

        void board(UUID wedding, String name, int position) {
            jdbc.update("INSERT INTO board (id, wedding_id, name, position) VALUES (?, ?, ?, ?)", UUID.randomUUID(), wedding, name, position);
        }
    }

    private static List<String> withFirstDance(List<String> list) {
        return java.util.stream.Stream.concat(list.stream(), java.util.stream.Stream.of("First dance")).toList();
    }
}
