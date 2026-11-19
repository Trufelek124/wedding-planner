package io.github.trufelek124.weddingplanner.mailreading;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The reading, set up the way an installation does it, with the AI service replaced by {@link StandInAnthropic}. Nothing
 * here ever goes to the real service: the key is made up and the address is the stand-in's. All mail is made up.
 */
@TestPropertySource(properties = "app.reading.api-key=" + StandInAnthropic.KEY)
public abstract class ReadingTestBase extends PostgresIntegrationTest {

    protected static final Instant ARRIVED = Instant.parse("2026-11-18T09:30:00Z");
    protected static final LocalDate TODAY = LocalDate.parse("2026-11-19");
    protected static final LocalDate WEDDING = LocalDate.parse("2027-06-12");

    protected final StandInAnthropic service = StandInAnthropic.get();

    @Autowired
    protected MailTriage triage;

    @Autowired
    protected MailExtraction extraction;

    @DynamicPropertySource
    static void standInForTheService(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @BeforeEach
    void aQuietService() {
        service.reset();
    }

    protected static Sender jan() {
        return new Sender("Jan from Palace on the Hill", "jan@palace-on-the-hill.example");
    }

    protected static MailTriage.Input triageInput(String text) {
        return new MailTriage.Input(jan(), "Your booking for 12 June", ARRIVED, text);
    }

    protected static MailExtraction.Input extractionInput(String text) {
        return extractionInput(text, List.of(), WEDDING, List.of());
    }

    protected static MailExtraction.Input extractionInput(
            String text, List<MailExtraction.EarlierMessage> earlier, LocalDate weddingDate, List<String> alreadyProposed) {
        return new MailExtraction.Input(jan(), "Your booking for 12 June", ARRIVED, text, earlier, TODAY, weddingDate, alreadyProposed);
    }

    /** A triage answer, as the model writes it. */
    protected static String triageAnswer(boolean yes, int sure) {
        return "{\"concernsTheWedding\":" + yes + ",\"howSure\":" + sure + "}";
    }

    /** One proposal as the model writes it; a test changes a field with {@link #with}. */
    protected static String proposal(String kind, String title, String passage) {
        return "{\"kind\":\"" + kind + "\",\"title\":\"" + title + "\",\"notes\":\"\",\"firstDay\":\"\",\"dueDay\":\"\",\"amount\":\"\","
                + "\"currency\":\"\",\"paymentKind\":\"\",\"category\":\"\",\"vendorName\":\"\",\"vendorEmail\":\"\",\"vendorPhone\":\"\","
                + "\"vendorWebsite\":\"\",\"howSure\":80,\"passage\":\"" + passage + "\"}";
    }

    /** The proposal with one field replaced by this raw JSON value (a quoted text or a number). */
    protected static String with(String proposal, String field, String rawValue) {
        String replaced = proposal.replaceAll("\"" + field + "\":(\"(?:[^\"\\\\]|\\\\.)*\"|[0-9]+)", java.util.regex.Matcher.quoteReplacement("\"" + field + "\":" + rawValue));
        if (replaced.equals(proposal) && !proposal.contains("\"" + field + "\":" + rawValue)) {
            throw new AssertionError("no such field: " + field);
        }
        return replaced;
    }

    protected static String proposals(String... proposals) {
        return "{\"proposals\":[" + String.join(",", proposals) + "]}";
    }
}
