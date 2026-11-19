package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestWedding;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The server as it starts when the import was not given what it needs, with the sync's own clock switched on: nothing
 * runs, nothing is asked of Google, whatever the database holds.
 */
@TestPropertySource(properties = {"app.mail.sync-automatic=true", "app.mail.sync-interval=50ms"})
class MailSyncNotSetUpIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MailSync sync;

    @Autowired
    io.github.trufelek124.weddingplanner.mail.MailReading reading;

    final StandInGoogle google = StandInGoogle.get();

    @DynamicPropertySource
    static void standInForGoogle(DynamicPropertyRegistry registry) {
        StandInGoogle google = StandInGoogle.get();
        registry.add("app.mail.authorization-uri", google::authorizationUri);
        registry.add("app.mail.token-uri", google::tokenUri);
        registry.add("app.mail.revoke-uri", google::revokeUri);
        registry.add("app.mail.gmail-uri", google::gmailUri);
    }

    @Test
    void withoutTheImportSetUpNoLookIsMadeNotEvenByTheServersOwnClock() throws Exception {
        google.reset();
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        // A connection left in the database, as if the import had been set up before.
        jdbc.update("INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at) VALUES (?, 'CONNECTED', ?, ?, now())",
                UUID.fromString(wedding.annaId), "anna.mailbox@planner.example", new byte[40]);
        google.mailbox("anna.mailbox@planner.example").deliver("m-1", "thread-1", java.time.Instant.now(), "INBOX");

        assertThat(sync.lookAtAll()).isZero();
        Thread.sleep(400); // the server's own clock, at 50 ms, has had its chances

        assertThat(google.calls()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_look_at FROM mail_connection", java.time.OffsetDateTime.class)).isNull();
    }

    @Test
    void withoutTheImportSetUpNothingWaitsAndNoLinkIsMade() throws Exception {
        TestWedding wedding = TestWedding.fresh(jdbc, port);
        UUID anna = UUID.fromString(wedding.annaId);
        UUID weddingId = jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
        jdbc.update("INSERT INTO mail_connection (member_id, state, address, token_sealed, connected_at) VALUES (?, 'CONNECTED', ?, ?, now())",
                anna, "anna.mailbox@planner.example", new byte[40]);
        jdbc.update("INSERT INTO mail_message (member_id, message_id, thread_id, noted_at, look, newness) VALUES (?, 'm-1', 't-1', now(), 1, 1)", anna);

        assertThat(reading.waiting(10)).isEmpty();
        assertThat(reading.waiting(10, java.util.List.of())).isEmpty();
        assertThat(reading.linkTo(new io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle(weddingId, anna, "m-1", "t-1")))
                .isEmpty();
    }
}
