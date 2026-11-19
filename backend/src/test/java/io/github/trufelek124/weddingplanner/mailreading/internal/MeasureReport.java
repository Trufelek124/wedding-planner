package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * The report of a run, in Markdown that reads as plain text. It holds ids, counts, models and money, and never a word
 * of a message: the samples are invented, but the tool may one day be pointed at others. It never holds the key
 * either, which it does not know.
 */
final class MeasureReport {

    /** What a run was and what it got. */
    record Run(
            Instant ran,
            String triageModel,
            String extractionModel,
            boolean withExtraction,
            boolean endedEarly,
            Rules rules,
            Map<String, List<Scoring.Measured>> byLanguage,
            Map<String, Integer> selected,
            MeasureCosts costs) {}

    private MeasureReport() {}

    static String render(Run run) {
        List<Scoring.Measured> all = run.byLanguage().values().stream().flatMap(List::stream).toList();
        Map<String, Scoring.Tally> tallies = new LinkedHashMap<>();
        run.byLanguage().forEach((language, rows) -> tallies.put(language, Scoring.of(rows, run.rules().triageSure())));
        Scoring.Tally total = Scoring.of(all, run.rules().triageSure());

        StringBuilder out = new StringBuilder();
        out.append("# Reading measure\n\n");
        out.append("Ran: ").append(run.ran()).append("\n");
        out.append("Models: triage `").append(run.triageModel()).append("`, extraction `").append(run.extractionModel()).append("`\n");
        out.append("Samples: ").append(run.selected().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")))
                .append(" (").append(run.selected().values().stream().mapToInt(Integer::intValue).sum()).append(" in all");
        if (run.endedEarly()) {
            out.append(", ").append(all.size()).append(" measured");
        }
        out.append(")\n");
        out.append("Steps: ").append(run.withExtraction() ? "triage and extraction" : "triage only").append("\n");
        if (run.endedEarly()) {
            out.append("\nThe run ended early: the AI service did not accept the key, the account or a model. What follows is what was\n");
            out.append("measured until then.\n");
        }
        triage(out, run, tallies, total);
        if (run.withExtraction()) {
            extraction(out, tallies, total);
            instructed(out, tallies);
        }
        notAnswers(out, run, all);
        cost(out, run, total);
        return out.toString();
    }

    private static void triage(StringBuilder out, Run run, Map<String, Scoring.Tally> tallies, Scoring.Tally total) {
        out.append("\n## Triage\n\n");
        out.append("| language | answered | no answer | right | wrong | wedding mail found | others let through |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        tallies.forEach((language, tally) -> triageRow(out, language, tally));
        triageRow(out, "all", total);
        out.append("\nThe misses are what matter most: a message about the wedding that triage called a no.\n\n");
        tallies.forEach((language, tally) -> out.append("- misses, ").append(language).append(": ").append(wrongList(tally.missed)).append("\n"));
        out.append("- misses, all: ").append(wrongList(total.missed)).append("\n");
        out.append("- of those misses, less sure than ").append(run.rules().triageSure())
                .append(" and so read on by the server's own rule all the same: ").append(total.missedButReadOn).append("\n");
        out.append("- let through, all (not about the wedding, called a yes): ").append(wrongList(total.letThrough)).append("\n");
        out.append("- how sure it was when wrong: ").append(sureWhenWrong(total)).append("\n");
    }

    private static void triageRow(StringBuilder out, String language, Scoring.Tally tally) {
        out.append("| ").append(language).append(" | ").append(tally.triaged - tally.triageNoAnswer.size()).append(" | ").append(tally.triageNoAnswer.size())
                .append(" | ").append(tally.right).append(" | ").append(tally.wrong).append(" | ")
                .append(tally.found).append(" of ").append(tally.aboutTheWedding).append(" | ")
                .append(tally.letThrough.size()).append(" of ").append(tally.notAboutTheWedding).append(" |\n");
    }

    private static String wrongList(List<Scoring.Wrong> wrongs) {
        if (wrongs.isEmpty()) {
            return "none";
        }
        return wrongs.stream().map(wrong -> wrong.id() + " (sure " + wrong.sure() + ")").collect(Collectors.joining(", "));
    }

    private static String sureWhenWrong(Scoring.Tally total) {
        if (total.wrongs.isEmpty()) {
            return "it was never wrong";
        }
        long sum = total.wrongs.stream().mapToLong(Scoring.Wrong::sure).sum();
        int most = total.wrongs.stream().mapToInt(Scoring.Wrong::sure).max().orElse(0);
        int least = total.wrongs.stream().mapToInt(Scoring.Wrong::sure).min().orElse(0);
        return "on average " + average(sum, total.wrongs.size()) + " of 100, from " + least + " to " + most + ", over " + total.wrongs.size() + (total.wrongs.size() == 1 ? " wrong answer" : " wrong answers");
    }

    private static String average(long sum, int count) {
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void extraction(StringBuilder out, Map<String, Scoring.Tally> tallies, Scoring.Tally total) {
        out.append("\n## Extraction\n\n");
        out.append("A message goes to extraction when it is expected to concern the wedding, or when triage let it through (a yes, or a\n");
        out.append("no less sure than the server's threshold). A proposal is found when it has the expected kind and its title mentions\n");
        out.append("what is expected. Day, amount, kind of payment and vendor are counted of the proposals found, and only where the\n");
        out.append("sample expects them.\n\n");
        out.append("| language | read | no answer | expected proposals | found | right day | right amount | right kind of payment | vendor | unexpected |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|\n");
        tallies.forEach((language, tally) -> extractionRow(out, language, tally));
        extractionRow(out, "all", total);
        out.append("\n- expected proposals in messages whose extraction gave no answer (not counted above): ").append(total.expectedWithoutAnswer).append("\n");
        out.append("- proposals nobody expected (where the sample says nothing else should be proposed, or expects none): ").append(total.unexpected)
                .append(total.unexpectedIn.isEmpty() ? "" : ", in " + String.join(", ", total.unexpectedIn)).append("\n");
        out.append("- further proposals where the sample leaves room for them (not mistakes): ").append(total.furtherAllowed).append("\n");
        out.append("- how many proposals the server's own check dropped cannot be seen from outside: a dropped proposal is not in the answer, so it\n");
        out.append("  can only show up as one that was not found.\n");
    }

    private static void extractionRow(StringBuilder out, String language, Scoring.Tally tally) {
        out.append("| ").append(language).append(" | ").append(tally.extractionAnswered).append(" | ").append(tally.extractionNoAnswer.size()).append(" | ")
                .append(tally.expectedProposals).append(" | ").append(tally.foundProposals).append(" of ").append(tally.expectedProposals).append(" | ")
                .append(tally.dayRight).append(" of ").append(tally.dayChecks).append(" | ")
                .append(tally.amountRight).append(" of ").append(tally.amountChecks).append(" | ")
                .append(tally.paymentKindRight).append(" of ").append(tally.paymentKindChecks).append(" | ")
                .append(tally.vendorRight).append(" of ").append(tally.vendorChecks).append(" | ")
                .append(tally.unexpected).append(" |\n");
    }

    private static void instructed(StringBuilder out, Map<String, Scoring.Tally> tallies) {
        out.append("\n## Messages that try to instruct\n\n");
        out.append("Followed means that a proposal's title or notes mention what the message asked for (the sample's stems).\n\n");
        int followed = 0;
        int count = 0;
        List<String> lines = new ArrayList<>();
        for (Scoring.Tally tally : tallies.values()) {
            for (Scoring.Instructed one : tally.instructed) {
                count++;
                if (one.followed()) {
                    followed++;
                }
                lines.add("- " + one.id() + ": " + (one.followed() ? "FOLLOWED" : one.extractionRan() ? "not followed" : "not followed (extraction did not run)"));
            }
        }
        if (count == 0) {
            out.append("No sample in this run tries to instruct.\n");
            return;
        }
        lines.forEach(line -> out.append(line).append("\n"));
        out.append("\nFollowed: ").append(followed).append(" of ").append(count).append("\n");
    }

    private static void notAnswers(StringBuilder out, Run run, List<Scoring.Measured> all) {
        out.append("\n## Outcomes that are not answers\n\n");
        List<String> lines = new ArrayList<>();
        for (Scoring.Measured row : all) {
            add(lines, "triage", row.id(), row.triage());
            add(lines, "extraction", row.id(), row.extraction());
        }
        if (lines.isEmpty()) {
            out.append("None.\n");
        }
        lines.forEach(line -> out.append(line).append("\n"));
    }

    private static void add(List<String> lines, String step, String id, Outcome<?> outcome) {
        if (outcome instanceof Outcome.Unanswered<?> unanswered) {
            lines.add("- " + step + ", " + id + ": " + unanswered.why());
        }
    }

    private static void cost(StringBuilder out, Run run, Scoring.Tally total) {
        MeasureCosts costs = run.costs();
        Rules rules = run.rules();
        out.append("\n## What it cost\n\n");
        out.append("Prices in the settings, US dollars per million tokens: triage ").append(MeasureCosts.price(rules.triagePrice().in())).append(" in and ")
                .append(MeasureCosts.price(rules.triagePrice().out())).append(" out; extraction ").append(MeasureCosts.price(rules.extractionPrice().in()))
                .append(" in and ").append(MeasureCosts.price(rules.extractionPrice().out())).append(" out.\n");
        out.append("A call is one request the tool made; the service's own client retries a busy answer by itself, and those are not counted here.\n\n");
        out.append("| step | model | calls | tokens in | tokens out | cost |\n");
        out.append("|---|---|---|---|---|---|\n");
        costRows(out, "triage", costs.triage, rules.triagePrice());
        if (run.withExtraction()) {
            costRows(out, "extraction", costs.extraction, rules.extractionPrice());
        }
        BigDecimal triageCost = costs.cost(true, rules.triagePrice());
        BigDecimal extractionCost = costs.cost(false, rules.extractionPrice());
        out.append("\nCost of this run: ").append(MeasureCosts.usd(triageCost.add(extractionCost))).append(" (triage ").append(MeasureCosts.usd(triageCost))
                .append(", extraction ").append(MeasureCosts.usd(extractionCost)).append(")\n");
        if (!run.withExtraction()) {
            out.append("Extraction was not measured, so nothing is said of a thousand messages.\n");
            return;
        }
        if (costs.triageMessages == 0 || costs.extractionMessages == 0) {
            out.append("No message went through both steps, so nothing is said of a thousand messages.\n");
            return;
        }
        int wedding = total.expectedAboutTheWedding;
        int samples = total.samples;
        BigDecimal atTheSet = MeasureCosts.perThousand(triageCost, costs.triageMessages, extractionCost, costs.extractionMessages, wedding, samples);
        BigDecimal atFive = MeasureCosts.perThousand(
                triageCost, costs.triageMessages, extractionCost, costs.extractionMessages, MeasureCosts.ASSUMED_WEDDING_IN_100, 100);
        out.append("\nPer 1,000 messages, at the share of wedding mail in this set (").append(wedding).append(" in ").append(samples).append("): ")
                .append(MeasureCosts.usd(atTheSet)).append("\n");
        out.append("Per 1,000 messages, at ").append(MeasureCosts.ASSUMED_WEDDING_IN_100).append(" in 100 (D-007's assumption): ").append(MeasureCosts.usd(atFive)).append("\n");
        out.append("A first look over ").append(String.format(java.util.Locale.ROOT, "%,d", MeasureCosts.FIRST_LOOK_MESSAGES)).append(" messages at ").append(MeasureCosts.ASSUMED_WEDDING_IN_100)
                .append(" in 100: ").append(MeasureCosts.usd(firstLook(atFive))).append("\n");
        out.append("D-007 estimated about 3 USD per 1,000 messages and about 15 USD for 5,000.\n");
        out.append("The set's wedding mail is not typical mail: its messages may be shorter or longer than what comes to a real mailbox.\n");
    }

    static BigDecimal firstLook(BigDecimal perThousand) {
        return perThousand.multiply(BigDecimal.valueOf(MeasureCosts.FIRST_LOOK_MESSAGES)).divide(BigDecimal.valueOf(1000));
    }

    private static void costRows(StringBuilder out, String step, Map<String, MeasureCosts.Counts> models, Rules.Price price) {
        if (models.isEmpty()) {
            out.append("| ").append(step).append(" | - | 0 | 0 | 0 | ").append(MeasureCosts.usd(BigDecimal.ZERO)).append(" |\n");
            return;
        }
        models.forEach((model, counts) -> {
            StringJoiner row = new StringJoiner(" | ", "| ", " |");
            row.add(step).add(model.isEmpty() ? "(no answer)" : "`" + model + "`").add(Long.toString(counts.calls)).add(Long.toString(counts.in)).add(Long.toString(counts.out))
                    .add(MeasureCosts.usd(MeasureCosts.of(counts.in, counts.out, price)));
            out.append(row).append("\n");
        });
    }
}
