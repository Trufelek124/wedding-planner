package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Sender;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Measures how good the two steps of reading the mail are, with the invented samples of S04 section 10, and what they
 * cost. It runs the steps exactly as the server makes them ({@link ReadingConfiguration}), with the same settings, and
 * is started only by the Gradle task {@code measureReading}, which no other task depends on: the gate never runs it.
 *
 * <p><b>It calls the real AI service, and a call costs money.</b> So it calls nothing unless the key is in the
 * environment (APP_AI_API_KEY) <i>and</i> the owner said so on the command ({@code --spend-money=yes}). With
 * {@code --dry-run=yes} it only counts. The key is never printed, logged or written. An address other than the
 * service's own is taken only from the Java system property {@value #BASE_URL_PROPERTY}, which tests set and the task
 * does not pass on.
 *
 * <p>Options: {@code --spend-money=yes}, {@code --dry-run=yes}, {@code --only=pl,uk,en}, {@code --steps=triage|both},
 * {@code --limit=N} (the first N samples of each language), {@code --report=<file>}.
 */
public final class MeasureReading {

    /** The only way to point the tool at another address. Never read from the command line. */
    static final String BASE_URL_PROPERTY = "app.reading.base-url";

    static final String NEEDS = "Nothing was asked of the AI service: a run needs the key in the environment variable APP_AI_API_KEY and "
            + "-PspendMoney=yes on the command line, and it costs money (add -PdryRun=yes to only count the calls and estimate the cost).";

    static final String NOT_ACCEPTED = "The run stopped at once: the AI service does not accept the key, the account or a model that is set.";

    static final String DEFAULT_REPORT = "build/reports/reading-measure.md";

    private static final Duration PAUSE_BEFORE_ONE_MORE_TRY = Duration.ofSeconds(30);

    /** Settings that have defaults, as application.yml gives them; a test holds them against that file. */
    static final Map<String, String> DEFAULTS = Map.of(
            "APP_AI_TRIAGE_MODEL", "claude-haiku-5-5",
            "APP_AI_EXTRACTION_MODEL", "claude-opus-5-5",
            "APP_AI_TRIAGE_PRICE_IN", "0.10",
            "APP_AI_TRIAGE_PRICE_OUT", "0.50",
            "APP_AI_EXTRACTION_PRICE_IN", "4",
            "APP_AI_EXTRACTION_PRICE_OUT", "20",
            "MAIL_READING_TRIAGE_SURE", "60",
            "MAIL_READING_DOCUMENTS_ENABLED", "true",
            "MAIL_READING_DOCUMENTS_SURE", "80");

    /** The rough sizes the dry run counts with. */
    private static final int HEADER_CHARACTERS = 400;

    private static final int TRIAGE_ANSWER_TOKENS = 100;
    private static final int EXTRACTION_ANSWER_TOKENS = 1_500;

    private MeasureReading() {}

    /** What a run is given from outside: all of it replaceable in a test. */
    record Surroundings(Map<String, String> environment, Properties properties, PrintStream out, Path defaultReport, Duration pause, Clock clock, SampleSet samples) {}

    record Options(boolean spendMoney, boolean dryRun, List<String> only, boolean withExtraction, Integer limit, String report) {}

    public static void main(String[] args) {
        quietTheLog();
        int code = run(args, new Surroundings(System.getenv(), System.getProperties(), System.out, Path.of(DEFAULT_REPORT), PAUSE_BEFORE_ONE_MORE_TRY, Clock.systemUTC(), SampleSet.bundled()));
        System.exit(code);
    }

