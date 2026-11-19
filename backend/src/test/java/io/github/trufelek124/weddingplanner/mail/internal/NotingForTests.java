package io.github.trufelek124.weddingplanner.mail.internal;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The mail module's sync is its own, and package-private: a test of another module that needs messages noted (so that
 * they wait to be read) asks for them through this. Nothing goes to the real Google: the mailboxes are the stand-in's.
 *
 * <p>Connecting notes nothing of what is in a mailbox (D-009): earlier mail is noted when its owner asks for it. A test
 * that has put messages into a mailbox therefore asks to look back, as the owner would on the e-mail page, and then
 * has the sync look.
 */
public final class NotingForTests {

    /** As far back as the page lets an owner go. Every message a test makes is newer than this. */
    public static final int FAR_BACK_DAYS = 90;

    private NotingForTests() {}

    /** Looks at every connected mailbox once, as the server's own clock would; returns how many were looked at. */
    public static int lookAtAll(ApplicationContext context) {
        return context.getBean(MailSync.class).lookAtAll();
    }

    /**
     * Every connected mailbox's owner asks to look back {@link #FAR_BACK_DAYS} days (again, when they had), and then
     * the sync looks at every mailbox once: the first look takes where the mailbox stands, and the look back notes
     * what is in it, the newest first, as the first look used to. Returns how many mailboxes were looked at.
     */
    public static int lookBackAndLookAtAll(ApplicationContext context) {
        ConnectionRepository connections = context.getBean(ConnectionRepository.class);
        Clock clock = context.getBean(Clock.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        for (UUID member : jdbc.queryForList("SELECT member_id FROM mail_connection WHERE state = 'CONNECTED'", UUID.class)) {
            connections.askLookBack(member, FAR_BACK_DAYS, clock.instant());
        }
        return lookAtAll(context);
    }
}
