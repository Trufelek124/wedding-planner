package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * From a checked proposal to a suggestion (S04 section 5): the money, the days, the vendor, the category and the contact
 * details are the server's to decide. Made-up mail only.
 */
class ReadingProposalsIntegrationTest extends RoundTestBase {

    static final String TEXT = "Dzień dobry, potwierdzam rezerwację sali oraz zaliczkę. Zespół Echo zagra, a Foto Studio zrobi zdjęcia. "
            + "Prosimy o odpowiedź do piątku. Cena sali wynosi 300 EUR.";
    static final String PASSAGE = "Prosimy o odpowiedź do piątku.";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
        annaGets("m-1", "Rezerwacja", TEXT);
        note();
    }

    private List<Map<String, Object>> readWith(String... proposals) throws Exception {
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposals));
        assertThat(round.round()).isEqualTo(1);
        return waitingSuggestions();
    }

    private Map<String, Object> only(String... proposals) throws Exception {
        List<Map<String, Object>> shown = readWith(proposals);
        assertThat(shown).hasSize(1);
        return shown.get(0);
    }

    private String payment(String amount, String currency) {
        return with(with(proposal("PAYMENT", "Pay the hall", PASSAGE), "amount", amount), "currency", currency);
    }

    private UUID vendor(String name, String email) throws Exception {
        Map<String, Object> vendor = new HashMap<>();
        vendor.put("name", name);
        vendor.put("categoryId", null);
        vendor.put("status", "CONSIDERING");
        vendor.put("contactPerson", "");
        vendor.put("phone", "");
        vendor.put("email", email);
        vendor.put("website", "");
        vendor.put("address", "");
        vendor.put("agreedPrice", null);
        vendor.put("notes", "");
        var created = http.postJsonAs(wedding.anna, "/api/vendors", TestHttp.jsonOf(vendor));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString((String) TestHttp.json(created).get("id"));
    }

    private UUID category(String name) throws Exception {
        var created = http.postJsonAs(wedding.anna, "/api/categories", TestHttp.jsonOf(Map.of("name", name)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString((String) TestHttp.json(created).get("id"));
    }

    // ---------------------------------------------------------------- money

    @Test
    void anAmountInTheWeddingsCurrencyIsCarriedInGroszeWhateverTheWayItIsWritten() throws Exception {
        List<Map<String, Object>> shown = readWith(
                with(payment("2500", "PLN"), "title", "Whole"),
                with(payment("2500.5", "pln"), "title", "One decimal"),
                with(payment("2500.50", "PLN"), "title", "Two decimals"),
                with(payment("0.99", "PLN"), "title", "Less than a zloty"));

        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));
        assertThat(byTitle.get("Whole")).containsEntry("amount", 250_000);
        assertThat(byTitle.get("One decimal")).containsEntry("amount", 250_050);
        assertThat(byTitle.get("Two decimals")).containsEntry("amount", 250_050);
        assertThat(byTitle.get("Less than a zloty")).containsEntry("amount", 99);
        assertThat(shown).allSatisfy(one -> assertThat((String) one.get("notes")).as("nothing to explain").isEmpty());
    }

    @Test
    void anAmountInAnotherCurrencyIsLeftOutAndTheNotesSayWhatTheMailNamed() throws Exception {
        Map<String, Object> suggestion = only(with(payment("300.00", "EUR"), "notes", "Cena sali."));

        assertThat(suggestion.get("amount")).isNull();
        assertThat(suggestion).containsEntry("notes", "Cena sali.\nThe e-mail names 300.00 EUR; the planner counts in PLN.");
        assertThat(suggestion).containsEntry("kind", "PAYMENT");
    }

    @Test
    void anAmountWithNoCurrencyNamedIsLeftOutAndTheNotesSaySo() throws Exception {
        Map<String, Object> suggestion = only(payment("300", ""));

        assertThat(suggestion.get("amount")).isNull();
        assertThat(suggestion).containsEntry("notes", "The e-mail names 300 with no currency; the planner counts in PLN.");
    }

    @Test
    void anAmountAboveThePlannersLimitIsLeftOutAndSaid() throws Exception {
        Map<String, Object> suggestion = only(payment("10000000000.01", "PLN"));

        assertThat(suggestion.get("amount")).isNull();
        assertThat((String) suggestion.get("notes")).startsWith("The e-mail names 10000000000.01 PLN;");
    }

    @Test
    void theLimitItselfIsCarried() throws Exception {
        assertThat(only(payment("10000000000", "PLN"))).containsEntry("amount", 1_000_000_000_000L);
    }

    @Test
    void anAmountOnATaskIsNotCarriedAndNothingIsSaidOfIt() throws Exception {
        Map<String, Object> suggestion = only(with(with(proposal("TASK", "Answer the hall", PASSAGE), "amount", "100"), "currency", "PLN"));

        assertThat(suggestion.get("amount")).isNull();
        assertThat((String) suggestion.get("notes")).isEmpty();
    }

    @Test
    void aPaymentWithNoAmountIsHandedInAllTheSameAndTheKindOnlyOnAPayment() throws Exception {
        List<Map<String, Object>> shown = readWith(
                with(proposal("PAYMENT", "Pay the florist", PASSAGE), "paymentKind", "FINAL"),
                with(proposal("TASK", "Phone the florist", PASSAGE), "paymentKind", "DEPOSIT"));

        assertThat(shown).hasSize(2);
        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));
        assertThat(byTitle.get("Pay the florist")).containsEntry("paymentKind", "FINAL");
        assertThat(byTitle.get("Pay the florist").get("amount")).isNull();
        assertThat(byTitle.get("Phone the florist").get("paymentKind")).isNull();
    }

    @Test
    void anAmountTheStepDoesNotLetThroughDropsTheProposalAndTheOthersStay() throws Exception {
        List<Map<String, Object>> shown = readWith(
                with(payment("2 500,50", "PLN"), "title", "Dropped by the step"), proposal("TASK", "Answer the hall", PASSAGE));

        assertThat(shown).extracting(one -> one.get("title")).containsExactly("Answer the hall");
        assertThat(verdictOf("m-1")).containsEntry("proposals", 1);
    }

    // ---------------------------------------------------------------- days

    @Test
    void aFirstDayIsKeptOnlyOnAStepOfThePlanAndNeverAfterTheDayItIsDue() throws Exception {
        List<Map<String, Object>> shown = readWith(
                with(with(proposal("PLAN_ITEM", "The tasting", PASSAGE), "firstDay", "2027-03-01"), "dueDay", "2027-03-02"),
                with(with(proposal("PLAN_ITEM", "Backwards", PASSAGE), "firstDay", "2027-03-05"), "dueDay", "2027-03-02"),
                with(with(proposal("TASK", "A task with days", PASSAGE), "firstDay", "2027-03-01"), "dueDay", "2027-03-02"));

        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));
        assertThat(byTitle.get("The tasting")).containsEntry("startDate", "2027-03-01").containsEntry("dueDate", "2027-03-02");
        assertThat(byTitle.get("Backwards").get("startDate")).isNull();
        assertThat(byTitle.get("Backwards")).containsEntry("dueDate", "2027-03-02");
        assertThat(byTitle.get("A task with days").get("startDate")).isNull();
        assertThat(byTitle.get("A task with days")).containsEntry("dueDate", "2027-03-02");
    }

    // ---------------------------------------------------------------- vendors

    @Test
    void aVendorIsFoundByTheSendersAddressFirstAndThenByTheProposedName() throws Exception {
        UUID palace = vendor("Pałac na Wzgórzu", "  JAN@palace-on-the-hill.example ");
        UUID echo = vendor("Zespół Echo", "echo@band.example");

        List<Map<String, Object>> shown = readWith(
                with(with(proposal("TASK", "By the address", PASSAGE), "vendorName", "Something else"), "vendorEmail", "x@y.example"),
                with(proposal("TASK", "By the name", PASSAGE), "vendorName", "  zespół ECHO "));
        // The second one is by name only when the sender's address is not a vendor's: here it is, so both are the palace's.
        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));

        assertThat(byTitle.get("By the address")).containsEntry("vendorId", palace.toString());
        assertThat(byTitle.get("By the address").get("vendorName")).isNull();
        assertThat(byTitle.get("By the address").get("vendorEmail")).isNull();
        assertThat(byTitle.get("By the name")).containsEntry("vendorId", palace.toString());
        assertThat(echo).isNotNull();
    }

    @Test
    void aVendorIsFoundByNameWhenTheSendersAddressIsNoVendors() throws Exception {
        UUID echo = vendor("Zespół Echo", "echo@band.example");

        Map<String, Object> suggestion = only(with(with(proposal("TASK", "Ask the band", PASSAGE), "vendorName", "  zespół ECHO "), "vendorPhone", "+48 600 100 200"));

        assertThat(suggestion).containsEntry("vendorId", echo.toString());
        assertThat(suggestion.get("vendorName")).isNull();
        assertThat(suggestion.get("vendorPhone")).as("a vendor the planner has needs no details of its own").isNull();
    }

    @Test
    void twoVendorsOfOneNameAreNoMatchAndTheNameStaysWithTheDetailsTheMessageGave() throws Exception {
        vendor("Studio Foto", "one@studio.example");
        vendor("Studio Foto", "two@studio.example");

        Map<String, Object> suggestion = only(with(with(with(with(proposal("TASK", "Ask the photographer", PASSAGE),
                "vendorName", "Studio Foto"), "vendorEmail", "studio@foto.example"), "vendorPhone", "+48 600 100 200"),
                "vendorWebsite", "www.foto.example"));

        assertThat(suggestion.get("vendorId")).isNull();
        assertThat(suggestion).containsEntry("vendorName", "Studio Foto").containsEntry("vendorEmail", "studio@foto.example")
                .containsEntry("vendorPhone", "+48 600 100 200").containsEntry("vendorWebsite", "https://www.foto.example");
    }

    @Test
    void noMatchKeepsTheNameAndOnlyContactDetailsThatHaveTheirShape() throws Exception {
        List<Map<String, Object>> shown = readWith(
                with(with(with(with(proposal("TASK", "One", PASSAGE), "vendorName", "Nowy Fotograf"),
                        "vendorEmail", "not an address"), "vendorPhone", "+48 600 100 200"), "vendorWebsite", "javascript:alert(1)"),
                with(with(with(proposal("TASK", "Two", PASSAGE), "vendorName", "Nowy Fotograf"), "vendorWebsite", "ftp://files.example/x"),
                        "vendorEmail", "ok@foto.example"),
                with(with(proposal("TASK", "Three", PASSAGE), "vendorName", "Nowy Fotograf"), "vendorWebsite", "foto.example/oferta"),
                with(proposal("TASK", "Four", PASSAGE), "vendorWebsite", "foto.example"));

        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));
        assertThat(byTitle.get("One")).containsEntry("vendorName", "Nowy Fotograf");
        assertThat(byTitle.get("One").get("vendorEmail")).isNull();
        assertThat(byTitle.get("One")).containsEntry("vendorPhone", "+48 600 100 200");
        assertThat(byTitle.get("One").get("vendorWebsite")).isNull();
        assertThat(byTitle.get("Two").get("vendorWebsite")).isNull();
        assertThat(byTitle.get("Two")).containsEntry("vendorEmail", "ok@foto.example");
        assertThat(byTitle.get("Three")).containsEntry("vendorWebsite", "https://foto.example/oferta");
        assertThat(byTitle.get("Four").get("vendorName")).as("no name, no vendor to attach details to").isNull();
        assertThat(byTitle.get("Four").get("vendorWebsite")).isNull();
    }

    @Test
    void aProposedVendorWhoseNameThePlannerHasIsNotHandedInEvenFromAKnownSender() throws Exception {
        vendor("Pałac na Wzgórzu", JANS_ADDRESS);
        vendor("Foto Studio", "foto@studio.example");

        assertThat(readWith(with(proposal("VENDOR", "foto studio", PASSAGE), "vendorEmail", "other@new.example"))).isEmpty();
    }

    @Test
    void aProposedVendorWhoseAddressThePlannerHasIsNotHandedInEvenFromAKnownSender() throws Exception {
        vendor("Pałac na Wzgórzu", JANS_ADDRESS);
        vendor("Zespół Echo", "echo@band.example");

        assertThat(readWith(with(proposal("VENDOR", "A band under a new name", PASSAGE), "vendorEmail", "ECHO@band.example"))).isEmpty();
    }

    @Test
    void aKnownSenderIntroducingItselfAgainIsNotHandedInWhetherItGivesNoAddressOrItsOwn() throws Exception {
        vendor("Pałac na Wzgórzu", JANS_ADDRESS);

        List<Map<String, Object>> shown = readWith(
                proposal("VENDOR", "Pałac, again", PASSAGE),
                with(proposal("VENDOR", "Pałac, once more", PASSAGE), "vendorEmail", " Jan@Palace-On-The-Hill.example"));

        assertThat(shown).isEmpty();
        assertThat(verdictOf("m-1")).containsEntry("proposals", 0);
    }

    @Test
    void aKnownSenderIntroducingABusinessWithAnAddressOfItsOwnIsHandedIn() throws Exception {
        vendor("Pałac na Wzgórzu", JANS_ADDRESS);

        List<Map<String, Object>> shown = readWith(with(proposal("VENDOR", "Kwiaty Ani", PASSAGE), "vendorEmail", "kontakt@kwiaty-ani.example"));

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("title", "Kwiaty Ani").containsEntry("vendorEmail", "kontakt@kwiaty-ani.example");
    }

    @Test
    void aProposedVendorTheNameOrTheProposedAddressMatchesIsNotHandedInEvenWhenTheSenderIsNobodysAddress() throws Exception {
        vendor("Zespół Echo", "echo@band.example");
        vendor("Foto Studio", "foto@studio.example");

        List<Map<String, Object>> shown = readWith(
                with(proposal("VENDOR", "A band", PASSAGE), "vendorEmail", "echo@band.example"),
                proposal("VENDOR", "foto studio", PASSAGE),
                with(with(with(proposal("VENDOR", "Kwiaciarnia Róża", PASSAGE), "vendorEmail", "roza@kwiaty.example"),
                        "vendorPhone", "+48 600 100 200"), "vendorWebsite", "kwiaty.example"));

        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("kind", "VENDOR").containsEntry("title", "Kwiaciarnia Róża")
                .containsEntry("vendorEmail", "roza@kwiaty.example").containsEntry("vendorPhone", "+48 600 100 200")
                .containsEntry("vendorWebsite", "https://kwiaty.example");
        assertThat(shown.get(0).get("vendorName")).as("for a vendor the title is the name").isNull();
        assertThat(verdictOf("m-1")).containsEntry("proposals", 1);
    }

    // ---------------------------------------------------------------- categories

    @Test
    void aCategoryIsFoundByNameWhateverTheCaseAndNoMatchDropsTheName() throws Exception {
        UUID photography = category("Fotografia");

        List<Map<String, Object>> shown = readWith(
                with(proposal("TASK", "Matching", PASSAGE), "category", "  fotografia "),
                with(proposal("TASK", "Not matching", PASSAGE), "category", "Fajerwerki"));

        Map<String, Map<String, Object>> byTitle = new HashMap<>();
        shown.forEach(one -> byTitle.put((String) one.get("title"), one));
        assertThat(byTitle.get("Matching")).containsEntry("categoryId", photography.toString());
        assertThat(byTitle.get("Not matching").get("categoryId")).isNull();
    }

    // ---------------------------------------------------------------- lengths

    @Test
    void aLongSubjectAndANameAreCutAtWhatTheSuggestionsKeepAndALongAddressIsLeftEmpty() throws Exception {
        google.mailbox(ANNA_MAILBOX).write("m-long", "thread-long", daysAgo(1),
                "N".repeat(250) + " <" + "a".repeat(260) + "@palace.example>", "S".repeat(400),
                io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime.plain(TEXT), "INBOX");
        note();
        jdbc.update("UPDATE mail_message SET verdict = 'NOT_WEDDING', documents = 'DONE', read_at = now() WHERE message_id = 'm-1'");
        service.reset();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays(proposal("TASK", "A task", PASSAGE)));

        assertThat(round.round()).isEqualTo(1);

        Map<String, Object> suggestion = waitingSuggestions().get(0);
        assertThat((String) suggestion.get("subject")).hasSize(300);
        assertThat((String) suggestion.get("senderName")).hasSize(200);
        assertThat(suggestion).containsEntry("senderAddress", "");
    }

    @Test
    void theNotesKeepTheLineThatSaysWhatIsMissingWhenTheyAreCut() {
        String line = "The e-mail names 300 with no currency; the planner counts in PLN.";
        String made = ProposalMaker.cut("n".repeat(20_000), ProposalMaker.NOTES - line.length() - 1);

        assertThat(made.length() + 1 + line.length()).isEqualTo(ProposalMaker.NOTES);
    }
}