    static int run(String[] args, Surroundings around) {
        PrintStream out = around.out();
        Options options;
        try {
            options = options(args);
        } catch (IllegalArgumentException wrong) {
            out.println(wrong.getMessage());
            return 2;
        }
        List<SampleSet.Item> items = selected(around.samples(), options);
        if (items.isEmpty()) {
            out.println("There is no sample to run: -Ponly or -Plimit left none.");
            return 2;
        }
        Settings settings = Settings.read(around.environment());
        if (options.dryRun()) {
            return dryRun(out, items, options, settings);
        }
        String key = settings.apiKey();
        if (!options.spendMoney() || key.isEmpty()) {
            out.println(NEEDS);
            return 0;
        }
        return realRun(options, around, items, settings);
    }

    // ---------------------------------------------------------------------------------------------------------- options

    static Options options(String[] args) {
        boolean spend = false;
        boolean dry = false;
        List<String> only = new ArrayList<>(SampleSet.LANGUAGES);
        boolean both = true;
        Integer limit = null;
        String report = null;
        for (String arg : args) {
            int equals = arg.indexOf('=');
            String name = equals < 0 ? arg : arg.substring(0, equals);
            String value = equals < 0 ? "" : arg.substring(equals + 1);
            switch (name) {
                case "--spend-money" -> spend = yes(name, value);
                case "--dry-run" -> dry = yes(name, value);
                case "--only" -> {
                    only = new ArrayList<>();
                    for (String language : value.split(",")) {
                        if (!SampleSet.LANGUAGES.contains(language.strip())) {
                            throw new IllegalArgumentException("-Ponly names languages out of pl, uk, en, separated by commas.");
                        }
                        only.add(language.strip());
                    }
                }
                case "--steps" -> {
                    if (!value.equals("triage") && !value.equals("both")) {
                        throw new IllegalArgumentException("-Psteps is triage or both.");
                    }
                    both = value.equals("both");
                }
                case "--limit" -> {
                    try {
                        limit = Integer.valueOf(value);
                    } catch (NumberFormatException notANumber) {
                        throw new IllegalArgumentException("-Plimit is a whole number of samples per language, one or more.");
                    }
                    if (limit < 1) {
                        throw new IllegalArgumentException("-Plimit is a whole number of samples per language, one or more.");
                    }
                }
                case "--report" -> report = value;
                default -> throw new IllegalArgumentException("The tool does not know the option " + name + ".");
            }
        }
        return new Options(spend, dry, List.copyOf(only), both, limit, report);
    }

    private static boolean yes(String name, String value) {
        if (value.equals("yes")) {
            return true;
        }
        if (value.equals("no") || value.isEmpty()) {
            return false;
        }
        throw new IllegalArgumentException("The value of " + name + " is yes or no.");
    }

    private static List<SampleSet.Item> selected(SampleSet samples, Options options) {
        List<SampleSet.Item> chosen = new ArrayList<>();
        for (SampleSet.Collection collection : samples.collections()) {
            if (!options.only().contains(collection.language())) {
                continue;
            }
            int taken = 0;
            for (SampleSet.Sample sample : collection.samples()) {
                if (options.limit() != null && taken >= options.limit()) {
                    break;
                }
                chosen.add(new SampleSet.Item(collection, sample));
                taken++;
            }
        }
        return chosen;
    }

    // --------------------------------------------------------------------------------------------------------- settings

    /** The settings, from the environment, as the server reads them (the same names, the same starting values). */
    record Settings(String apiKey, ReadingProperties reading, RoundProperties round) {

        /** Holds the key: prints nothing of itself. */
        @Override
        public String toString() {
            return "Settings[]";
        }

