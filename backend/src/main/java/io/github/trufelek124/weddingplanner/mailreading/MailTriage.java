package io.github.trufelek124.weddingplanner.mailreading;

import java.time.Instant;
import java.util.Objects;

/**
 * Step one: does this message concern the owners' wedding? A small model answers it from the sender, the subject,
 * when the message arrived and the beginning of its text. Nothing more of the message leaves the server, and nothing
 * of the thread or of the planner (S04 section 3).
 */
public interface MailTriage {

    /** How much of a message's text triage sends, in characters (Unicode code points). */
    int FIRST_CHARACTERS = 1_500;

    /**
     * Never throws for what the service does or answers. With no key set it answers {@code ASKED_NOTHING} and asks
     * nothing of anybody.
     */
    Outcome<Triaged> triage(Input message);

    /**
     * A message as triage sees it. {@code text} is the whole text: the step cuts it to {@link #FIRST_CHARACTERS} itself.
     *
     * @param subject may be empty
     */
    record Input(Sender from, String subject, Instant arrived, String text) {

        public Input {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(arrived, "arrived");
            Objects.requireNonNull(text, "text");
        }

        /** Somebody else's words: nothing of them is printed. */
        @Override
        public String toString() {
            return "Input[]";
        }
    }

    /**
     * @param concernsTheWedding whether the message concerns the two owners' own wedding
     * @param sure how sure the model is, 0 to 100
     */
    record Triaged(boolean concernsTheWedding, int sure) {

        public Triaged {
            if (sure < 0 || sure > 100) {
                throw new IllegalArgumentException("sure");
            }
        }
    }
}
