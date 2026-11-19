package io.github.trufelek124.weddingplanner.wedding;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** What a part of the server that works without a signed-in person may ask of a wedding. */
class WeddingFactsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Weddings weddings;

    UUID weddingId;

    @BeforeEach
    void aFreshWedding() throws Exception {
        TestWedding.fresh(jdbc, port);
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    @Test
    void theFactsOfTheWeddingThatIsThere() {
        assertThat(weddings.factsOf(weddingId))
                .contains(new WeddingFacts(LocalDate.of(2027, 6, 19), "PLN", ZoneId.of("Europe/Warsaw")));
    }

    @Test
    void theDayIsNullUntilTheCoupleHasFixedIt() {
        jdbc.update("UPDATE wedding SET wedding_date = NULL");

        WeddingFacts facts = weddings.factsOf(weddingId).orElseThrow();

        assertThat(facts.weddingDate()).isNull();
        assertThat(facts.currency()).isEqualTo("PLN");
        assertThat(facts.timeZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
    }

    @Test
    void thereIsNoWeddingUnderAnotherId() {
        assertThat(weddings.factsOf(UUID.randomUUID())).isEmpty();
    }
}