        static Settings read(Map<String, String> env) {
            String key = text(env.get(ReadingSetup.API_KEY), "");
            ReadingProperties reading = new ReadingProperties(
                    key,
                    null,
                    text(env.get("APP_AI_TRIAGE_MODEL"), DEFAULTS.get("APP_AI_TRIAGE_MODEL")),
                    text(env.get("APP_AI_EXTRACTION_MODEL"), DEFAULTS.get("APP_AI_EXTRACTION_MODEL")),
                    Duration.ofMinutes(5));
            RoundProperties round = new RoundProperties(
                    "1m",
                    "false",
                    "20",
                    text(env.get("MAIL_READING_TRIAGE_SURE"), DEFAULTS.get("MAIL_READING_TRIAGE_SURE")),
                    text(env.get("MAIL_READING_DOCUMENTS_ENABLED"), DEFAULTS.get("MAIL_READING_DOCUMENTS_ENABLED")),
                    text(env.get("MAIL_READING_DOCUMENTS_SURE"), DEFAULTS.get("MAIL_READING_DOCUMENTS_SURE")),
                    "20",
                    "2000",
                    "50",
                    text(env.get("APP_AI_TRIAGE_PRICE_IN"), DEFAULTS.get("APP_AI_TRIAGE_PRICE_IN")),
                    text(env.get("APP_AI_TRIAGE_PRICE_OUT"), DEFAULTS.get("APP_AI_TRIAGE_PRICE_OUT")),
                    text(env.get("APP_AI_EXTRACTION_PRICE_IN"), DEFAULTS.get("APP_AI_EXTRACTION_PRICE_IN")),
                    text(env.get("APP_AI_EXTRACTION_PRICE_OUT"), DEFAULTS.get("APP_AI_EXTRACTION_PRICE_OUT")));
            return new Settings(key, reading, round);
        }

        private static String text(String value, String otherwise) {
            return value == null || value.isBlank() ? otherwise : value.strip();
        }
    }

    // ----------------------------------------------------------------------------------------------------------- dry run

    private static int dryRun(PrintStream out, List<SampleSet.Item> items, Options options, Settings settings) {
        RoundSetup.Parsed round = RoundSetup.parse(settings.round(), true);
        if (!round.invalid().isEmpty()) {
            out.println("A dry run needs valid settings; not valid: " + String.join(", ", round.invalid()) + ".");
            return 1;
        }
        out.println("Dry run: nothing is asked of the AI service.");
        out.println(estimate(items, options, round.rules()));
        return 0;
    }

    /** How many calls a run would make and what they would roughly cost, from the length of the texts alone. */
    static String estimate(List<SampleSet.Item> items, Options options, Rules rules) {
        Instructions instructions = Instructions.load();
        long triageCalls = items.size();
        long extractionCalls = options.withExtraction() ? items.stream().filter(item -> item.sample().expected().concernsTheWedding()).count() : 0;
        long triageIn = 0;
        long extractionIn = 0;
        for (SampleSet.Item item : items) {
            SampleSet.Sample sample = item.sample();
            double charactersPerToken = item.collection().language().equals("uk") ? 2.0 : 3.0;
            int triageText = Math.min(MailTriage.FIRST_CHARACTERS, sample.text().length());
            triageIn += tokens(instructions.triage().length() + HEADER_CHARACTERS + triageText, charactersPerToken);
            if (options.withExtraction() && sample.expected().concernsTheWedding()) {
                int full = sample.text().length() + sample.earlier().stream().mapToInt(earlier -> earlier.text().length()).sum()
                        + sample.alreadyProposed().stream().mapToInt(String::length).sum()
                        // The documents are counted as if triage let them through, as it should for a message about the wedding.
                        + documentsOf(sample, true).stream().mapToInt(document -> document.name().length() + document.text().length()).sum();
                extractionIn += tokens(instructions.extraction().length() + HEADER_CHARACTERS + full, charactersPerToken);
            }
        }
        long triageOut = triageCalls * TRIAGE_ANSWER_TOKENS;
        long extractionOut = extractionCalls * EXTRACTION_ANSWER_TOKENS;
        BigDecimal cost = MeasureCosts.of(triageIn, triageOut, rules.triagePrice()).add(MeasureCosts.of(extractionIn, extractionOut, rules.extractionPrice()));
        return "A real run would make " + triageCalls + " triage calls and " + extractionCalls + " extraction calls"
                + (options.withExtraction() ? " (more extraction calls if triage lets other messages through)" : " (extraction is not run: -Psteps=triage)")
                + ", " + (triageCalls + extractionCalls) + " in all.\n"
                + "Roughly " + (triageIn + extractionIn) + " tokens in and " + (triageOut + extractionOut) + " tokens out, about " + MeasureCosts.usd(cost)
                + " at the prices in the settings. This is a rough guess from the length of the texts.";
    }

