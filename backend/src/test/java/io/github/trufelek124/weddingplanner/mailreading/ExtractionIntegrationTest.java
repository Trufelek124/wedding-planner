package io.github.trufelek124.weddingplanner.mailreading;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Amount;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.EarlierMessage;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Extracted;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Kind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.PaymentKind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Proposed;
import io.github.trufelek124.weddingplanner.mailreading.Outcome.Answered;
import io.github.trufelek124.weddingplanner.mailreading.Outcome.Unanswered;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Call;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Step two against the stand-in: what is asked, and what comes back and is checked. */
class ExtractionIntegrationTest extends ReadingTestBase {

    private static final String LETTER = "Dear Anna and Piotr,\nthe deposit of 2500.50 PLN for the hall is due by 2026-12-01.\n"
            + "Please send us the final guest list.\nBest wishes, Jan";
    private static final String DEPOSIT = "the deposit of 2500.50 PLN for the hall is due by 2026-12-01.";
    private static final String GUESTS = "Please send us the final guest list.";

    private static final List<String> NEVER_SENT = List.of("tools", "tool_choice", "thinking", "temperature", "top_p", "top_k");

    private Outcome<Extracted> answered(String answer) {
        service.reset();
        service.answerNextCallsWith(Reply.says(answer));
        return extraction.extract(extractionInput(LETTER));
    }

    private static List<Proposed> proposals(Outcome<Extracted> outcome) {
        assertThat(outcome).isInstanceOf(Answered.class);
        return ((Answered<Extracted>) outcome).answer().proposals();
    }

