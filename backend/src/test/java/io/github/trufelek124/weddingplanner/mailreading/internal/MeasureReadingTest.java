package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tool, run against {@link StandInAnthropic}: what it calls and what it does not, what it says, and what it writes.
 * Nothing here goes near the real AI service: the key is made up and the address is the stand-in's.
 */
class MeasureReadingTest {

    private static final String KEY = StandInAnthropic.KEY;

    /** Words that stand only in the samples below: none of them may ever come out of the tool. */
    private static final String[] WORDS = {"Zażółć-Qx", "Gęślą-Qx", "Imię-Qx", "qx@qx-sender.example", "Temat-Qx", "Pasaż-Qx", "Wcześniej-Qx", "Tytuł-Qx"};

    private final StandInAnthropic service = StandInAnthropic.get();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @TempDir
    Path folder;

    @BeforeEach
    void aQuietService() {
        service.reset();
    }

    // The made-up samples: three in Polish, three in English.
    private static String file(String language, String... samples) {
        return "{\"language\":\"" + language + "\",\"today\":\"2026-11-19\",\"weddingDate\":\"2027-06-12\",\"samples\":[" + String.join(",", samples) + "]}";
    }

    private static String sample(String id, boolean about, String expectedProposals) {
        return "{\"id\":\"" + id + "\",\"sort\":\"" + (about ? "proposal" : "unrelated") + "\",\"from\":{\"name\":\"Imię-Qx\",\"address\":\"qx@qx-sender.example\"},"
                + "\"subject\":\"Temat-Qx\",\"arrived\":\"2026-11-17T09:00:00Z\",\"text\":\"Zażółć-Qx Gęślą-Qx zaliczkę 5000 zł do 4 grudnia 2026 Pasaż-Qx.\","
                + "\"earlier\":[{\"from\":{\"name\":\"Imię-Qx\",\"address\":\"qx@qx-sender.example\"},\"arrived\":\"2026-11-10T09:00:00Z\",\"text\":\"Wcześniej-Qx\"}],"
                + "\"alreadyProposed\":[\"Tytuł-Qx\"],\"why\":\"in the text\",\"expected\":{\"concernsTheWedding\":" + about + ",\"proposals\":" + expectedProposals + "}}";
    }

    /** A thread: two earlier messages that are read now, a later message that changed the amount (600 to 700), and what must not be proposed. */
    private static String threadSample(String id) {
        return "{\"id\":\"" + id + "\",\"sort\":\"hard\",\"from\":{\"name\":\"Imię-Qx\",\"address\":\"qx@qx-sender.example\"},"
                + "\"subject\":\"Temat-Qx\",\"arrived\":\"2026-11-17T09:00:00Z\",\"text\":\"Poprawka-Qx: zaliczka 700 zł do 4 grudnia 2026.\","
                + "\"earlier\":[{\"from\":{\"name\":\"Imię-Qx\",\"address\":\"qx@qx-sender.example\"},\"arrived\":\"2026-11-10T09:00:00Z\",\"text\":\"Pierwszy-Qx zaliczka 600 zł\",\"readNow\":true},"
                + "{\"from\":{\"name\":\"Imię-Qx\",\"address\":\"qx@qx-sender.example\"},\"arrived\":\"2026-11-11T09:00:00Z\",\"text\":\"Drugi-Qx\"}],"
                + "\"why\":\"in the text\",\"expected\":{\"concernsTheWedding\":true,\"proposals\":[{\"kind\":\"PAYMENT\",\"titleMentions\":[\"zaliczk\"],\"dueDate\":\"2026-12-04\"}],"
                + "\"mustNotPropose\":[\"600\"]}}";
    }

    private static final String DEPOSIT_EXPECTED =
            "[{\"kind\":\"PAYMENT\",\"titleMentions\":[\"zaliczk\"],\"dueDate\":\"2026-12-04\",\"amount\":{\"value\":\"5000\",\"currency\":\"PLN\"},\"paymentKind\":\"DEPOSIT\"}]";

    private SampleSet samples(String... samplesInPolish) {
        return new SampleSet(List.of(SampleSet.parse(file("pl", samplesInPolish))));
    }

    private static String triage(boolean yes, int sure) {
        return "{\"concernsTheWedding\":" + yes + ",\"howSure\":" + sure + "}";
    }

