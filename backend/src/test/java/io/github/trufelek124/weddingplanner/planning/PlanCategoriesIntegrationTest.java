package io.github.trufelek124.weddingplanner.planning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Finding a category by the name another module knows it by. */
class PlanCategoriesIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlanCategories categories;

    TestWedding wedding;
    UUID weddingId;

    @BeforeEach
    void aFreshWedding() throws Exception {
        wedding = TestWedding.fresh(jdbc, port);
        weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        // Listing offers the starter categories once.
        assertThat(wedding.http.getAs(wedding.anna, "/api/categories").statusCode()).isEqualTo(200);
    }

    @Test
    void aCategoryIsFoundByItsNameWhateverTheCaseAndTheSpacesAroundIt() {
        UUID music = jdbc.queryForObject("SELECT id FROM category WHERE name = 'Music'", UUID.class);

        assertThat(categories.named(weddingId, "Music")).contains(music);
        assertThat(categories.named(weddingId, "mUSIC")).contains(music);
        assertThat(categories.named(weddingId, "  music  ")).contains(music);
    }

    @Test
    void aCategoryTheCoupleAddedIsFoundToo() throws Exception {
        var created = wedding.http.postJsonAs(wedding.anna, "/api/categories", TestHttp.jsonOf(Map.of("name", "Fireworks")));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        assertThat(categories.named(weddingId, "fireworks")).contains(UUID.fromString((String) TestHttp.json(created).get("id")));
    }

    @Test
    void nothingIsFoundWhenThereIsNoSuchCategoryOrWeddingOrName() {
        assertThat(categories.named(weddingId, "Fireworks")).isEmpty();
        assertThat(categories.named(weddingId, "Mus")).isEmpty();
        assertThat(categories.named(weddingId, "Mus%")).isEmpty();
        assertThat(categories.named(weddingId, "")).isEmpty();
        assertThat(categories.named(weddingId, "   ")).isEmpty();
        assertThat(categories.named(UUID.randomUUID(), "Music")).isEmpty();
    }
}
