package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;

/**
 * Both steps in one thing that can be closed, so that the application has one bean for them: the real ones, with the
 * client of the AI service inside, or the ones that ask nothing.
 */
interface ReadingSteps extends MailTriage, MailExtraction, AutoCloseable {

    @Override
    void close();
}
