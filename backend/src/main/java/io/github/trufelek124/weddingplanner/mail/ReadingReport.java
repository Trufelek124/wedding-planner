package io.github.trufelek124.weddingplanner.mail;

/**
 * What the e-mail page says of the reading of the mail (S04 section 8). The mail module asks; the module that reads
 * answers. How many messages wait, were read and came to what is the mail module's own count
 * ({@link MailReading#counts}).
 */
public interface ReadingReport {

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
     * The estimate of the cost in whole US cents, rounded up.
     *
     * @param costThisMonthCents, costInAllCents the estimate of what the reading has cost
     * @param monthlyLimitCents the limit the month's estimate is held against
     */
    record Report(State state, long costThisMonthCents, long costInAllCents, long monthlyLimitCents) {}

    Report report();
}
