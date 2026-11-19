/**
 * Reading the mail with the AI service (S04, D-007): the two steps, triage and extraction, as two things one can call.
 * Everything about the service is inside them. They take a message's words as plain values and give back values:
 * an answer that has been checked, or one of the outcomes that are not answers. They fetch no mail, keep nothing and
 * hand nothing in; that is the next module's work.
 */
package io.github.trufelek124.weddingplanner.mailreading;
