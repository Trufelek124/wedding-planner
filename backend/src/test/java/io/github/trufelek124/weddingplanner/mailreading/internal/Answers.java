package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.util.regex.Matcher;

/** What the stand-in for the AI service says, as the models write it. All made up. */
final class Answers {

    private Answers() {}

    static String triage(boolean yes, int sure) {
        return "{\"concernsTheWedding\":" + yes + ",\"howSure\":" + sure + "}";
    }

    /** One proposal as the model writes it; a test changes a field with {@link #with}. */
    static String proposal(String kind, String title, String passage) {
        return "{\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"notes\":\"\",\"firstDay\":\"\",\"dueDay\":\"\",\"amount\":\"\","
                + "\"currency\":\"\",\"paymentKind\":\"\",\"category\":\"\",\"vendorName\":\"\",\"vendorEmail\":\"\",\"vendorPhone\":\"\","
                + "\"vendorWebsite\":\"\",\"howSure\":80,\"passage\":\"" + passage + "\"}";
    }

    /** The proposal with one field replaced by this text (it is quoted) or number. */
    static String with(String proposal, String field, String value) {
        String raw = value.matches("[0-9]+") ? value : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String replaced = proposal.replaceAll("\"" + field + "\":(\"(?:[^\"\\\\]|\\\\.)*\"|[0-9]+)", Matcher.quoteReplacement("\"" + field + "\":" + raw));
        if (replaced.equals(proposal) && !proposal.contains("\"" + field + "\":" + raw)) {
            throw new AssertionError("no such field: " + field);
        }
        return replaced;
    }

    static String proposals(String... proposals) {
        return "{\"proposals\":[" + String.join(",", proposals) + "]}";
    }
}
