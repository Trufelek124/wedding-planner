package io.github.trufelek124.weddingplanner.mail.internal;

/** The words the API and the table use for a mailbox connection. */
final class MailTypes {

    private MailTypes() {}

    /** Where a person's connection stands. */
    enum ConnectionState {
        /** Nothing is connected. */
        NOT_CONNECTED,
        /** The server may read this mailbox. */
        CONNECTED,
        /** Google no longer lets the server in: access was withdrawn or expired, or the token cannot be read. */
        NEEDS_RECONNECTING
    }
}