    /**
     * What a sample's documents come to when they are sent: the first three, 40,000 characters in all, as the server does it.
     * {@code sent} is false where the server would not send any (triage was not sure enough).
     */
    static List<MailExtraction.Document> documentsOf(SampleSet.Sample sample, boolean sent) {
        List<MailExtraction.Document> documents = new ArrayList<>();
        if (!sent) {
            return documents;
        }
        int room = MailExtraction.MOST_DOCUMENT_CHARACTERS;
        for (SampleSet.SampleDocument document : sample.documents().stream().limit(MailExtraction.MOST_DOCUMENTS).toList()) {
            Optional<MailExtraction.Document> fitted = RequestText.fitted(document.name(), document.text(), false, room);
            if (fitted.isPresent()) {
                room -= RequestText.characters(fitted.get().text());
                documents.add(fitted.get());
            }
        }
        return documents;
    }

    private static long tokens(long characters, double charactersPerToken) {
        return (long) Math.ceil(characters / charactersPerToken);
    }

    // ----------------------------------------------------------------------------------------------------------- real run

    private static int realRun(Options options, Surroundings around, List<SampleSet.Item> items, Settings settings) {
        PrintStream out = around.out();
        String baseUrl = around.properties().getProperty(BASE_URL_PROPERTY);
        ReadingProperties reading = new ReadingProperties(
                settings.reading().apiKey(),
                baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip(),
                settings.reading().triageModel(),
                settings.reading().extractionModel(),
                settings.reading().requestTimeout());
        ReadingSetup.Parsed parsed = ReadingSetup.parse(reading);
        RoundSetup.Parsed round = RoundSetup.parse(settings.round(), parsed.config() != null);
        if (parsed.config() == null || !round.invalid().isEmpty()) {
            List<String> invalid = new ArrayList<>(parsed.invalid());
            invalid.addAll(round.invalid());
            out.println("Nothing was asked of the AI service: these settings are not valid: " + String.join(", ", invalid) + ".");
            return 1;
        }
        Rules rules = round.rules();
        out.println("About to call the real AI service, one call at a time. This costs money.");
        out.println(estimate(items, options, rules));
        Run run = new Run(around, options, parsed.config(), rules, items);
        Path file = options.report() == null ? around.defaultReport() : Path.of(options.report());
        try (ReadingSteps steps = new ReadingConfiguration().readingSteps(reading, rules)) {
            boolean ended = run.measure(steps, out);
            if (ended) {
                out.println(NOT_ACCEPTED);
                if (run.answeredAnything()) {
                    write(file, run.render(true), out);
                }
                return 1;
            }
            write(file, run.render(false), out);
            return 0;
        }
    }

