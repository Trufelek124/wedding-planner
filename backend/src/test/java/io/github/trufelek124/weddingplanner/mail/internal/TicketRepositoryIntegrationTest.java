package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The ticket is taken by one statement: of any number of takers at the same moment, exactly one gets it. */
class TicketRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TicketRepository tickets;

    @Test
    void ofManyTakersAtTheSameMomentExactlyOneGetsTheTicket() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        UUID member = UUID.fromString(wedding.annaId);
        byte[] stateHash = new byte[32];
        stateHash[0] = 7;
        Instant now = Instant.now();
        tickets.create(stateHash, member, new byte[32], new byte[60], now, now.plusSeconds(600));
        int takers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(takers);
        try {
            CountDownLatch together = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < takers; i++) {
                results.add(pool.submit(() -> {
                    together.await();
                    return tickets.take(stateHash, now.plusSeconds(1));
                }));
            }
            together.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }

            assertThat(winners).isEqualTo(1);
            assertThat(tickets.take(stateHash, now.plusSeconds(2))).as("and never again").isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTicketIsNotTakenAtTheMomentItRunsOut() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        byte[] stateHash = new byte[32];
        stateHash[0] = 9;
        Instant now = Instant.now();
        tickets.create(stateHash, UUID.fromString(wedding.annaId), new byte[32], new byte[60], now, now.plusSeconds(600));

        assertThat(tickets.take(stateHash, now.plusSeconds(600))).isFalse();
        assertThat(tickets.take(stateHash, now.plusSeconds(601))).isFalse();
        assertThat(tickets.take(stateHash, now.plusSeconds(599))).isTrue();
    }
}
