package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the sample set itself (S04 section 10): that it is whole, that it is invented as far as a test can see, and that
 * what a sample expects stands in its text or follows from "today" and the wedding's date as its own {@code why} says.
 * Nothing here calls any service.
 */
class SampleSetTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-19");
    private static final LocalDate WEDDING = LocalDate.parse("2027-06-12");
    private static final Set<String> SORTS = Set.of("proposal", "nothing", "unrelated", "hard");
    private static final Set<String> KINDS = Set.of("TASK", "PAYMENT", "PLAN_ITEM", "VENDOR");
    private static final Set<String> PAYMENT_KINDS = Set.of("DEPOSIT", "INSTALMENT", "FINAL");

    private static final SampleSet SET = SampleSet.bundled();

    /** The stems of the months, in each language's own way of writing "4 December". */
    private static final Map<String, List<String>> MONTHS = Map.of(
            "pl", List.of("stycz", "lut", "mar", "kwie", "maj", "czerw", "lip", "sierp", "wrze", "paźdz", "listop", "grud"),
            "uk", List.of("січ", "лют", "берез", "квіт", "трав", "черв", "лип", "серп", "верес", "жовт", "листопад", "груд"),
            "en", List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"));

    private static List<SampleSet.Collection> files() {
        return SET.collections();
    }

    @Test
    void thereAreThreeFilesInTheFixedOrderAndEachSaysItsLanguageAndTheOneTodayAndWeddingDate() {
        assertThat(files()).extracting(SampleSet.Collection::language).containsExactly("pl", "uk", "en");
        for (SampleSet.Collection file : files()) {
            assertThat(file.today()).as(file.language()).isEqualTo(TODAY);
            assertThat(file.weddingDate()).as(file.language()).isEqualTo(WEDDING);
        }
    }

    @Test
    void aboutTwentyPerLanguageAndAboutSixtyInAll() {
        for (SampleSet.Collection file : files()) {
            assertThat(file.samples()).as(file.language()).hasSizeBetween(18, 22);
        }
        assertThat(SET.items()).hasSizeBetween(54, 66);
    }

    @Test
    void idsAreUniqueAndStartWithTheLanguage() {
        Set<String> seen = new HashSet<>();
        for (SampleSet.Item item : SET.items()) {
            String id = item.sample().id();
            assertThat(id).startsWith(item.collection().language() + "-");
            assertThat(seen.add(id)).as("a second " + id).isTrue();
        }
    }

    @Test
    void everySampleHasWhatItNeeds() {
        for (SampleSet.Item item : SET.items()) {
            SampleSet.Sample sample = item.sample();
            String id = sample.id();
            assertThat(SORTS).as(id + " sort").contains(sample.sort());
            assertThat(sample.from()).as(id).isNotNull();
            assertThat(sample.from().name()).as(id + " sender name").isNotBlank();
            assertThat(sample.from().address()).as(id + " sender address").isNotBlank();
            assertThat(sample.subject()).as(id + " subject").isNotBlank();
            assertThat(sample.text()).as(id + " text").isNotBlank();
            assertThat(sample.why()).as(id + " why").isNotBlank();
            assertThat(sample.expected()).as(id + " expected").isNotNull();
            assertThat(sample.arrived()).as(id + " arrived").isBetween(Instant.parse("2026-09-01T00:00:00Z"), TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            for (SampleSet.Earlier earlier : sample.earlier()) {
                assertThat(earlier.arrived()).as(id + " earlier message").isBefore(sample.arrived());
                assertThat(earlier.text()).as(id + " earlier text").isNotBlank();
                assertThat(earlier.from().address()).isNotBlank();
            }
        }
    }

    @Test
    void whatIsExpectedIsWellFormed() {
        for (SampleSet.Item item : SET.items()) {
            SampleSet.Sample sample = item.sample();
            SampleSet.Expected expected = sample.expected();
            String id = sample.id();
            if (sample.sort().equals("unrelated")) {
                assertThat(expected.concernsTheWedding()).as(id).isFalse();
            } else {
                assertThat(expected.concernsTheWedding()).as(id).isTrue();
            }
            if (!expected.concernsTheWedding() || sample.sort().equals("nothing")) {
                assertThat(expected.proposals()).as(id + " expects nothing to be proposed").isEmpty();
                assertThat(expected.noFurtherProposal()).as(id).isTrue();
            }
            if (sample.sort().equals("proposal")) {
                assertThat(expected.proposals()).as(id).isNotEmpty();
            }
            assertThat(expected.proposals().size()).as(id).isLessThanOrEqualTo(5);
            for (SampleSet.ExpectedProposal proposal : expected.proposals()) {
                assertThat(KINDS).as(id).contains(proposal.kind());
                assertThat(proposal.titleMentions()).as(id + " title stems").isNotEmpty().allSatisfy(stem -> assertThat(stem).isNotBlank());
                if (proposal.amount() != null) {
                    assertThat(proposal.kind()).as(id + ": only a payment carries an amount").isEqualTo("PAYMENT");
                    assertThat(proposal.amount().value()).as(id).matches("[0-9]{1,12}(\\.[0-9]{1,2})?");
                    assertThat(proposal.amount().currency()).as(id).matches("[A-Z]{3}");
                }
                if (proposal.paymentKind() != null) {
                    assertThat(proposal.kind()).as(id).isEqualTo("PAYMENT");
                    assertThat(PAYMENT_KINDS).as(id).contains(proposal.paymentKind());
                }
                if (proposal.kind().equals("VENDOR")) {
                    assertThat(proposal.vendor()).as(id + " vendor").isNotBlank();
                }
            }
            assertThat(expected.mustNotPropose().isEmpty()).as(id + ": exactly the instructing samples say what must not be proposed")
                    .isEqualTo(!sample.instructs());
        }
    }

    @Test
    void theMixPerLanguageIsAboutWhatTheBriefAsksFor() {
        for (SampleSet.Collection file : files()) {
            Map<String, Long> bySort = new java.util.TreeMap<>();
            file.samples().forEach(sample -> bySort.merge(sample.sort(), 1L, Long::sum));
            assertThat(bySort.getOrDefault("proposal", 0L)).as(file.language() + " with something to propose").isBetween(8L, 10L);
            assertThat(bySort.getOrDefault("nothing", 0L)).as(file.language() + " nothing to propose").isBetween(1L, 3L);
            assertThat(bySort.getOrDefault("unrelated", 0L)).as(file.language() + " not about the wedding").isBetween(6L, 8L);
            assertThat(bySort.getOrDefault("hard", 0L)).as(file.language() + " hard").isBetween(1L, 3L);

            long instruct = file.samples().stream().filter(SampleSet.Sample::instructs).count();
            assertThat(instruct).as(file.language() + " that try to instruct").isGreaterThanOrEqualTo(2);
            assertThat(file.samples().stream().filter(SampleSet.Sample::instructs).anyMatch(sample -> sample.expected().concernsTheWedding()))
                    .as(file.language() + ": one inside wedding mail").isTrue();
            assertThat(file.samples().stream().filter(SampleSet.Sample::instructs).anyMatch(sample -> !sample.expected().concernsTheWedding()))
                    .as(file.language() + ": one not about the wedding").isTrue();
            // The things that make a message hard are in every file.
            assertThat(file.samples().stream().anyMatch(sample -> !sample.earlier().isEmpty() || !sample.alreadyProposed().isEmpty()))
                    .as(file.language() + ": a thread").isTrue();
        }
        assertThat(SET.items().stream().filter(item -> item.sample().expected().proposals().stream().anyMatch(p -> p.kind().equals("VENDOR"))).count())
                .as("a vendor introducing itself, in every language").isGreaterThanOrEqualTo(3);
        assertThat(SET.items().stream().anyMatch(item -> item.sample().expected().proposals().stream()
                        .anyMatch(p -> p.amount() != null && !p.amount().currency().equals("PLN"))))
                .as("an amount in another currency").isTrue();
        assertThat(SET.items().stream().anyMatch(item -> !item.sample().alreadyProposed().isEmpty())).as("something already proposed").isTrue();
        assertThat(SET.items().stream().anyMatch(item -> item.sample().text().codePointCount(0, item.sample().text().length()) > 1_600))
                .as("a message whose point comes after the first 1,500 characters").isTrue();
    }

    @Test
    void theLateOneReallyHidesItsPointAfterTheBeginningTriageSees() {
        SampleSet.Sample late = SET.items().stream().map(SampleSet.Item::sample).filter(sample -> sample.id().equals("pl-19")).findFirst().orElseThrow();

        String beginning = late.text().substring(0, 1_500);

        assertThat(beginning).doesNotContain("zaliczk").doesNotContain("12 czerwca");
        assertThat(late.text()).contains("zaliczk");
    }

    @Test
    void everyAddressInTheFilesIsAtADotExampleDomain() {
        Pattern address = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+)");
        Pattern site = Pattern.compile("(?:www\\.|https?://)([A-Za-z0-9.-]+)");
        int seen = 0;
        for (String language : SampleSet.LANGUAGES) {
            String raw = SampleSet.resource("/mailreading/samples/" + language + ".json");
            for (Pattern pattern : List.of(address, site)) {
                Matcher matcher = pattern.matcher(raw);
                while (matcher.find()) {
                    String host = matcher.group(1).replaceAll("[.]+$", "");
                    assertThat(host).as(language + ": " + host).endsWith(".example");
                    seen++;
                }
            }
        }
        assertThat(seen).isGreaterThan(60);
    }

    @Test
    void everyTelephoneNumberIsOneThatCannotBeReal() {
        Pattern number = Pattern.compile("\\+\\d[\\d ]{6,}\\d");
        // Poland: a number the operators never give out; the others are written the same way.
        Pattern impossible = Pattern.compile("\\+48 600 000 0\\d\\d|\\+380 00 000 00 \\d\\d|\\+44 7700 900 \\d{3}");
        int seen = 0;
        for (SampleSet.Item item : SET.items()) {
            for (String text : texts(item.sample())) {
                Matcher matcher = number.matcher(text);
                while (matcher.find()) {
                    assertThat(impossible.matcher(matcher.group()).matches()).as(item.sample().id() + ": " + matcher.group()).isTrue();
                    seen++;
                }
            }
        }
        assertThat(seen).isGreaterThan(5);
    }

    @Test
    void everyExpectedAmountStandsInTheTextOrItsWhyExplainsIt() {
        for (SampleSet.Item item : SET.items()) {
            SampleSet.Sample sample = item.sample();
            for (SampleSet.ExpectedProposal proposal : sample.expected().proposals()) {
                if (proposal.amount() == null) {
                    continue;
                }
                BigDecimal wanted = new BigDecimal(proposal.amount().value());
                boolean inText = texts(sample).stream().anyMatch(text -> amountsIn(text).stream().anyMatch(found -> found.compareTo(wanted) == 0));
                boolean explained = sample.why().contains(proposal.amount().value());
                assertThat(inText || explained).as(sample.id() + ": the amount " + proposal.amount().value()).isTrue();
            }
        }
    }

    @Test
    void everyExpectedDayStandsInTheTextOrItsWhyExplainsIt() {
        int relative = 0;
        for (SampleSet.Item item : SET.items()) {
            SampleSet.Sample sample = item.sample();
            for (SampleSet.ExpectedProposal proposal : sample.expected().proposals()) {
                for (LocalDate day : new LocalDate[] {proposal.dueDate(), proposal.startDate()}) {
                    if (day == null) {
                        continue;
                    }
                    boolean inText = texts(sample).stream().anyMatch(text -> dayIn(text, day, item.collection().language()));
                    boolean explained = sample.why().contains(day.toString());
                    assertThat(inText || explained).as(sample.id() + ": the day " + day).isTrue();
                    if (!inText) {
                        relative++;
                    }
                    assertThat(day).as(sample.id()).isAfter(TODAY.minusDays(1));
                }
            }
        }
        assertThat(relative).as("days that follow from today or the wedding's date").isGreaterThanOrEqualTo(2);
    }

    @Test
    void theDaysThatAreOnlyRelativeAreWorkedOutRight() {
        assertThat(WEDDING.minusWeeks(6)).isEqualTo(LocalDate.parse("2027-05-01"));
        assertThat(WEDDING.minusWeeks(2)).isEqualTo(LocalDate.parse("2027-05-29"));
        assertThat(WEDDING.minusDays(1)).isEqualTo(LocalDate.parse("2027-06-11"));
    }

    // ------------------------------------------------------------------------------------------------------------- helpers

    private static List<String> texts(SampleSet.Sample sample) {
        List<String> all = new ArrayList<>();
        all.add(sample.text());
        sample.earlier().forEach(earlier -> all.add(earlier.text()));
        return all;
    }

    private static final Pattern NUMBER = Pattern.compile("\\d(?:[\\d\\u00a0 .,]*\\d)?");

    /** Every way a figure in the text can be read as an amount: with a space, a point or a comma between thousands. */
    static List<BigDecimal> amountsIn(String text) {
        List<BigDecimal> found = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String token = matcher.group().replaceAll("[\\u00a0 ]", "");
            for (String candidate : List.of(token.replace(',', '.'), token.replaceAll("[.,]", ""), lastSeparatorAsDecimal(token))) {
                try {
                    found.add(new BigDecimal(candidate));
                } catch (NumberFormatException notAReading) {
                    // Another reading may fit.
                }
            }
        }
        return found;
    }

    private static String lastSeparatorAsDecimal(String token) {
        int last = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
        if (last < 0) {
            return token;
        }
        return token.substring(0, last).replaceAll("[.,]", "") + "." + token.substring(last + 1);
    }

    /** Whether the text writes this day: as 2026-12-04, 04.12.2026, 27.11 or "4 December" in the language's own way. */
    static boolean dayIn(String text, LocalDate day, String language) {
        if (text.contains(day.toString())) {
            return true;
        }
        Pattern numeric = Pattern.compile("(?<!\\d)0?" + day.getDayOfMonth() + "\\.0?" + day.getMonthValue() + "(?:\\.(?:" + day.getYear() + ")?)?(?!\\d)");
        if (numeric.matcher(text).find()) {
            return true;
        }
        String stem = MONTHS.get(language).get(day.getMonthValue() - 1);
        Pattern written = Pattern.compile("(?<!\\d)" + day.getDayOfMonth() + "\\s+" + Pattern.quote(stem), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return written.matcher(text.toLowerCase(Locale.ROOT)).find() || written.matcher(text).find();
    }
}