    @Test
    void asksTheLargeModelForAShapedAnswerAtMediumEffortWithTheServicesOwnFallbackAndNothingElse() throws IOException {
        service.answerNextCallsWith(Reply.says(proposals()));

        extraction.extract(extractionInput(LETTER));

        Call call = service.onlyCall();
        assertThat(call.path()).isEqualTo("/v1/messages");
        // The service takes the fallback as a beta of the one messages call: it is the header that says so.
        assertThat(call.query()).isNull();
        assertThat(call.json()).containsEntry("model", "claude-opus-5-5").containsEntry("max_tokens", 16_000);
        assertThat(call.json().keySet()).doesNotContainAnyElementsOf(NEVER_SENT);
        assertThat(call.json().keySet()).isSubsetOf("model", "max_tokens", "system", "messages", "output_config", "fallbacks");
        assertThat(call.json()).containsEntry("fallbacks", "default");
        assertThat(call.headers().get("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        Map<String, Object> output = call.map("output_config");
        assertThat(output).containsEntry("effort", "medium");
        assertThat(output.get("format").toString()).contains("json_schema").contains("proposals").contains("passage").contains("amount");
        assertThat(call.messages()).hasSize(1);
        assertThat(call.messages().get(0)).containsEntry("role", "user");
        assertThat(call.system()).isEqualTo(new String(getClass().getResourceAsStream("/mailreading/extraction-instructions.txt").readAllBytes(), StandardCharsets.UTF_8).strip());
        assertThat(call.headers()).containsEntry("authorization", "Bearer " + StandInAnthropic.KEY);
        assertThat(call.body()).doesNotContain(StandInAnthropic.KEY);
    }

    private static String proposals() {
        return "{\"proposals\":[]}";
    }

    @Test
    void sendsTheFullTextTheThreadOldestFirstBothDatesAndTheTitlesAlreadyProposed() {
        String longText = "Start. " + "Lorem ipsum dolor sit amet. ".repeat(1_000) + "THE-END-OF-A-LONG-TEXT";
        List<EarlierMessage> earlier = List.of(
                new EarlierMessage(jan(), Instant.parse("2026-11-10T08:00:00Z"), "FIRST-EARLIER we met at the hall."),
                new EarlierMessage(new Sender("Anna", "anna@planner.example"), Instant.parse("2026-11-12T08:00:00Z"), "SECOND-EARLIER we would like 12 June."));

        extraction.extract(extractionInput(longText, earlier, WEDDING, List.of("Send the guest list", "Pay the deposit")));

        String turn = service.onlyCall().userTurn();
        assertThat(turn).contains(longText).contains("Today: 2026-11-19").contains("The wedding's date: 2027-06-12");
        assertThat(turn.indexOf("FIRST-EARLIER")).isPositive().isLessThan(turn.indexOf("SECOND-EARLIER"));
        assertThat(turn.indexOf("SECOND-EARLIER")).isLessThan(turn.indexOf("THE-END-OF-A-LONG-TEXT"));
        assertThat(turn).contains("Send the guest list\nPay the deposit\n");
        assertThat(turn).contains("Jan from Palace on the Hill <jan@palace-on-the-hill.example>").contains("Your booking for 12 June")
                .contains("2026-11-18T09:30:00Z").contains("2026-11-12T08:00:00Z");
        assertThat(turn).doesNotContain("was cut");
    }

    @Test
    void saysThatNoWeddingDateIsSet() {
        extraction.extract(extractionInput(LETTER, List.of(), null, List.of()));

        assertThat(service.onlyCall().userTurn()).contains("The wedding's date: not set yet");
    }

    @Test
    void cutsTheThreadAt60000CharactersLeavingOutTheOldestMessagesAndSaysSo() {
        String message = "M".repeat(10_000);
        List<EarlierMessage> earlier = new ArrayList<>();
        earlier.add(new EarlierMessage(jan(), Instant.parse("2026-10-01T08:00:00Z"), "OLDEST " + "o".repeat(20_000)));
        earlier.add(new EarlierMessage(jan(), Instant.parse("2026-10-02T08:00:00Z"), "MIDDLE " + "m".repeat(30_000)));
        earlier.add(new EarlierMessage(jan(), Instant.parse("2026-10-03T08:00:00Z"), "NEWEST " + "n".repeat(15_000)));

        extraction.extract(extractionInput(message, earlier, WEDDING, List.of()));

        String turn = service.onlyCall().userTurn();
        // 10,000 + 15,007 + 30,007 = 55,014 fit; the oldest, 20,007 more, does not.
        assertThat(turn).contains("MIDDLE").contains("NEWEST").doesNotContain("OLDEST");
        assertThat(turn).contains("The thread was cut: the 1 oldest earlier message(s) were left out");
        assertThat(turn.replaceAll("[^mMno]", "").length()).isLessThanOrEqualTo(60_000);
    }

    @Test
    void aMessageThatFillsTheRoomAloneSendsNoThreadButIsSentWhole() {
        String message = "X".repeat(61_000);
        List<EarlierMessage> earlier = List.of(new EarlierMessage(jan(), Instant.parse("2026-10-01T08:00:00Z"), "AN-EARLIER-ONE"));

        extraction.extract(extractionInput(message, earlier, WEDDING, List.of()));

        String turn = service.onlyCall().userTurn();
        assertThat(turn).contains(message).doesNotContain("AN-EARLIER-ONE").contains("The thread was cut: the 1 oldest");
    }

    @Test
    void proposalsOfEveryKindWithEveryFieldComeBackChecked() {
        String task = with(with(proposal("TASK", "Send the final guest list", GUESTS), "firstDay", "\"2026-11-20\""), "dueDay", "\"2026-12-15\"");
        String payment = with(with(with(with(proposal("PAYMENT", "Pay the deposit for the hall", DEPOSIT), "amount", "\"2500.50\""), "currency", "\"pln\""),
                "paymentKind", "\"DEPOSIT\""), "dueDay", "\"2026-12-01\"");
        String step = with(proposal("PLAN_ITEM", "Visit the hall", "Dear Anna and Piotr,"), "firstDay", "\"2026-12-05\"");
        String vendor = with(with(with(with(with(proposal("VENDOR", "Palace on the Hill", "Best wishes, Jan"), "vendorName", "\"Palace on the Hill\""),
                "vendorEmail", "\"jan@palace-on-the-hill.example\""), "vendorPhone", "\"+48 600 000 000\""), "vendorWebsite", "\"https://palace-on-the-hill.example\""),
                "category", "\"Venue\"");

        List<Proposed> found = proposals(answered(proposals(task, payment, step, vendor)));

        assertThat(found).hasSize(4);
        assertThat(found.get(0)).isEqualTo(new Proposed(Kind.TASK, "Send the final guest list", "", LocalDate.parse("2026-11-20"), LocalDate.parse("2026-12-15"),
                null, null, null, null, null, null, null, 80, GUESTS));
        assertThat(found.get(1)).isEqualTo(new Proposed(Kind.PAYMENT, "Pay the deposit for the hall", "", null, LocalDate.parse("2026-12-01"),
                new Amount("2500.50", "PLN"), PaymentKind.DEPOSIT, null, null, null, null, null, 80, DEPOSIT));
        assertThat(found.get(2).kind()).isEqualTo(Kind.PLAN_ITEM);
        assertThat(found.get(3)).isEqualTo(new Proposed(Kind.VENDOR, "Palace on the Hill", "", null, null, null, null, "Venue", "Palace on the Hill",
                "jan@palace-on-the-hill.example", "+48 600 000 000", "https://palace-on-the-hill.example", 80, "Best wishes, Jan"));
    }

    @Test
    void whatIsReportedUsedIsTheModelsAndTheTokens() {
        service.answerNextCallsWith(Reply.says(proposals(), "claude-opus-5-5", 6_000, 1_500));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(LETTER));

        assertThat(outcome).isEqualTo(new Answered<>(new Extracted(List.of()), new Used("claude-opus-5-5", 6_000, 1_500)));
    }

    @Test
    void whenTheServiceAnswersWithAnotherModelItIsTheOneReported() {
        service.answerNextCallsWith(Reply.says(proposals(proposal("TASK", "Send the final guest list", GUESTS)), "claude-opus-4-8", 6_100, 900));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(LETTER));

        assertThat(outcome.used()).isEqualTo(new Used("claude-opus-4-8", 6_100, 900));
        assertThat(proposals(outcome)).hasSize(1);
    }

