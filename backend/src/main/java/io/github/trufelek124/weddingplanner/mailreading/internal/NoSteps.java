package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Used;

/** The steps while the reading is off: no client exists, nothing is asked, every call is "asked nothing". */
final class NoSteps implements ReadingSteps {

    @Override
    public Outcome<MailTriage.Triaged> triage(MailTriage.Input message) {
        return new Outcome.Unanswered<>(Outcome.Why.ASKED_NOTHING, Used.NOTHING);
    }

    @Override
    public Outcome<MailExtraction.Extracted> extract(MailExtraction.Input message) {
        return new Outcome.Unanswered<>(Outcome.Why.ASKED_NOTHING, Used.NOTHING);
    }

    @Override
    public void close() {}
}