    private static void write(Path file, String text, PrintStream out) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The report could not be written to " + file, e);
        }
        out.println(text);
        out.println("The report was written to " + file + ".");
    }

    /** One run in progress: what has been asked and answered so far. */
    private static final class Run {

        private final Surroundings around;
        private final Options options;
        private final ReadingSetup.Config config;
        private final Rules rules;
        private final List<SampleSet.Item> items;
        private final Map<String, List<Scoring.Measured>> rows = new LinkedHashMap<>();
        private final MeasureCosts costs = new MeasureCosts();
        private boolean answered;

        Run(Surroundings around, Options options, ReadingSetup.Config config, Rules rules, List<SampleSet.Item> items) {
            this.around = around;
            this.options = options;
            this.config = config;
            this.rules = rules;
            this.items = items;
            for (SampleSet.Item item : items) {
                rows.computeIfAbsent(item.collection().language(), language -> new ArrayList<>());
            }
        }

        boolean answeredAnything() {
            return answered;
        }

        /** Runs every sample, in the fixed order. Returns true when the service did not accept the key and the run was ended at once. */
        boolean measure(ReadingSteps steps, PrintStream out) {
            int number = 0;
            for (SampleSet.Item item : items) {
                number++;
                SampleSet.Sample sample = item.sample();
                out.println("[" + number + "/" + items.size() + "] " + sample.id());
                MailTriage.Input triageInput = new MailTriage.Input(sender(sample.from()), sample.subject(), sample.arrived(), sample.text());
                Outcome<MailTriage.Triaged> triage = attempt(true, () -> steps.triage(triageInput));
                if (notAccepted(triage)) {
                    return true;
                }
                costs.triageMessages++;
                Outcome<MailExtraction.Extracted> extraction = null;
                if (options.withExtraction() && (sample.expected().concernsTheWedding() || Scoring.passedOn(triage, rules.triageSure()))) {
                    MailExtraction.Input extractionInput = extractionInput(item, Scoring.documentsRead(triage, rules));
                    extraction = attempt(false, () -> steps.extract(extractionInput));
                    if (notAccepted(extraction)) {
                        return true;
                    }
                    costs.extractionMessages++;
                }
                rows.get(item.collection().language()).add(new Scoring.Measured(item, triage, extraction));
            }
            return false;
        }

        private boolean notAccepted(Outcome<?> outcome) {
            return outcome instanceof Outcome.Unanswered<?> unanswered && unanswered.why() == Outcome.Why.NOT_ACCEPTED;
        }

        /** One call; a "try later" is tried once more after a pause, and then counts as failed. */
        private <A> Outcome<A> attempt(boolean triageStep, Supplier<Outcome<A>> call) {
            Outcome<A> first = call.get();
            costs.record(triageStep, first);
            note(first);
            if (!(first instanceof Outcome.Unanswered<A> unanswered) || unanswered.why() != Outcome.Why.TRY_LATER) {
                return first;
            }
            try {
                Thread.sleep(around.pause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return first;
            }
            Outcome<A> second = call.get();
            costs.record(triageStep, second);
            note(second);
            return second;
        }

        private void note(Outcome<?> outcome) {
            if (outcome instanceof Outcome.Answered<?>) {
                answered = true;
            }
        }

        String render(boolean endedEarly) {
            Map<String, Integer> selected = new LinkedHashMap<>();
            for (SampleSet.Item item : items) {
                selected.merge(item.collection().language(), 1, Integer::sum);
            }
            return MeasureReport.render(new MeasureReport.Run(
                    around.clock().instant(),
                    config.triageModel(),
                    config.extractionModel(),
                    options.withExtraction(),
                    endedEarly,
                    rules,
                    rows,
                    selected,
                    costs));
        }
    }

    /** @param documentsSent whether the server would send the documents with this message: triage said yes, and was sure enough */
    private static MailExtraction.Input extractionInput(SampleSet.Item item, boolean documentsSent) {
        SampleSet.Sample sample = item.sample();
        List<MailExtraction.EarlierMessage> earlier = sample.earlier().stream()
                .map(message -> new MailExtraction.EarlierMessage(sender(message.from()), message.arrived(), message.text(), message.readNow()))
                .toList();
        return new MailExtraction.Input(
                sender(sample.from()), sample.subject(), sample.arrived(), sample.text(), earlier, item.collection().today(),
                item.collection().weddingDate(), sample.alreadyProposed(), documentsOf(sample, documentsSent));
    }

    private static Sender sender(SampleSet.Person person) {
        return new Sender(person.name(), person.address());
    }

    /** The service's own client says nothing worth reading at INFO; the tool prints what matters. */
    private static void quietTheLog() {
        try {
            if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context) {
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.WARN);
            }
        } catch (LinkageError | RuntimeException noLogback) {
            // Then the log stays as it is.
        }
    }
}