    @Test
    void aSwitchPointOfTheFallbackInTheAnswerIsNotInTheWay() {
        String switched = "[{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5-5\"},\"to\":{\"model\":\"claude-opus-4-8\"}},"
                + "{\"type\":\"text\",\"text\":" + quoted(proposals(proposal("TASK", "Send the final guest list", GUESTS))) + "}]";
        service.answerNextCallsWith(Reply.withContent("end_turn", switched));

        assertThat(proposals(extraction.extract(extractionInput(LETTER)))).hasSize(1);
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void aMessageThatAsksForNothingProposesNothing() {
        Outcome<Extracted> outcome = answered(proposals());

        assertThat(outcome).isInstanceOf(Answered.class);
        assertThat(proposals(outcome)).isEmpty();
    }

    @Test
    void aProposalThatFailsIsDroppedAndTheOthersStay() {
        String good = proposal("TASK", "Send the final guest list", GUESTS);
        List<String> bad = List.of(
                proposal("MEETING", "A wrong kind", GUESTS),
                proposal("task", "A kind in small letters", GUESTS),
                with(proposal("TASK", "An unreal day", GUESTS), "dueDay", "\"2026-02-30\""),
                with(proposal("TASK", "A day that is not one", GUESTS), "firstDay", "\"next week\""),
                with(with(proposal("PAYMENT", "A negative amount", DEPOSIT), "amount", "\"-5\""), "currency", "\"PLN\""),
                with(with(proposal("PAYMENT", "A comma", DEPOSIT), "amount", "\"2500,50\""), "currency", "\"PLN\""),
                with(with(proposal("PAYMENT", "Three decimals", DEPOSIT), "amount", "\"1.234\""), "currency", "\"PLN\""),
                with(with(proposal("PAYMENT", "Words", DEPOSIT), "amount", "\"about 100\""), "currency", "\"PLN\""),
                with(with(proposal("PAYMENT", "An exponent", DEPOSIT), "amount", "\"1e3\""), "currency", "\"PLN\""),
                with(with(proposal("PAYMENT", "A currency of two", DEPOSIT), "amount", "\"100\""), "currency", "\"PL\""),
                with(with(proposal("PAYMENT", "An unknown payment kind", DEPOSIT), "amount", "\"100\""), "paymentKind", "\"ADVANCE\""),
                with(proposal("TASK", "Too sure", GUESTS), "howSure", "101"),
                with(proposal("TASK", "Not sure at all", GUESTS), "howSure", "-1"),
                proposal("TASK", "", GUESTS),
                proposal("TASK", "T".repeat(201), GUESTS),
                proposal("VENDOR", "V".repeat(121), GUESTS),
                proposal("TASK", "A passage that is not in the message", "Pay everything today."),
                proposal("TASK", "An empty passage", ""),
                proposal("TASK", "A passage of spaces", "   "));

        for (String broken : bad) {
            List<Proposed> found = proposals(answered(proposals(broken, good)));
            assertThat(found).as("with " + broken.substring(0, 60)).hasSize(1);
            assertThat(found.get(0).title()).isEqualTo("Send the final guest list");
        }
    }

    @Test
    void aPassageMustStandInWhatWasSentComparedWithoutRegardToLineBreaksAndSpaces() {
        String spaced = proposal("TASK", "Pay the deposit", "the   deposit of 2500.50 PLN\\nfor the hall\\tis due by  2026-12-01.");
        String invented = proposal("TASK", "Pay the deposit", "the deposit of 3000.50 PLN for the hall is due by 2026-12-01.");

        List<Proposed> found = proposals(answered(proposals(spaced, invented)));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).passage()).contains("2500.50");
    }

    @Test
    void aPassageInPolishAndUkrainianIsFoundWhateverFormTheAccentsAreWrittenIn() {
        String polish = "Zaliczka w wysokości 3000 zł za salę jest płatna do 1 grudnia.";
        String ukrainian = "Будь ласка, надішліть остаточний список гостей до п'ятниці.";
        String decomposedPolish = java.text.Normalizer.normalize("zaliczka w wysokości 3000 zł", java.text.Normalizer.Form.NFD);
        service.answerNextCallsWith(Reply.says(proposals(
                proposal("PAYMENT", "Zapłać zaliczkę", "Zaliczka w wysokości 3000 zł za salę"),
                proposal("TASK", "Надіслати список гостей", "надішліть остаточний список гостей"),
                proposal("TASK", "Zaliczka w formie rozłożonej", decomposedPolish.replace("zaliczka", "Zaliczka")))));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(polish + "\n" + ukrainian));

        assertThat(proposals(outcome)).extracting(Proposed::title).containsExactly("Zapłać zaliczkę", "Надіслати список гостей", "Zaliczka w formie rozłożonej");
    }

    @Test
    void aPassageThatIsOnlyInAnEarlierMessageOfTheThreadIsFoundThereWhenItWasSent() {
        List<EarlierMessage> earlier = List.of(new EarlierMessage(jan(), Instant.parse("2026-11-10T08:00:00Z"), "The quartet costs 4000 EUR."));
        service.answerNextCallsWith(Reply.says(proposals(proposal("TASK", "Decide on the quartet", "The quartet costs 4000 EUR."))));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(LETTER, earlier, WEDDING, List.of()));

        assertThat(proposals(outcome)).hasSize(1);
    }

    @Test
    void aPassageThatIsOnlyInAMessageThatWasLeftOutIsNotFound() {
        List<EarlierMessage> earlier = List.of(new EarlierMessage(jan(), Instant.parse("2026-11-10T08:00:00Z"), "The quartet costs 4000 EUR. " + "o".repeat(30_000)));
        service.answerNextCallsWith(Reply.says(proposals(proposal("TASK", "Decide on the quartet", "The quartet costs 4000 EUR."))));

        Outcome<Extracted> outcome = extraction.extract(extractionInput("m".repeat(40_000), earlier, WEDDING, List.of()));

        assertThat(proposals(outcome)).isEmpty();
    }

    @Test
    void sixProposalsCannotBeUsedAtAll() {
        String one = proposal("TASK", "Send the final guest list", GUESTS);

        Outcome<Extracted> six = answered(proposals(one, one, one, one, one, one));
        Outcome<Extracted> five = answered(proposals(one, one, one, one, one));

        assertThat(six).isEqualTo(new Unanswered<Extracted>(Outcome.Why.TOO_MANY_PROPOSALS, new Used("claude-opus-5-5", 120, 40)));
        assertThat(proposals(five)).hasSize(5);
    }

    @Test
    void anAmountWithNoCurrencyIsLetThroughWithAnEmptyOneButAWrongCurrencyDropsTheProposal() {
        String named = with(proposal("PAYMENT", "Pay the deposit", DEPOSIT), "amount", "\"100\"");

        Extracted answer = ((Answered<Extracted>) answered(proposals(named))).answer();

        assertThat(answer.proposals()).hasSize(1);
        assertThat(answer.proposals().get(0).amount()).isEqualTo(new MailExtraction.Amount("100", ""));
    }

    @Test
    void sixProposalsCannotBeUsedEvenWhenFiveOfThemWouldHaveBeenDropped() {
        String one = proposal("TASK", "Send the final guest list", GUESTS);
        String bad = proposal("MEETING", "Nothing", GUESTS);

        assertThat(((Unanswered<Extracted>) answered(proposals(one, bad, bad, bad, bad, bad))).why()).isEqualTo(Outcome.Why.TOO_MANY_PROPOSALS);
    }

    @Test
    void aRefusalIsDeclinedAndNotAnAnswer() {
        service.answerNextCallsWith(Reply.refusal());

        Outcome<Extracted> outcome = extraction.extract(extractionInput(LETTER));

        assertThat(outcome).isEqualTo(new Unanswered<Extracted>(Outcome.Why.DECLINED, new Used("claude-opus-5-5", 77, 0)));
    }

    @Test
    void aRefusalIsDeclinedEvenWhenItCarriesProposals() {
        service.answerNextCallsWith(Reply.message("refusal", proposals(proposal("TASK", "Send the final guest list", GUESTS)), 80, 10, "%MODEL%"));

        assertThat(((Unanswered<Extracted>) extraction.extract(extractionInput(LETTER))).why()).isEqualTo(Outcome.Why.DECLINED);
    }

    @Test
    void anAnswerCutOffByTheLimitCannotBeUsedEvenWhenItLooksWhole() {
        service.answerNextCallsWith(Reply.message("max_tokens", proposals(proposal("TASK", "Send the final guest list", GUESTS)), 6_000, 16_000, "%MODEL%"));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(LETTER));

        assertThat(outcome).isEqualTo(new Unanswered<Extracted>(Outcome.Why.CANNOT_BE_USED, new Used("claude-opus-5-5", 6_000, 16_000)));
    }

    @Test
    void anAnswerThatIsNotTheShapeCannotBeUsed() {
        for (String text : List.of("Sure! I propose to send the guest list.", "{\"proposals\":\"none\"}", "{\"proposals\":null}", "{}", "[]", "", "{\"proposals\":[null]}",
                "{\"proposals\":[{\"kind\":\"TASK\"}]}x")) {
            Outcome<Extracted> outcome = answered(text);

            // A proposal that is missing parts is a proposal that is dropped; the answer as a whole is unusable when it is no list at all.
            if (outcome instanceof Answered<Extracted> kept) {
                assertThat(kept.answer().proposals()).as("answer: " + text).isEmpty();
            } else {
                assertThat(((Unanswered<Extracted>) outcome).why()).as("answer: " + text).isEqualTo(Outcome.Why.CANNOT_BE_USED);
            }
        }
        assertThat(answered("Sure! I propose to send the guest list.")).isInstanceOf(Unanswered.class);
        assertThat(answered("{\"proposals\":null}")).isInstanceOf(Unanswered.class);
        assertThat(answered("{\"proposals\":\"none\"}")).isInstanceOf(Unanswered.class);
        assertThat(answered("{}")).isInstanceOf(Unanswered.class);
    }

    @Test
    void textsAreMadePlainControlCharactersGoAndLengthsAreHeld() {
        String messy = with(with(proposal("TASK", "Send\\u0007 the\\nfinal\\u202Eguest list", GUESTS), "notes", "\"line one\\r\\nline two\\u0000\\ttabbed\""), "category", "\"Ven\\u200Bue\"");

        Proposed found = proposals(answered(proposals(messy))).get(0);

        assertThat(found.title()).isEqualTo("Send the finalguest list");
        assertThat(found.notes()).isEqualTo("line one\nline two tabbed");
        assertThat(found.category()).isEqualTo("Venue");
        assertThat(found.title() + found.notes() + found.category()).doesNotContainPattern("[\\p{Cc}\\p{Cf}&&[^\\n]]");
    }

    // ---------------------------------------------------------------- earlier messages that are read now (D-012)

    private static EarlierMessage readNow(String text, String arrived) {
        return new EarlierMessage(jan(), Instant.parse(arrived), text, true);
    }

    @Test
    void everyEarlierMessageSaysOnALineOfItsOwnWhetherItWasReadBeforeOrIsReadNowAndThePlannerSaysSoOnceInItsOwnFacts() {
        List<EarlierMessage> earlier = List.of(
                new EarlierMessage(jan(), Instant.parse("2026-11-09T08:00:00Z"), "BACKGROUND-ONE we met."),
                readNow("NOW-TWO the deposit is 600 PLN.", "2026-11-10T08:00:00Z"),
                readNow("NOW-THREE the deposit is 700 PLN.", "2026-11-11T08:00:00Z"));

        extraction.extract(extractionInput(LETTER, earlier, WEDDING, List.of()));

        String turn = service.onlyCall().userTurn();
        assertThat(turn).contains("Earlier message 1\nRead before: yes\nFrom: ").contains("Earlier message 2\nRead before: no - it is read now, with the message\nFrom: ")
                .contains("Earlier message 3\nRead before: no - it is read now, with the message\nFrom: ");
        String fact = "Earlier messages that are marked as not read before are read now, with the message: the conversation is read as a whole, as it stands after its last message.";
        assertThat(turn).containsOnlyOnce(fact).contains("At most 8 proposals in all, for this request.");
        assertThat(turn.indexOf(fact)).as("among the planner's facts, before any marked part").isLessThan(turn.indexOf("----- BOUNDARY-"));
    }

    @Test
    void withNoEarlierMessageReadNowTheRequestHasNoSuchFactAndAllowsFive() {
        extraction.extract(extractionInput(LETTER, List.of(new EarlierMessage(jan(), Instant.parse("2026-11-09T08:00:00Z"), "BACKGROUND-ONE")), WEDDING, List.of()));

        String turn = service.onlyCall().userTurn();
        assertThat(turn).contains("Read before: yes").doesNotContain("it is read now").doesNotContain("read as a whole").contains("At most 5 proposals in all");
    }

    @Test
    void theFactIsLeftOutWhenTheOnesReadNowDidNotFitAndTheAnswerSaysHowManyWereLeftOut() {
        String message = "M".repeat(10_000);
        List<EarlierMessage> earlier = new ArrayList<>();
        earlier.add(readNow("OLDEST " + "o".repeat(40_000), "2026-10-01T08:00:00Z"));
        earlier.add(readNow("MIDDLE " + "m".repeat(40_000), "2026-10-02T08:00:00Z"));
        earlier.add(new EarlierMessage(jan(), Instant.parse("2026-10-03T08:00:00Z"), "NEWEST " + "n".repeat(15_000)));
        service.answerNextCallsWith(Reply.says(proposals()));

        Outcome<Extracted> outcome = extraction.extract(extractionInput(message, earlier, WEDDING, List.of()));

        String turn = service.onlyCall().userTurn();
        assertThat(turn).contains("NEWEST").doesNotContain("OLDEST").doesNotContain("MIDDLE").doesNotContain("it is read now");
        assertThat(turn).as("nothing that is read now was sent: no such fact").doesNotContain("read as a whole");
        assertThat(((Answered<Extracted>) outcome).answer().earlierLeftOut()).isEqualTo(2);
    }

    @Test
    void earlierLeftOutIsZeroWhenEverythingFitsAndCountsTheOldestWhenNot() {
        service.answerNextCallsWith(Reply.says(proposals()));
        Outcome<Extracted> whole = extraction.extract(extractionInput(LETTER, List.of(readNow("ONE", "2026-11-09T08:00:00Z")), WEDDING, List.of()));
        assertThat(((Answered<Extracted>) whole).answer().earlierLeftOut()).isZero();

        service.reset();
        service.answerNextCallsWith(Reply.says(proposals()));
        List<EarlierMessage> earlier = List.of(readNow("A" + "a".repeat(30_000), "2026-10-01T08:00:00Z"), readNow("B" + "b".repeat(30_000), "2026-10-02T08:00:00Z"));
        Outcome<Extracted> cut = extraction.extract(extractionInput("M".repeat(10_000), earlier, WEDDING, List.of()));
        assertThat(((Answered<Extracted>) cut).answer().earlierLeftOut()).isEqualTo(1);
    }

    @Test
    void theMostProposalsIsEightWithDocumentsOrWithAnEarlierMessageReadNowAndFiveOtherwise() {
        List<EarlierMessage> background = List.of(new EarlierMessage(jan(), Instant.parse("2026-11-09T08:00:00Z"), "BACKGROUND"));
        List<EarlierMessage> now = List.of(readNow("NOW", "2026-11-09T08:00:00Z"));
        MailExtraction.Document document = new MailExtraction.Document("offer.pdf", "Words", false);

        assertThat(extractionInput(LETTER, background, WEDDING, List.of()).mostProposals()).isEqualTo(5);
        assertThat(extractionInput(LETTER, now, WEDDING, List.of()).mostProposals()).isEqualTo(8);
        assertThat(new MailExtraction.Input(jan(), "S", ARRIVED, LETTER, background, TODAY, WEDDING, List.of(), List.of(document)).mostProposals()).isEqualTo(8);
    }

    @Test
    void eightProposalsAreAnAnswerForAThreadReadNowAndNineAreTooMany() {
        String one = proposal("TASK", "Send the final guest list", GUESTS);
        List<EarlierMessage> now = List.of(readNow("NOW", "2026-11-09T08:00:00Z"));
        service.answerNextCallsWith(Reply.says(proposals(one, one, one, one, one, one, one, one)), Reply.says(proposals(one, one, one, one, one, one, one, one, one)),
                Reply.says(proposals(one, one, one, one, one, one)));

        Outcome<Extracted> eight = extraction.extract(extractionInput(LETTER, now, WEDDING, List.of()));
        Outcome<Extracted> nine = extraction.extract(extractionInput(LETTER, now, WEDDING, List.of()));
        Outcome<Extracted> sixWithoutReadNow = extraction.extract(extractionInput(LETTER, List.of(), WEDDING, List.of()));

        assertThat(((Answered<Extracted>) eight).answer().proposals()).hasSize(8);
        assertThat(((Unanswered<Extracted>) nine).why()).isEqualTo(Outcome.Why.TOO_MANY_PROPOSALS);
        assertThat(((Unanswered<Extracted>) sixWithoutReadNow).why()).isEqualTo(Outcome.Why.TOO_MANY_PROPOSALS);
    }

    @Test
    void theInstructionsHaveTheParagraphOnEarlierMessagesReadNowAndKeepTheirWidth() throws IOException {
        String instructions = new String(getClass().getResourceAsStream("/mailreading/extraction-instructions.txt").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(instructions).contains("The request may mark earlier messages of the thread as not read before: they are read now, with the message.\n"
                + "Then propose what the conversation as a whole asks of the two of them, as it stands after its last message.\n"
                + "What a later message changed is proposed as it stands last. What a later message settled, answered or took\n"
                + "back is not proposed. Earlier messages that were read before are background only: what they asked was\n"
                + "proposed when they were read. When earlier messages are read now, at most EIGHT proposals are allowed\n"
                + "instead of five.\n");
        assertThat(instructions.lines().mapToInt(String::length).max().orElseThrow()).isLessThanOrEqualTo(120);
        assertThat(instructions.indexOf("When the request holds documents")).isLessThan(instructions.indexOf("The request may mark earlier messages"));
    }
}
