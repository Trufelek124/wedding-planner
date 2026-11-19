package io.github.trufelek124.weddingplanner.mailreading.internal;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** The shape triage is asked to answer in. The SDK makes the schema from it; {@link Checking} checks it again. */
record RawTriage(
        @JsonPropertyDescription("true when the message concerns the two owners' own wedding, false when it does not") Boolean concernsTheWedding,
        @JsonPropertyDescription("how sure the answer is, a whole number from 0 to 100") Integer howSure) {

    @Override
    public String toString() {
        return "RawTriage[]";
    }
}
