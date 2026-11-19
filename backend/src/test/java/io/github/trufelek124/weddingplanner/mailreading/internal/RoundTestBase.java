package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.MailTestBase;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mail.internal.NotingForTests;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The reading as an installation has it: a made-up mailbox of Anna's and of Piotr's, the stand-in for Google behind the
 * mail module and the stand-in for the AI service behind the two steps. The round is run by the test, never by the clock.
 * All mail is made up (senders are at .example); nothing goes to a real service.
 */
@TestPropertySource(properties = {"app.reading.api-key=" + StandInAnthropic.KEY, "app.reading.automatic=false"})
abstract class RoundTestBase extends MailTestBase {

    static final String JAN = "Jan from Palace on the Hill <jan@palace-on-the-hill.example>";
    static final String JANS_ADDRESS = "jan@palace-on-the-hill.example";
    static final String POLISH = "Dzień dobry, w imieniu Pałacu na Wzgórzu potwierdzam rezerwację sali na 19 czerwca 2027. "
            + "Prosimy o wpłatę zaliczki w wysokości 2500.50 PLN do 1 grudnia 2026. Pozdrawiam, Jan";
    static final String DEPOSIT_PASSAGE = "Prosimy o wpłatę zaliczki w wysokości 2500.50 PLN do 1 grudnia 2026.";

    @Autowired
    ApplicationContext context;

    @Autowired
    ReadingSteps steps;

    @Autowired
    MailReading mail;

    @Autowired
    Suggestions suggestions;

    @Autowired
    Weddings weddings;

    @Autowired
    ProposalMaker maker;

    @Autowired
    UsageRepository usage;

    @Autowired
    Clock clock;

    @Autowired
    ReadingRound round;

    final StandInAnthropic service = StandInAnthropic.get();

    @DynamicPropertySource
    static void standInForTheService(DynamicPropertyRegistry registry) {
        registry.add("app.reading.base-url", StandInAnthropic.get()::baseUrl);
    }

    @BeforeEach
    void aQuietServiceAndAnEmptyCount() {
        service.reset();
        jdbc.update("DELETE FROM reading_usage");
        round.forget();
    }

    /** What was counted is not left for the test of another class, which may count on an empty table. */
    @AfterEach
    void noCountLeftBehind() {
        jdbc.update("DELETE FROM reading_usage");
    }

    // ---------------------------------------------------------------- the rules a test runs under

    static Rules.Price price(String in, String out) {
        return new Rules.Price(new BigDecimal(in), new BigDecimal(out));
    }

    /** The settings as they begin, with the round not running by itself. */
    static Rules rules() {
        return new Rules(true, Duration.ofMinutes(1), false, 20, 60, new BigDecimal("20"), 2000, 50, price("0.10", "0.50"), price("4", "20"));
    }

    ReadingRound roundOf(Rules rules) {
        return roundOf(rules, mail);
    }

    ReadingRound roundOf(Rules rules, MailReading withMail) {
        return new ReadingRound(steps, rules, withMail, suggestions, weddings, maker, usage, clock);
    }

    // ---------------------------------------------------------------- mail

    UUID weddingId() {
        return jdbc.queryForObject("SELECT id FROM wedding", UUID.class);
    }

    UUID anna() {
        return UUID.fromString(wedding.annaId);
    }

    UUID piotr() {
        return UUID.fromString(wedding.piotrId);
    }

    void connectAnna() throws Exception {
        // The mailbox exists (and says where its history stands) before the connection asks for its profile.
        google.mailbox(ANNA_MAILBOX).markerIs(7_391_000);
        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);
    }

    void connectPiotr() throws Exception {
        google.mailbox(PIOTR_MAILBOX).markerIs(8_472_000);
        assertThat(location(connect(wedding.piotr, PIOTR_MAILBOX))).isEqualTo(YES);
    }

    static Instant daysAgo(long days) {
        return TestClock.now().minus(Duration.ofDays(days));
    }

    /** A message in Anna's mailbox, from Jan, in its own thread. */
    void annaGets(String id, String subject, String text) {
        annaGets(id, "thread-" + id, 2, subject, text);
    }

    void annaGets(String id, String thread, long daysAgo, String subject, String text) {
        google.mailbox(ANNA_MAILBOX).write(id, thread, daysAgo(daysAgo), JAN, subject, Mime.plain(text), "INBOX");
    }

    /** A message with the identifier its sender gave it ({@code Message-ID}, written as given; none when null), in this mailbox. */
    void gets(String mailbox, String id, String thread, long daysAgo, String from, String subject, String text, String messageIdHeader) {
        google.mailbox(mailbox).writeWithMessageId(id, thread, daysAgo(daysAgo), from, subject, Mime.plain(text), messageIdHeader, "INBOX");
    }

    void piotrGets(String id, String subject, String text) {
        google.mailbox(PIOTR_MAILBOX).write(id, "thread-" + id, daysAgo(2), JAN, subject, Mime.plain(text), "INBOX");
    }

    /**
     * What the mail module notes now: the owners ask to look back as far as they may, as they do on the e-mail page,
     * and the sync looks once at every mailbox. Connecting notes nothing of what is in a mailbox (D-009).
     */
    void note() {
        NotingForTests.lookBackAndLookAtAll(context);
    }

    /** The verdict kept for a message (the module's own table; only a word, a reason and a number). */
    Map<String, Object> verdictOf(String messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT verdict, unread_reason, proposals FROM mail_message WHERE message_id = ?", messageId);
        assertThat(rows).as("the message was noted").hasSize(1);
        return new HashMap<>(rows.get(0));
    }

    boolean isRead(String messageId) {
        return verdictOf(messageId).get("verdict") != null;
    }

    // ---------------------------------------------------------------- the stand-in for the AI service

    static Reply triageSays(boolean yes, int sure) {
        return Reply.says(Answers.triage(yes, sure));
    }

    static Reply extractionSays(String... proposals) {
        return Reply.says(Answers.proposals(proposals));
    }

    /** How many calls the stand-in has had, by the model asked. */
    long callsTo(String model) {
        return service.calls().stream().filter(call -> model.equals(call.json().get("model"))).count();
    }

    long triageCalls() {
        return callsTo("claude-haiku-5-5");
    }

    long extractionCalls() {
        return callsTo("claude-opus-5-5");
    }

    // ---------------------------------------------------------------- what the owners see

    List<Map<String, Object>> waitingSuggestions() throws Exception {
        return TestHttp.jsonList(http.getAs(wedding.anna, "/api/suggestions"));
    }

    int suggestionRows() {
        return jdbc.queryForObject("SELECT count(*) FROM suggestion", Integer.class);
    }
}
