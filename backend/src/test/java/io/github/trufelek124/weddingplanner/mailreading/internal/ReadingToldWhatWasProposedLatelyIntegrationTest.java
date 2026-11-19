package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Call;
import io.github.trufelek124.weddingplanner.suggestions.Proposal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The extraction is told what was proposed lately (S04 sections 3 and 6), not only from this thread: first what came from
 * this thread, then what came from any mail of the wedding in the last thirty days, at most thirty, one line each, nothing
 * twice and never the message's own. Made-up mail, some of it in Polish and Ukrainian.
 */
class ReadingToldWhatWasProposedLatelyIntegrationTest extends RoundTestBase {

    static final String TEXT = "Dzień dobry, potwierdzamy termin degustacji. Prosimy o wybór menu do końca tygodnia. Pozdrawiamy.";
    static final String PASSAGE = "Prosimy o wybór menu do końca tygodnia.";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    /** What the extraction was last asked, as the model was shown it. */
    private String lastTurn() {
        List<Call> extractions = service.calls().stream().filter(call -> "claude-opus-5-5".equals(call.json().get("model"))).toList();
        return extractions.get(extractions.size() - 1).userTurn();
    }

    private String alreadyProposedPart(String turn) {
        int begin = turn.indexOf("begins: already proposed");
        int end = turn.indexOf("ends: already proposed");
        assertThat(begin).as("the part is there").isPositive();
        assertThat(end).isGreaterThan(begin);
        return turn.substring(begin, end);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            n++;
        }
        return n;
    }

    private void readOne(String... proposals) {
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposals));
        assertThat(round.round()).isEqualTo(1);
    }

    private UUID handIn(String key, Proposal.Kind kind, String title, LocalDate start, LocalDate due, Long amount) {
        return suggestions.handIn(new Proposal(weddingId(), key, kind, title, "", start, due, amount, null, null, null, null, null, null, null,
                "Jan from Palace on the Hill", "jan@palace-on-the-hill.example", "Earlier", Instant.parse("2026-11-12T09:30:00Z"), anna(),
                "https://mail.google.com/mail/#all/18c0ffee00000001", "We could meet on Friday.", 80));
    }

    // ---------------------------------------------------------------- what is told, and how

    @Test
    void whatWasProposedFromAnyMailReachesALaterMessageOneLineEachWithKindTitleDayAndAmountAsPeopleWriteIt() {
        annaGets("m-1", "thread-hall", 3, "Sala", TEXT);
        note();
        String deposit = with(with(with(with(proposal("PAYMENT", "Wpłacić zaliczkę za salę", PASSAGE), "dueDay", "2026-12-01"),
                "amount", "5000"), "currency", "PLN"), "paymentKind", "DEPOSIT");
        String tasting = with(proposal("PLAN_ITEM", "Degustacja menu", PASSAGE), "firstDay", "2026-12-12");
        String cents = with(with(with(proposal("PAYMENT", "Opłata za rezerwację", PASSAGE), "amount", "0.07"), "currency", "PLN"), "dueDay", "2026-12-03");
        String task = proposal("TASK", "Надіслати список гостей", PASSAGE);
        readOne(deposit, tasting, cents, task);

        annaGets("m-2", "thread-flowers", 1, "Kwiaty", TEXT + " Kwiaty.");
        note();
        readOne();

        String told = alreadyProposedPart(lastTurn());
        assertThat(told).contains("PAYMENT | Wpłacić zaliczkę za salę | due 2026-12-01 | 5000.00 PLN\n");
        assertThat(told).contains("PLAN_ITEM | Degustacja menu | starts 2026-12-12\n");
        assertThat(told).contains("PAYMENT | Opłata za rezerwację | due 2026-12-03 | 0.07 PLN\n");
        assertThat(told).contains("TASK | Надіслати список гостей\n");
        assertThat(told).doesNotContain("from this thread, one title per line").contains("lately from other mail");
    }

    @Test
    void theThreadsComeFirstThenTheWeddingsNothingTwiceAndTheMessagesOwnAreLeftOut() {
        annaGets("m-menu-1", "thread-menu", 5, "Degustacja", TEXT);
        annaGets("m-flowers", "thread-flowers", 4, "Kwiaty", TEXT);
        note();
        service.answerNextCallsWith(
                triageSays(true, 90), extractionSays(proposal("TASK", "Flowers-Title-Qz", PASSAGE)),
                triageSays(true, 90), extractionSays(proposal("TASK", "Menu-Title-Qz", PASSAGE)));
        assertThat(round.round()).isEqualTo(2);
        TestClockAdvance.minutes(10);
        // A later message of the menu's thread; its first try proposes Own-Title-Qz and the server "stops" before marking.
        annaGets("m-menu-2", "thread-menu", 1, "Re: Degustacja", TEXT + " Dziękujemy.");
        note();
        WatchedMail watched = new WatchedMail(mail);
        watched.marksToFail = 1;
        ReadingRound mine = roundOf(rules(), watched);
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "Own-Title-Qz", PASSAGE)));
        assertThat(mine.round()).isZero();
        mine.forget();
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        assertThat(mine.round()).isEqualTo(1);

        String told = alreadyProposedPart(lastTurn());
        assertThat(told).contains("Menu-Title-Qz").contains("Flowers-Title-Qz").doesNotContain("Own-Title-Qz");
        assertThat(told.indexOf("Menu-Title-Qz")).as("the thread's first").isLessThan(told.indexOf("Flowers-Title-Qz"));
        assertThat(count(told, "Menu-Title-Qz")).isEqualTo(1);
        assertThat(count(told, "Flowers-Title-Qz")).isEqualTo(1);
    }

    @Test
    void aForwardedCopyWithAnotherIdentifierAndAnotherSenderIsToldWhatTheOriginalProposed() {
        gets(ANNA_MAILBOX, "m-original", "thread-original", 4, JAN, "Rezerwacja", TEXT, "<original.Qk7@palace-on-the-hill.example>");
        note();
        readOne(with(with(with(proposal("PAYMENT", "Original-Deposit-Qz", PASSAGE), "amount", "2500.50"), "currency", "PLN"), "dueDay", "2026-12-20"));
        TestClockAdvance.minutes(10);
        // Piotr's mother forwards the same offer, in her own words, from her own address.
        connectPiotrToo();
        gets(PIOTR_MAILBOX, "m-forward", "thread-forward", 1, "Mama Piotra <mama@family.example>", "Fwd: Rezerwacja",
                "Zobacz, co przysłał pan Jan. " + TEXT, "<forward.Zx9@family.example>");
        note();
        readOne();

        String told = alreadyProposedPart(lastTurn());

        assertThat(told).contains("PAYMENT | Original-Deposit-Qz | due 2026-12-20 | 2500.50 PLN\n");
        assertThat(lastTurn()).contains("Mama Piotra");
    }

    private void connectPiotrToo() {
        try {
            connectPiotr();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectedAndAcceptedAndAddedOnesAreToldToo() throws Exception {
        annaGets("m-1", "thread-1", 3, "Degustacja", TEXT);
        note();
        readOne(proposal("TASK", "Rejected-Title-Qz", PASSAGE), proposal("TASK", "Accepted-Title-Qz", PASSAGE));
        for (var shown : waitingSuggestions()) {
            String action = shown.get("title").equals("Rejected-Title-Qz") ? "reject" : "accept";
            assertThat(http.postJsonAs(wedding.anna, "/api/suggestions/" + shown.get("id") + "/" + action, "").statusCode()).isEqualTo(200);
        }
        annaGets("m-2", "thread-2", 1, "Inne", TEXT + " Inne.");
        note();
        readOne();

        String told = alreadyProposedPart(lastTurn());

        assertThat(told).contains("TASK | Rejected-Title-Qz").contains("TASK | Accepted-Title-Qz");
    }

    // ---------------------------------------------------------------- how many, and how far back

    @Test
    void atMostThirtyOfTheLastThirtyDaysTheNewestFirstAndNothingOlder() {
        handIn("x-old", Proposal.Kind.TASK, "Forty-Days-Ago-Qz", null, null, null);
        TestClockAdvance.days(10);
        handIn("x-edge", Proposal.Kind.TASK, "Thirty-One-Days-Ago-Qz", null, null, null);
        TestClockAdvance.days(20);
        TestClockAdvance.seconds(-10);
        for (int n = 1; n <= 35; n++) {
            TestClockAdvance.seconds(1);
            handIn("x-" + n, Proposal.Kind.TASK, "Filler-%02d-Qz".formatted(n), null, null, null);
        }
        TestClockAdvance.days(1);
        handIn("x-recent", Proposal.Kind.TASK, "Yesterday-Qz", null, null, null);
        // The thirty-day mark falls between "Forty..." and the fillers: "Thirty-One-Days-Ago" is 31 days old now.
        annaGets("m-new", "thread-new", 0, "Nowy", TEXT);
        note();

        readOne();

        String told = alreadyProposedPart(lastTurn());
        assertThat(count(told, "Qz\n")).as("at most thirty lines").isEqualTo(30);
        assertThat(told).contains("Yesterday-Qz").contains("Filler-35-Qz").contains("Filler-07-Qz");
        assertThat(told).as("the newest first").satisfies(text -> assertThat(text.indexOf("Yesterday-Qz")).isLessThan(text.indexOf("Filler-35-Qz")));
        assertThat(told).doesNotContain("Filler-06-Qz").doesNotContain("Filler-01-Qz");
        assertThat(told).doesNotContain("Forty-Days-Ago-Qz").doesNotContain("Thirty-One-Days-Ago-Qz");
    }

    @Test
    void whatWasProposedMoreThanThirtyDaysAgoIsNotToldAndWhatWasProposedLessIs() {
        handIn("x-old", Proposal.Kind.TASK, "Thirty-One-Days-Ago-Qz", null, null, null);
        TestClockAdvance.days(20);
        handIn("x-mid", Proposal.Kind.TASK, "Eleven-Days-Ago-Qz", null, null, null);
        TestClockAdvance.days(11);
        annaGets("m-new", "thread-new", 0, "Nowy", TEXT);
        note();

        readOne();

        String told = alreadyProposedPart(lastTurn());
        assertThat(told).contains("Eleven-Days-Ago-Qz").doesNotContain("Thirty-One-Days-Ago-Qz");
    }

    @Test
    void theInstructionsSayThatWhatIsListedIsNotProposedAgainAndWhatIsNewIs() {
        annaGets("m-1", "thread-1", 1, "Degustacja", TEXT);
        note();

        readOne();

        List<Call> extractions = service.calls().stream().filter(call -> "claude-opus-5-5".equals(call.json().get("model"))).toList();
        assertThat(extractions.get(0).system()).contains("Do not propose again what is listed as already proposed.")
                .contains("kind | title | day | amount").contains("from other mail").contains("forwarded copy of the same letter")
                .contains("really new, a later payment or another day, is still proposed")
                .doesNotContain("already proposed from this thread");
    }

    @Test
    void theHandleOfTheMessageIsStillWhatNamesItsOwnProposals() {
        annaGets("m-1", "thread-1", 1, "Degustacja", TEXT);
        note();
        readOne(proposal("TASK", "Mine-Qz", PASSAGE));
        var keys = mail.keysOf(new MessageHandle(weddingId(), anna(), "m-1", "thread-1"));

        assertThat(suggestions.proposedUnder(weddingId(), keys.thread() + "/" + keys.message() + "/", 5)).hasSize(1);
    }
}
