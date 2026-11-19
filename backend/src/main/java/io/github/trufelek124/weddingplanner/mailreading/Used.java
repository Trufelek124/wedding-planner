package io.github.trufelek124.weddingplanner.mailreading;

/**
 * What one call to the AI service used: the model that answered and the tokens it counted. For a call that never
 * reached the service, or that it refused before counting anything, {@link #NOTHING}.
 *
 * @param model the model that gave the answer; with the service's own fallback it can differ from the one asked
 */
public record Used(String model, long inputTokens, long outputTokens) {

    /** No call, or none that was counted. */
    public static final Used NOTHING = new Used("", 0, 0);

    public Used {
        if (model == null || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("used");
        }
    }
}
