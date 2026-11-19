package io.github.trufelek124.weddingplanner.mail.internal;

import org.springframework.context.ApplicationContext;

/**
 * The mail module's sync is its own, and package-private: a test of another module that needs messages noted (so that
 * they wait to be read) asks for one look through this. Nothing goes to the real Google: the mailboxes are the stand-in's.
 */
public final class NotingForTests {

    private NotingForTests() {}

    /** Looks at every connected mailbox once, as the server's own clock would; returns how many were looked at. */
    public static int lookAtAll(ApplicationContext context) {
        return context.getBean(MailSync.class).lookAtAll();
    }
}
