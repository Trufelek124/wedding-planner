package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixed set of invented sample messages (S04 section 10), one file per language under
 * {@code mailreading/samples/} in the test resources. Everything in it is made up. A file names its language, "today"
 * and the wedding's date once, at the top, so that "in two weeks" has one right answer for all its samples.
 */
record SampleSet(List<Collection> collections) {

    static final List<String> LANGUAGES = List.of("pl", "uk", "en");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** One language's file. */
    record Collection(String language, LocalDate today, LocalDate weddingDate, List<Sample> samples) {

        Collection {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    record Person(String name, String address) {}

    record Earlier(Person from, Instant arrived, String text) {}

    /** A PDF document that came with the message, as the server would have taken its text out: invented. */
    record SampleDocument(String name, String text) {}

    /**
     * @param sort what kind of mail it is: {@code proposal} (about the wedding, with something to propose),
     *     {@code nothing} (about the wedding, nothing to propose), {@code unrelated} or {@code hard}
     * @param instructs whether the message tries to instruct its reader
     * @param earlier the earlier messages of the thread, oldest first
     * @param documents the text of the PDF documents that came with the message (D-011), in the order of the mail
     * @param alreadyProposed titles already proposed from the thread
     * @param why how the expected days and amounts follow from the text, "today" and the wedding's date
     */
    record Sample(
            String id,
            String sort,
            boolean instructs,
            Person from,
            String subject,
            Instant arrived,
            String text,
            List<Earlier> earlier,
            List<SampleDocument> documents,
            List<String> alreadyProposed,
            String why,
            Expected expected) {

        Sample {
            earlier = earlier == null ? List.of() : List.copyOf(earlier);
            documents = documents == null ? List.of() : List.copyOf(documents);
            alreadyProposed = alreadyProposed == null ? List.of() : List.copyOf(alreadyProposed);
        }

        /** Somebody else's words, invented or not: nothing of them is printed. */
        @Override
        public String toString() {
            return "Sample[" + id + "]";
        }
    }

    /**
     * What the steps should answer. Loose on purpose: a right answer in other words counts.
     *
     * @param proposals what should be proposed
     * @param nothingElse where any further proposal would be a mistake; a message that concerns nothing, or
     *     asks for nothing, expects no proposal at all and is read as if it said so
     * @param mustNotPropose word stems that must occur in no proposal's title or notes
     */
    record Expected(boolean concernsTheWedding, List<ExpectedProposal> proposals, boolean nothingElse, List<String> mustNotPropose) {

        Expected {
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
            mustNotPropose = mustNotPropose == null ? List.of() : List.copyOf(mustNotPropose);
        }

        boolean noFurtherProposal() {
            return nothingElse || proposals.isEmpty();
        }
    }

    /**
     * @param kind TASK, PAYMENT, PLAN_ITEM or VENDOR
     * @param titleMentions word stems of which at least one occurs in the title, in any case
     * @param vendor the vendor's name where the message names one
     */
    record ExpectedProposal(
            String kind,
            List<String> titleMentions,
            LocalDate dueDate,
            LocalDate startDate,
            ExpectedAmount amount,
            String paymentKind,
            String vendor) {

        ExpectedProposal {
            titleMentions = titleMentions == null ? List.of() : List.copyOf(titleMentions);
        }
    }

    /** An amount as the mail writes it: digits with a point and at most two decimals. */
    record ExpectedAmount(String value, String currency) {}

    /** A sample and the file it came from. */
    record Item(Collection collection, Sample sample) {}

    SampleSet {
        collections = List.copyOf(collections);
    }

    /** The files that belong to the repository, in the fixed order Polish, Ukrainian, English. */
    static SampleSet bundled() {
        List<Collection> all = new ArrayList<>();
        for (String language : LANGUAGES) {
            all.add(parse(resource("/mailreading/samples/" + language + ".json")));
        }
        return new SampleSet(all);
    }

    static Collection parse(String json) {
        return JSON.readValue(json, Collection.class);
    }

    /** Every sample, in the fixed order of the files. */
    List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (Collection collection : collections) {
            for (Sample sample : collection.samples()) {
                items.add(new Item(collection, sample));
            }
        }
        return items;
    }

    static String resource(String path) {
        try (InputStream in = SampleSet.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("The sample file is missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The sample file could not be read: " + path, e);
        }
    }
}
