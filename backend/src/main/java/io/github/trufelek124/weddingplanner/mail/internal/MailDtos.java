package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailTypes.ConnectionState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * What the mail endpoints give. The address of a mailbox and the address that Google is sent to are private to one
 * person's request: every record hides them in toString(), because response bodies reach debug logs through it.
 */
final class MailDtos {

    private MailDtos() {}

    @Schema(
            name = "MailState",
            description = "Whether the e-mail import works on this server, and where each owner's connection stands. "
                    + "While it is not set up both connections read NOT_CONNECTED: there is nothing to connect.")
    record MailStateResponse(
            @Schema(description = "False when the server was not given what the import needs.") boolean setUp,
            @Schema(description = "The caller's own connection.") MyConnection yours,
            @Schema(description = "The partner's connection, without their address.") PartnersConnection partner) {

        @Override
        public String toString() {
            return "MailState[setUp=" + setUp + "]";
        }
    }

    @Schema(name = "MailConnection", description = "The caller's own connection to their mailbox.")
    record MyConnection(
            ConnectionState state,
            @Schema(description = "The connected mailbox, shown to its owner only. Null when nothing was ever connected.", nullable = true)
                    String address,
            @Schema(description = "When it was connected.", nullable = true) Instant connectedAt,
            @Schema(description = "When the server last looked at the mailbox.", nullable = true) Instant lastLookAt,
            @Schema(description = "How many messages the server has noted; 0 until the sync exists.") int messagesNoted) {

        @Override
        public String toString() {
            return "MailConnection[state=" + state + "]";
        }
    }

    @Schema(name = "PartnersMailConnection", description = "The partner's connection: whether there is one, never which mailbox.")
    record PartnersConnection(
            @Schema(description = "False until the partner has signed in for the first time: there is nobody to connect yet.")
                    boolean joined,
            ConnectionState state,
            @Schema(description = "When the server last looked.", nullable = true) Instant lastLookAt) {

        @Override
        public String toString() {
            return "PartnersMailConnection[joined=" + joined + ", state=" + state + "]";
        }
    }

    @Schema(name = "MailConnectionStart", description = "Where to send the browser to ask Google for the permission.")
    record ConnectionStart(
            @Schema(description = "Google's address, made for this attempt only. Go there at once; it works once.")
                    String authorizationUrl) {

        @Override
        public String toString() {
            return "MailConnectionStart[]";
        }
    }
}
