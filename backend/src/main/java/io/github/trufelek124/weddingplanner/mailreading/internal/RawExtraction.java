package io.github.trufelek124.weddingplanner.mailreading.internal;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * The shape extraction is asked to answer in. Every value is text or a whole number, with an empty text for "none":
 * the schema asks for nothing the server would have to trust, and {@link Checking} checks all of it again.
 */
record RawExtraction(
        @JsonPropertyDescription("at most five proposals, the most important first; empty when the message asks for nothing")
                List<Proposal> proposals) {

    record Proposal(
            @JsonPropertyDescription("one of TASK, PAYMENT, PLAN_ITEM, VENDOR") String kind,
            @JsonPropertyDescription("short and plain, in the language of the message; for a vendor its name") String title,
            @JsonPropertyDescription("what else matters, plain text; empty when nothing") String notes,
            @JsonPropertyDescription("first day as YYYY-MM-DD, or empty") String firstDay,
            @JsonPropertyDescription("the day it is due as YYYY-MM-DD, or empty") String dueDay,
            @JsonPropertyDescription("the amount as the message writes it: digits, a point and at most two decimals, or empty") String amount,
            @JsonPropertyDescription("the currency's three-letter code, or empty") String currency,
            @JsonPropertyDescription("one of DEPOSIT, INSTALMENT, FINAL for a payment where the message says which, or empty") String paymentKind,
            @JsonPropertyDescription("a category by name, or empty") String category,
            @JsonPropertyDescription("the vendor's name, or empty") String vendorName,
            @JsonPropertyDescription("the vendor's e-mail address as written, or empty") String vendorEmail,
            @JsonPropertyDescription("the vendor's phone number as written, or empty") String vendorPhone,
            @JsonPropertyDescription("the vendor's website as written, or empty") String vendorWebsite,
            @JsonPropertyDescription("how sure the proposal is right, a whole number from 0 to 100") Integer howSure,
            @JsonPropertyDescription("the words of the message the proposal rests on, copied word for word") String passage) {

        @Override
        public String toString() {
            return "Proposal[]";
        }
    }

    @Override
    public String toString() {
        return "RawExtraction[]";
    }
}
