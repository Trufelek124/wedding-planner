package io.github.trufelek124.weddingplanner.mailreading;

import java.math.BigDecimal;

/**
 * Where the reading of the mail stands (S04 sections 7 and 8), as the e-mail page will show it. How many messages wait,
 * were read and came to what is the mail module's own count ({@code MailReading.counts}).
 */
public interface Reading {

    enum State {
        /** No key for the AI service, or the mail import is not set up: nothing is read. */
        OFF,
        /** It reads, or would if there were something to read. */
        READING,
        /** The AI service asked to wait; it goes on by itself. */
        WAITING_FOR_THE_SERVICE,
        /** The key or the account is not accepted; it starts again when the server is started with one that is. */
        KEY_NOT_ACCEPTED,
        /** The limit for the month is reached; it goes on next month. */
        MONTHLY_LIMIT,
        /** The limit of messages for the day is reached; it goes on tomorrow. */
        DAILY_LIMIT
    }

    /**
     * @param costThisMonth, costInAll the estimate in US dollars, to the cent, rounded up
     * @param monthlyLimit the limit the month's estimate is held against
     */
    record Status(State state, BigDecimal costThisMonth, BigDecimal costInAll, BigDecimal monthlyLimit) {}

    Status status();
}
