package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.ReadingReport;
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
            @Schema(description = "The partner's connection, without their address.") PartnersConnection partner,
            @Schema(description = "How the reading of the mail stands. The wedding's, not one owner's: both owners get the same.")
                    ReadingBlock reading) {

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

    @Schema(
            name = "MailReading",
            description = "Where the reading of the mail stands, for both mailboxes of the wedding together (S04 section 8). "
                    + "Numbers only: nothing of anybody's address or message. The costs are an estimate from the tokens used and "
                    + "the prices in the server's settings, in whole US cents.")
    record ReadingBlock(
            @Schema(description = "OFF: nothing is read (no key for the AI service, or the import is not set up). READING: it reads. "
                    + "WAITING_FOR_THE_SERVICE: the AI service asked to wait, and it goes on by itself. KEY_NOT_ACCEPTED: the key "
                    + "or the account is not accepted, and it starts again when the server is started with one that is. "
                    + "MONTHLY_LIMIT: the limit for the month is reached. DAILY_LIMIT: the limit of messages for the day is reached.")
                    ReadingReport.State state,
            @Schema(description = "Messages noted and not yet read.") int waiting,
            @Schema(description = "Messages that were read, whatever came of it.") int read,
            @Schema(description = "Of those read, the ones that concerned the wedding.") int aboutTheWedding,
            @Schema(description = "Of those read, the ones that could not be read.") int couldNotBeRead,
            @Schema(description = "How many suggestions came of the messages that were read.") int suggestions,
            @Schema(description = "The estimated cost this month, in whole US cents, rounded up.") long costThisMonthCents,
            @Schema(description = "The estimated cost in all, in whole US cents, rounded up.") long costInAllCents,
            @Schema(description = "The monthly limit the month's estimate is held against, in whole US cents.") long monthlyLimitCents) {

        @Override
        public String toString() {
            return "MailReading[state=" + state + "]";
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