    private static String deposit(String title) {
        return Answers.proposals(Answers.with(Answers.with(Answers.with(Answers.with(
                Answers.proposal("PAYMENT", title, "zaliczkę 5000 zł do 4 grudnia 2026"), "amount", "5000.00"), "currency", "PLN"), "dueDay", "2026-12-04"), "paymentKind", "DEPOSIT"));
    }

    private MeasureReading.Surroundings around(Map<String, String> environment, SampleSet set) {
        Properties properties = new Properties();
        properties.setProperty(MeasureReading.BASE_URL_PROPERTY, service.baseUrl());
        return new MeasureReading.Surroundings(
                environment, properties, new PrintStream(captured, true, StandardCharsets.UTF_8), folder.resolve("reading-measure.md"), Duration.ZERO,
                Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC), set);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String report() throws IOException {
        return Files.readString(folder.resolve("reading-measure.md"));
    }

    // ---------------------------------------------------------------------------------------- nothing is called unasked

    @Test
    void withoutTheKeyNothingIsCalledAndTheSentenceSaysWhatItNeeds() throws IOException {
        int code = MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of(), samples(sample("pl-a", true, DEPOSIT_EXPECTED))));

        assertThat(code).isZero();
        assertThat(service.calls()).isEmpty();
        assertThat(output().strip()).isEqualTo(MeasureReading.NEEDS);
        assertThat(MeasureReading.NEEDS).contains("APP_AI_API_KEY").contains("-PspendMoney=yes").contains("costs money").doesNotContain("\n");
        assertThat(folder.resolve("reading-measure.md")).doesNotExist();
    }

    @Test
    void withoutTheOwnersWordNothingIsCalledEvenWithTheKey() {
        for (String[] args : new String[][] {{}, {"--spend-money=no"}, {"--only=pl"}}) {
            captured.reset();

            int code = MeasureReading.run(args, around(Map.of("APP_AI_API_KEY", KEY), samples(sample("pl-a", true, DEPOSIT_EXPECTED))));

            assertThat(code).isZero();
            assertThat(service.calls()).isEmpty();
            assertThat(output().strip()).isEqualTo(MeasureReading.NEEDS);
            assertThat(output()).doesNotContain(KEY);
        }
        assertThat(folder.resolve("reading-measure.md")).doesNotExist();
    }

    @Test
    void aBlankKeyIsNoKey() {
        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", "   "), samples(sample("pl-a", true, DEPOSIT_EXPECTED))));

        assertThat(service.calls()).isEmpty();
        assertThat(output().strip()).isEqualTo(MeasureReading.NEEDS);
    }

    @Test
    void aDryRunOnlyCountsAndCallsNothingWithOrWithoutKeyAndWord() {
        SampleSet set = samples(sample("pl-a", true, DEPOSIT_EXPECTED), sample("pl-b", false, "[]"), sample("pl-c", true, DEPOSIT_EXPECTED));
        for (Map<String, String> environment : List.of(Map.<String, String>of(), Map.of("APP_AI_API_KEY", KEY))) {
            for (String[] args : new String[][] {{"--dry-run=yes"}, {"--dry-run=yes", "--spend-money=yes"}}) {
                captured.reset();

                int code = MeasureReading.run(args, around(environment, set));

                assertThat(code).isZero();
                assertThat(service.calls()).isEmpty();
                assertThat(output()).contains("Dry run: nothing is asked of the AI service.");
                assertThat(output()).contains("3 triage calls and 2 extraction calls").contains("5 in all");
                assertThat(output()).containsPattern("Roughly \\d+ tokens in and 3300 tokens out, about \\d+\\.\\d{4} USD");
                assertThat(output()).doesNotContain(KEY);
            }
        }
        assertThat(folder.resolve("reading-measure.md")).doesNotExist();
    }

    @Test
    void theDryRunOfTriageOnlyCountsNoExtraction() {
        MeasureReading.run(new String[] {"--dry-run=yes", "--steps=triage"}, around(Map.of(), samples(sample("pl-a", true, DEPOSIT_EXPECTED))));

        assertThat(output()).contains("1 triage calls and 0 extraction calls").contains("extraction is not run");
    }

    @Test
    void theServicesAddressIsNotAnOptionOfTheCommand() {
        int code = MeasureReading.run(new String[] {"--spend-money=yes", "--base-url=https://elsewhere.example"}, around(Map.of("APP_AI_API_KEY", KEY), samples(sample("pl-a", true, "[]"))));

        assertThat(code).isEqualTo(2);
        assertThat(service.calls()).isEmpty();
        assertThat(output()).contains("does not know the option --base-url");
    }

    @Test
    void settingsThatAreNotValidStopTheRunBeforeAnyCallAndAreNamedWithoutTheirValues() {
        int code = MeasureReading.run(new String[] {"--spend-money=yes"},
                around(Map.of("APP_AI_API_KEY", KEY, "APP_AI_TRIAGE_MODEL", "Not A Model", "APP_AI_EXTRACTION_PRICE_IN", "dear"), samples(sample("pl-a", true, "[]"))));

        assertThat(code).isEqualTo(1);
        assertThat(service.calls()).isEmpty();
        assertThat(output()).contains("APP_AI_TRIAGE_MODEL").contains("APP_AI_EXTRACTION_PRICE_IN").doesNotContain("Not A Model").doesNotContain("dear").doesNotContain(KEY);
    }

    @Test
    void anOptionThatIsWrongIsSaidSoAndNothingIsCalled() {
        for (String wrong : new String[] {"--only=de", "--steps=all", "--limit=0", "--limit=many", "--spend-money=maybe", "--frobnicate"}) {
            captured.reset();

            int code = MeasureReading.run(new String[] {wrong}, around(Map.of("APP_AI_API_KEY", KEY), samples(sample("pl-a", true, "[]"))));

            assertThat(code).as(wrong).isEqualTo(2);
            assertThat(service.calls()).as(wrong).isEmpty();
        }
    }

    // ----------------------------------------------------------------------------------------------- a scripted run

    /**
     * Six samples, answered in the order the tool asks: pl-a (right yes, the deposit found with everything right), pl-b (not
     * about the wedding, right no), pl-c (about the wedding, triage says no, 30 sure: read on all the same, and extraction
     * proposes a deposit under another title), en-d (refused), en-e (try later, then works: a no), en-f (an answer that is
     * no answer).
     */
    private SampleSet sixSamples() {
        SampleSet.Collection polish = SampleSet.parse(file("pl",
                sample("pl-a", true, DEPOSIT_EXPECTED), sample("pl-b", false, "[]"), sample("pl-c", true, DEPOSIT_EXPECTED)));
        SampleSet.Collection english = SampleSet.parse(file("en",
                sample("en-d", true, DEPOSIT_EXPECTED), sample("en-e", false, "[]"), sample("en-f", true, "[]")));
        return new SampleSet(List.of(polish, english));
    }

    private void scriptTheSix() {
        Reply tryLater = Reply.error(529, "overloaded_error", "busy");
        service.answerNextCallsWith(
                // pl-a
                Reply.says(triage(true, 90), "claude-haiku-5-5", 100, 10),
                Reply.says(deposit("Zaliczka na dwór"), "claude-opus-5-5", 1_000, 200),
                // pl-b
                Reply.says(triage(false, 95), "claude-haiku-5-5", 100, 10),
                // pl-c: a no that is not sure, read on
                Reply.says(triage(false, 30), "claude-haiku-5-5", 100, 10),
                Reply.says(deposit("Opłata za salę"), "claude-opus-5-5", 1_000, 200),
                // en-d: triage declined; extraction runs because the sample is about the wedding, and is declined too
                Reply.refusal(),
                Reply.refusal(),
                // en-e: the service's own client tries three times, the tool once more after the pause
                tryLater, tryLater, tryLater,
                Reply.says(triage(false, 80), "claude-haiku-5-5", 100, 10),
                // en-f: an answer that is no answer
                Reply.says("nonsense", "claude-haiku-5-5", 100, 10),
                Reply.says(Answers.proposals(), "claude-opus-5-5", 1_000, 200));
    }

    @Test
    void aScriptedRunMakesTheCallsInTheFixedOrderAndTheReportHasTheNumbers() throws IOException {
        scriptTheSix();

        int code = MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), sixSamples()));

        assertThat(code).isZero();
        // 3 + 3 triage calls (one of them twice) and 4 extractions (pl-a, pl-c, en-d and en-f); the service's own client made two more
        // requests for the busy answer, which are not the tool's calls.
        assertThat(service.calls()).hasSize(7 + 4 + 2);
        assertThat(service.calls().get(0).json().get("model")).isEqualTo("claude-haiku-5-5");
        assertThat(service.calls().get(1).json().get("model")).isEqualTo("claude-opus-5-5");
        String report = report();
        assertThat(output()).contains("About to call the real AI service").contains("This costs money.").contains("6 triage calls and 4 extraction calls");
        assertThat(output()).contains(report);
        // Triage. pl: a right, b right, c wrong (a no 30 sure, about the wedding). en: d no answer, e wrong?  e is a no and expected no:
        // right; f no answer.
        assertThat(report).contains("| pl | 3 | 0 | 2 | 1 | 1 of 2 | 0 of 1 |");
        assertThat(report).contains("| en | 1 | 2 | 1 | 0 | 0 of 0 | 0 of 1 |");
        assertThat(report).contains("| all | 4 | 2 | 3 | 1 | 1 of 2 | 0 of 2 |");
        assertThat(report).contains("- misses, pl: pl-c (sure 30)");
        assertThat(report).contains("less sure than 60 and so read on by the server's own rule all the same: 1");
        // Extraction. pl: a (found, day, amount, kind right) and c (found: its title says Opłata, which does not mention zaliczk: not found).
        assertThat(report).contains("| pl | 2 | 0 | 2 | 1 of 2 | 1 of 1 | 1 of 1 | 1 of 1 | 0 of 0 | 0 |");
        assertThat(report).contains("- triage, en-d: DECLINED").contains("- extraction, en-d: DECLINED").contains("- triage, en-f: CANNOT_BE_USED");
        // Cost: triage calls: pl-a, pl-b, pl-c, en-d (declined, no tokens beyond the refusal's 77 in), en-e twice, en-f.
        assertThat(report).contains("| triage | `claude-haiku-5-5` |");
        assertThat(report).contains("| extraction | `claude-opus-5-5` |");
        assertThat(report).contains("Per 1,000 messages, at 5 in 100 (D-007's assumption):");
        assertThat(report).contains("A first look over 5,000 messages at 5 in 100:");
        // The report reaches the person who ran it, and does not hold the key.
        assertThat(output()).doesNotContain(KEY);
        assertThat(report).doesNotContain(KEY);
    }

    @Test
    void anEarlierMessageThatIsReadNowIsSaidSoInTheRequestAndTheReportCountsWhatStandsNoLongerProposed() throws IOException {
        service.answerNextCallsWith(
                Reply.says(triage(true, 90), "claude-haiku-5-5", 100, 10),
                Reply.says(Answers.proposals(Answers.proposal("PAYMENT", "Zaliczka 600 zł", "Pierwszy-Qx zaliczka 600 zł")), "claude-opus-5-5", 1_000, 200));

        int code = MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), samples(threadSample("pl-t"))));

        assertThat(code).isZero();
        String request = service.calls().get(1).userTurn();
        assertThat(request).contains("Read before: no - it is read now, with the message").contains("Read before: yes").contains("At most 8 proposals");
        assertThat(report()).contains("## Threads whose earlier messages are read now").contains("- pl-t: STALE PROPOSED").contains("Stale proposed: 1 of 1");
        assertThat(report()).doesNotContain("Qx").doesNotContain("Messages that try to instruct\n\n- ");
    }

    @Test
    void theTryLaterThatThenWorksIsOneCallThatCameBackAndAFailedOneIsCountedAsFailed() throws IOException {
        Reply tryLater = Reply.error(529, "overloaded_error", "busy");
        // First sample: busy three times, then again three times after the pause: failed. Second: busy three times, and after the pause it works.
        service.answerNextCallsWith(tryLater, tryLater, tryLater, tryLater, tryLater, tryLater, tryLater, tryLater, tryLater,
                Reply.says(triage(false, 90), "claude-haiku-5-5", 100, 10));
        SampleSet set = samples(sample("pl-a", false, "[]"), sample("pl-b", false, "[]"));

        MeasureReading.run(new String[] {"--spend-money=yes", "--steps=triage"}, around(Map.of("APP_AI_API_KEY", KEY), set));
        String report = report();

        assertThat(report).contains("- triage, pl-a: TRY_LATER");
        assertThat(report).doesNotContain("pl-b: ");
        assertThat(report).contains("| pl | 1 | 1 | 1 | 0 | 0 of 0 | 0 of 1 |");
        // Four calls of the tool were made: two for pl-a, two for pl-b (the second after the pause, as for pl-a).
        assertThat(report).contains("| triage | `claude-haiku-5-5` | 1 | 100 | 10 |");
        assertThat(report).contains("| triage | (no answer) | 3 | 0 | 0 |");
    }

    @Test
    void aKeyThatIsNotAcceptedEndsTheRunAtOnceWithOneSentenceAndWritesNothingWhenNothingWasAnswered() {
        service.answerEveryCallWith(Reply.error(401, "authentication_error", "invalid x-api-key"));

        int code = MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), sixSamples()));

        assertThat(code).isEqualTo(1);
        assertThat(service.calls()).as("the first call, and no other").hasSize(1);
        assertThat(output()).contains(MeasureReading.NOT_ACCEPTED);
        assertThat(output()).doesNotContain(KEY);
        assertThat(folder.resolve("reading-measure.md")).doesNotExist();
    }

    @Test
    void aKeyThatStopsBeingAcceptedMidwayEndsTheRunAndKeepsWhatWasMeasured() throws IOException {
        service.answerNextCallsWith(
                Reply.says(triage(false, 95), "claude-haiku-5-5", 100, 10),
                Reply.error(401, "authentication_error", "invalid x-api-key"));
        SampleSet set = samples(sample("pl-a", false, "[]"), sample("pl-b", false, "[]"), sample("pl-c", false, "[]"));

        int code = MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), set));

        assertThat(code).isEqualTo(1);
        assertThat(service.calls()).hasSize(2);
        assertThat(output()).contains(MeasureReading.NOT_ACCEPTED);
        assertThat(report()).contains("The run ended early").contains("| pl | 1 | 0 | 1 | 0 |");
    }

    @Test
    void onlyAndLimitChooseTheSamplesAndTriageOnlyAsksNothingMore() throws IOException {
        service.answerEveryCallWith(Reply.says(triage(true, 90), "claude-haiku-5-5", 100, 10));

        int code = MeasureReading.run(new String[] {"--spend-money=yes", "--only=en", "--limit=2", "--steps=triage"}, around(Map.of("APP_AI_API_KEY", KEY), sixSamples()));

        assertThat(code).isZero();
        assertThat(service.calls()).as("two English samples, triage only").hasSize(2);
        assertThat(report()).contains("Samples: en 2 (2 in all)").contains("Steps: triage only").doesNotContain("## Extraction");
        assertThat(report()).contains("Extraction was not measured");
    }

    @Test
    void theModelsComeFromTheSameSettingsAsTheServersAndTheRequestsCarryOnlyWhatTheServersDo() throws IOException {
        service.answerEveryCallWith(Reply.says(triage(false, 99), "my-small-model", 100, 10));

        MeasureReading.run(new String[] {"--spend-money=yes", "--steps=triage"},
                around(Map.of("APP_AI_API_KEY", KEY, "APP_AI_TRIAGE_MODEL", "my-small-model"), samples(sample("pl-a", false, "[]"))));

        assertThat(service.onlyCall().json().get("model")).isEqualTo("my-small-model");
        assertThat(service.onlyCall().headers()).containsEntry("authorization", "Bearer " + KEY);
        assertThat(service.onlyCall().system()).isEqualTo(Instructions.load().triage());
        assertThat(report()).contains("triage `my-small-model`");
    }

    @Test
    void theReportHoldsNoWordOfASamplesTextAndNotTheKeyAndTheOutputNeitherDoes() throws IOException {
        scriptTheSix();

        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), sixSamples()));

        for (String word : WORDS) {
            assertThat(report()).as("the report, " + word).doesNotContain(word);
            assertThat(output()).as("the output, " + word).doesNotContain(word);
        }
        assertThat(report()).doesNotContain(KEY).doesNotContain(KEY.substring(0, 12));
        assertThat(output()).doesNotContain(KEY.substring(0, 12));
        // Ids are the one thing of a sample that is in it, where a sample is named at all (a miss, an outcome that is not an answer).
        assertThat(report()).contains("pl-c").contains("en-d");
    }

    // ------------------------------------------------------------------------------- a sample that carries documents

    /** Words that stand only in the documents below: none of them may ever come out of the tool. */
    private static final String[] DOCUMENT_WORDS = {"Dokument-Qx", "Plik-Qx.pdf", "Rata-Qx"};

    private static String sampleWithDocuments(String id) {
        return sample(id, true, DEPOSIT_EXPECTED).replace("\"alreadyProposed\"",
                "\"documents\":[{\"name\":\"Plik-Qx.pdf\",\"text\":\"Dokument-Qx Rata-Qx: zaliczkę 5000 zł do 4 grudnia 2026\"},"
                        + "{\"name\":\"Drugi-Qx.pdf\",\"text\":\"Drugi dokument-Qx\"}],\"alreadyProposed\"");
    }

    @Test
    void aSampleMayCarryDocumentsAsTextAndOneWithoutHasNone() {
        SampleSet.Collection file = SampleSet.parse(file("pl", sampleWithDocuments("pl-d"), sample("pl-a", true, DEPOSIT_EXPECTED)));

        assertThat(file.samples().get(0).documents()).extracting(SampleSet.SampleDocument::name).containsExactly("Plik-Qx.pdf", "Drugi-Qx.pdf");
        assertThat(file.samples().get(0).documents().get(0).text()).startsWith("Dokument-Qx");
        assertThat(file.samples().get(1).documents()).isEmpty();
        assertThat(file.samples().get(0).toString()).doesNotContain("Qx").doesNotContain("Dokument");
    }

    @Test
    void theDocumentsOfASampleGoToExtractionWhereTriageSaysYesAndIsSureEnoughAndNowhereElse() {
        service.answerNextCallsWith(
                Reply.says(triage(true, 95), "claude-haiku-5-5", 100, 10), Reply.says(Answers.proposals(), "claude-opus-5-5", 1_000, 200),
                Reply.says(triage(true, 79), "claude-haiku-5-5", 100, 10), Reply.says(Answers.proposals(), "claude-opus-5-5", 1_000, 200),
                Reply.says(triage(false, 30), "claude-haiku-5-5", 100, 10), Reply.says(Answers.proposals(), "claude-opus-5-5", 1_000, 200));
        SampleSet set = samples(sampleWithDocuments("pl-a"), sampleWithDocuments("pl-b"), sampleWithDocuments("pl-c"));

        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), set));

        List<StandInAnthropic.Call> extractions = service.calls().stream().filter(call -> "claude-opus-5-5".equals(call.json().get("model"))).toList();
        assertThat(extractions).hasSize(3);
        assertThat(extractions.get(0).userTurn()).as("a yes, 95 sure").contains("document 1 of 2 that came with the message", "Dokument-Qx Rata-Qx", "document 2 of 2")
                .contains("File name: Plik-Qx.pdf").contains("At most 8 proposals");
        assertThat(extractions.get(1).userTurn()).as("a yes, 79 sure").doesNotContain("came with the message").contains("At most 5 proposals");
        assertThat(extractions.get(2).userTurn()).as("a no read on").doesNotContain("came with the message");
        for (StandInAnthropic.Call triageCall : service.calls().stream().filter(call -> "claude-haiku-5-5".equals(call.json().get("model"))).toList()) {
            assertThat(triageCall.userTurn()).as("triage never sees a document").doesNotContain("Dokument-Qx").doesNotContain("Plik-Qx");
        }
    }

    @Test
    void theShareAndTheSwitchForDocumentsComeFromTheSameSettingsAsTheServers() {
        service.answerEveryCallWith(Reply.says(triage(true, 70), "claude-haiku-5-5", 100, 10));
        SampleSet set = samples(sampleWithDocuments("pl-a"));

        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY, "MAIL_READING_DOCUMENTS_SURE", "70"), set));
        assertThat(service.calls().getLast().userTurn()).as("70 is sure enough where the share is 70").contains("came with the message");

        service.reset();
        service.answerEveryCallWith(Reply.says(triage(true, 99), "claude-haiku-5-5", 100, 10));
        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY, "MAIL_READING_DOCUMENTS_ENABLED", "false"), set));
        assertThat(service.calls().getLast().userTurn()).as("switched off").doesNotContain("came with the message");
    }

    @Test
    void theDryRunCountsTheLengthOfTheDocumentsOnlyAsFarAsTheServerWouldSendThem() {
        MeasureReading.Options options = new MeasureReading.Options(false, true, List.of(), true, null, null);
        SampleSet plain = samples(sample("pl-a", true, DEPOSIT_EXPECTED));
        String longText = "x".repeat(30_000);
        String big = sample("pl-a", true, DEPOSIT_EXPECTED).replace("\"alreadyProposed\"",
                "\"documents\":[{\"name\":\"a.pdf\",\"text\":\"" + longText + "\"},{\"name\":\"b.pdf\",\"text\":\"" + longText + "\"},"
                        + "{\"name\":\"c.pdf\",\"text\":\"" + longText + "\"},{\"name\":\"d.pdf\",\"text\":\"" + longText + "\"}],\"alreadyProposed\"");
        SampleSet withDocuments = samples(big);
        Rules rules = RoundSetup.parse(new RoundProperties("1m", "false", "20", "60", "true", "80", "20", "2000", "50", "0.10", "0.50", "4", "20"), true).rules();

        String without = MeasureReading.estimate(plain.items(), options, rules);
        String with = MeasureReading.estimate(withDocuments.items(), options, rules);

        assertThat(tokensIn(with)).as("documents are counted").isGreaterThan(tokensIn(without) + 10_000);
        // Forty thousand characters at three to a token: not the hundred and twenty thousand that were given.
        assertThat(tokensIn(with)).as("only what the server would send").isLessThan(tokensIn(without) + 14_500);
        // 30,000 and then the 10,000 that are left of the 40,000: the third and the fourth find no room.
        assertThat(MeasureReading.documentsOf(withDocuments.items().get(0).sample(), true)).hasSize(2);
        assertThat(MeasureReading.documentsOf(withDocuments.items().get(0).sample(), true).stream().mapToInt(document -> document.text().length()).sum())
                .isEqualTo(40_000);
        assertThat(MeasureReading.documentsOf(withDocuments.items().get(0).sample(), false)).isEmpty();
    }

    private static long tokensIn(String estimate) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Roughly (\\d+) tokens in").matcher(estimate);
        assertThat(matcher.find()).as(estimate).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void theReportAndTheOutputHoldNoWordOfADocumentEither() throws IOException {
        service.answerNextCallsWith(Reply.says(triage(true, 95), "claude-haiku-5-5", 100, 10), Reply.says(deposit("Zaliczka"), "claude-opus-5-5", 1_000, 200));

        MeasureReading.run(new String[] {"--spend-money=yes"}, around(Map.of("APP_AI_API_KEY", KEY), samples(sampleWithDocuments("pl-d"))));

        for (String word : DOCUMENT_WORDS) {
            assertThat(report()).as("the report, " + word).doesNotContain(word);
            assertThat(output()).as("the output, " + word).doesNotContain(word);
        }
    }

    // ------------------------------------------------------------------------------------------------------ the rest

    @Test
    void theStartingValuesThisToolHoldsAreTheOnesApplicationYmlHolds() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));

        MeasureReading.DEFAULTS.forEach((name, value) -> assertThat(yml).as(name).contains("${" + name + ":" + value + "}"));
    }

    @Test
    void theGradleTaskExistsAndNothingDependsOnIt() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));

        assertThat(build).contains("tasks.register<JavaExec>(\"measureReading\")");
        for (String line : build.lines().toList()) {
            if (line.contains("measureReading") && !line.stripLeading().startsWith("//")) {
                assertThat(line).as("only its own registration may name it").contains("tasks.register<JavaExec>(\"measureReading\")");
            }
        }
        assertThat(build).doesNotContain("dependsOn(\"measureReading\")").doesNotContain("finalizedBy(\"measureReading\")").doesNotContain("measureReading\"))");
        // The task never passes the service's address on, and the key only comes from the environment.
        String task = build.substring(build.indexOf("tasks.register<JavaExec>(\"measureReading\")"));
        assertThat(task).doesNotContain("base-url").doesNotContain("systemProperty").doesNotContain("environment(").doesNotContain("dependsOn");
    }
}
