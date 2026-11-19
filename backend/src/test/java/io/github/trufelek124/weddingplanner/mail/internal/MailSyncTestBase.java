package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.MailTestBase;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/** The sync against the stand-in: two owners, their made-up mailboxes, and what the tables hold afterwards. */
abstract class MailSyncTestBase extends MailTestBase {

    static final String MESSAGES = "/gmail/v1/users/me/messages";
    static final String HISTORY = "/gmail/v1/users/me/history";
    static final String PROFILE = "/gmail/v1/users/me/profile";

    @Autowired
    MailSync sync;

    @Autowired
    MailSetup setup;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    MailSource source;

    @Autowired
    java.time.Clock clock;

    Mailbox annasBox() {
        return google.mailbox(ANNA_MAILBOX);
    }

    Mailbox piotrsBox() {
        return google.mailbox(PIOTR_MAILBOX);
    }

    UUID anna() {
        return UUID.fromString(wedding.annaId);
    }

    UUID piotr() {
        return UUID.fromString(wedding.piotrId);
    }

    void connectAnna() throws Exception {
        org.assertj.core.api.Assertions.assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
    }

    void connectPiotr() throws Exception {
        org.assertj.core.api.Assertions.assertThat(location(connect(wedding.piotr, PIOTR_MAILBOX))).isEqualTo(YES);
    }

    /** Anna asks to look back this many days, as she would on the page (the sync is not woken: tests call the look). */
    void lookBack(UUID owner, int days) {
        assertThat(connections.askLookBack(owner, days, TestClock.now())).as("the look back was recorded").isTrue();
    }

    /**
     * What the first look used to do in these tests: the owner asks for the last ninety days, and the sync looks once
     * at every mailbox. The first look of a mailbox takes where it stands and notes nothing; the look back notes
     * what is in it, the newest first.
     */
    int noteEverything(UUID owner) {
        lookBack(owner, 90);
        return sync.lookAtAll();
    }

    static Instant daysAgo(long days) {
        return TestClock.now().minus(Duration.ofDays(days));
    }

    static Instant hoursAgo(long hours) {
        return TestClock.now().minus(Duration.ofHours(hours));
    }

    /** The identifiers noted for this owner, sorted. */
    List<String> noted(UUID owner) {
        return jdbc.queryForList("SELECT message_id FROM mail_message WHERE member_id = ? ORDER BY message_id", String.class, owner);
    }

    String marker(UUID owner) {
        return jdbc.queryForObject("SELECT sync_marker FROM mail_connection WHERE member_id = ?", String.class, owner);
    }

    Instant lastLook(UUID owner) {
        java.time.OffsetDateTime at =
                jdbc.queryForObject("SELECT last_look_at FROM mail_connection WHERE member_id = ?", java.time.OffsetDateTime.class, owner);
        return at == null ? null : at.toInstant();
    }

    int refusals(UUID owner) {
        return jdbc.queryForObject("SELECT refusals FROM mail_connection WHERE member_id = ?", Integer.class, owner);
    }

    Instant putOffUntil(UUID owner) {
        java.time.OffsetDateTime until =
                jdbc.queryForObject("SELECT put_off_until FROM mail_connection WHERE member_id = ?", java.time.OffsetDateTime.class, owner);
        return until == null ? null : until.toInstant();
    }

    String state(UUID owner) {
        return jdbc.queryForObject("SELECT state FROM mail_connection WHERE member_id = ?", String.class, owner);
    }

    /** How many calls reached the stand-in's Gmail addresses (the profile too, which connecting also asks). */
    int gmailCalls() {
        return google.count(MESSAGES) + google.count(HISTORY) + google.count(PROFILE);
    }
}
