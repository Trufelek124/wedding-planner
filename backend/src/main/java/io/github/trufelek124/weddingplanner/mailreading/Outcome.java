package io.github.trufelek124.weddingplanner.mailreading;

/**
 * What a step came to: an answer that has been checked, or one of the ways it can fail to be one. A failure is a
 * value, never an exception that carries text, so that nothing of a message can travel in it.
 *
 * @param <A> what the step answers
 */
public sealed interface Outcome<A> {

    /** What the call used; {@link Used#NOTHING} when it did not reach the service. */
    Used used();

    /** The answer, checked. Its {@link #used()} is what the call cost. */
    record Answered<A>(A answer, Used used) implements Outcome<A> {

        public Answered {
            if (answer == null || used == null) {
                throw new IllegalArgumentException("answered");
            }
        }

        @Override
        public String toString() {
            return "Answered[]";
        }
    }

    /** No answer, and why. {@link #used()} is what the service counted before it stopped, if it counted anything. */
    record Unanswered<A>(Why why, Used used) implements Outcome<A> {

        public Unanswered {
            if (why == null || used == null) {
                throw new IllegalArgumentException("unanswered");
            }
        }
    }

    enum Why {
        /** The model declined the message (stop reason "refusal"). Not to be tried again. */
        DECLINED,
        /**
         * The answer cannot be used: cut off, not the shape asked for, or the service
         * found the request itself wrong (a 400). Not to be tried again.
         */
        CANNOT_BE_USED,
        /** The answer has more than five proposals ({@link MailExtraction#MOST_PROPOSALS}): it is not used at all. */
        TOO_MANY_PROPOSALS,
        /** The service is busy or failing (too many requests, overloaded, a 5xx, no connection). Try later; the message stays as it is. */
        TRY_LATER,
        /**
         * The service does not accept the key, the account or the model (401, 403, 402, or a model it does not know):
         * the reading stops until the server is given something that works.
         */
        NOT_ACCEPTED,
        /** The reading is off (no key): nothing was asked of anybody. */
        ASKED_NOTHING
    }
}
