package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposals;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestPdfs;
import io.github.trufelek124.weddingplanner.mailreading.StandInAnthropic.Reply;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * D-014 the whole way: a payment that the offer (a PDF the test makes) names is added to the plan by itself, and what the mail's
 * own words name, or a vendor, waits. The server finds where the passage stands; the model's answer has no say. All made up.
 */
class ReadingPaymentsFromDocumentsIntegrationTest extends RoundTestBase {

    static final String MAIL = "Dzień dobry, w załączeniu oferta. Zaliczkę prosimy wpłacić do 1 grudnia 2026. Pozdrawiam, Jan";
    static final String MAIL_ONLY_PASSAGE = "Zaliczkę prosimy wpłacić do 1 grudnia 2026.";
    static final String DEPOSIT_LINE = "Zaliczka: 2500,00 PLN do 1 grudnia 2026";
    static final String OFFER = "Oferta nr 17/2026\nPałac na Wzgórzu, sala na 19 czerwca 2027\n\nHarmonogram płatności:\n" + DEPOSIT_LINE
            + "\nPałac na Wzgórzu, ul. Parkowa 1";

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private static String payment(String title, String passage, String amount, String day, String kind) {
        return with(with(with(with(with(with(proposal("PAYMENT", title, passage), "amount", amount), "currency", "PLN"), "dueDay", day),
                "paymentKind", kind), "howSure", "95"), "category", "");
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

    private void theOfferComes(String id) throws Exception {
        annaGetsWithFiles(id, "Oferta", MAIL, Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        note();
    }

    private Map<String, Object> suggestionTitled(String title) throws Exception {
        return allSuggestions().stream().filter(s -> title.equals(s.get("title"))).findFirst().orElseThrow();
    }

    private Map<String, Object> entryOf(Map<String, Object> suggestion) throws Exception {
        return TestHttp.json(http.getAs(wedding.anna, "/api/entries/" + suggestion.get("madeEntryId")));
    }

    @Test
    void aPaymentTheOfferNamesIsAddedWithItsAmountDayAndKindAndLinkedToTheVendorTheSenderIs() throws Exception {
        UUID palace = vendor("Pałac na Wzgórzu", "jan@palace-on-the-hill.example");
        theOfferComes("m-offer");
        service.answerNextCallsWith(triageSays(true, 95),
                Reply.says(proposals(payment("Wpłacić zaliczkę", DEPOSIT_LINE, "2500.00", "2026-12-01", "DEPOSIT"))));

        assertThat(round.round()).isEqualTo(1);

        Map<String, Object> suggestion = suggestionTitled("Wpłacić zaliczkę");
        assertThat(suggestion).containsEntry("state", "ADDED").containsEntry("fromDocument", true).containsEntry("passage", DEPOSIT_LINE);
        assertThat(entryOf(suggestion))
                .containsEntry("kind", "TASK")
                .containsEntry("amount", 250_000)
                .containsEntry("dueDate", "2026-12-01")
                .containsEntry("paymentKind", "DEPOSIT")
                .containsEntry("vendorId", palace.toString())
                .containsEntry("status", "NOT_STARTED")
                .containsEntry("fromSuggestionId", suggestion.get("id"));
        assertThat(TestHttp.json(http.getAs(wedding.anna, "/api/budget"))).containsEntry("committed", 250_000);
        assertThat(waitingSuggestions()).isEmpty();
    }

    @Test
    void whereTheSenderIsNoVendorOfThePlannerThePaymentHasNoneAndNoVendorIsMade() throws Exception {
        theOfferComes("m-offer");
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(
                with(payment("Wpłacić zaliczkę", DEPOSIT_LINE, "2500.00", "2026-12-01", "DEPOSIT"), "vendorName", "Pałac na Wzgórzu"))));

        round.round();

        Map<String, Object> suggestion = suggestionTitled("Wpłacić zaliczkę");
        assertThat(suggestion).containsEntry("state", "ADDED");
        assertThat(entryOf(suggestion)).containsEntry("vendorId", null).containsEntry("amount", 250_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor", Integer.class)).isZero();
    }

    @Test
    void aPaymentOnlyTheMailsWordsNameAndAVendorWaitWhileTheOneFromTheOfferIsAdded() throws Exception {
        theOfferComes("m-offer");
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(
                payment("Wpłacić zaliczkę", DEPOSIT_LINE, "2500.00", "2026-12-01", "DEPOSIT"),
                payment("Wpłacić według maila", MAIL_ONLY_PASSAGE, "900.00", "2026-12-01", "DEPOSIT"),
                with(proposal("VENDOR", "Pałac na Wzgórzu", "Pałac na Wzgórzu, ul. Parkowa 1"), "howSure", "99"))));

        round.round();

        assertThat(suggestionTitled("Wpłacić zaliczkę")).containsEntry("state", "ADDED").containsEntry("fromDocument", true);
        assertThat(suggestionTitled("Wpłacić według maila")).containsEntry("state", "WAITING").containsEntry("fromDocument", false);
        assertThat(suggestionTitled("Pałac na Wzgórzu")).containsEntry("state", "WAITING").containsEntry("fromDocument", true);
        assertThat(jdbc.queryForList("SELECT amount FROM entry", Long.class)).containsExactly(250_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor", Integer.class)).isZero();
    }

    @Test
    void aModelThatClaimsADocumentForAPassageOfTheMailOnlyGetsNothingAdded() throws Exception {
        theOfferComes("m-offer");
        // The answer claims a document for a passage that only the mail has. The shape has no such field: an answer with one is not
        // the shape that was asked for, and is not used. Either way, nothing of it makes a payment that is added or "from a document".
        String claims = payment("Wpłacić według maila", MAIL_ONLY_PASSAGE, "900.00", "2026-12-01", "DEPOSIT")
                .replaceFirst("\\}$", ",\"fromDocument\":true,\"place\":\"DOCUMENT\"}");
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(claims)));

        round.round();

        assertThat(allSuggestions()).allSatisfy(s -> assertThat(s).containsEntry("state", "WAITING").containsEntry("fromDocument", false));
        assertThat(entryCount()).isZero();
    }

    @Test
    void aPaymentInAnotherCurrencyOrWithNoAmountWaits() throws Exception {
        theOfferComes("m-offer");
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(
                with(payment("Wpłacić w euro", DEPOSIT_LINE, "2500.00", "2026-12-01", "DEPOSIT"), "currency", "EUR"),
                payment("Wpłacić bez kwoty", DEPOSIT_LINE, "", "2026-12-02", "DEPOSIT"))));

        round.round();

        assertThat(suggestionTitled("Wpłacić w euro")).containsEntry("state", "WAITING").containsEntry("amount", null).containsEntry("fromDocument", true);
        assertThat(suggestionTitled("Wpłacić bez kwoty")).containsEntry("state", "WAITING").containsEntry("amount", null);
        assertThat(entryCount()).isZero();
    }

    @Test
    void theSameOfferReadInAnotherLetterProposesThePaymentOnlyOnce() throws Exception {
        theOfferComes("m-offer");
        annaGetsWithFiles("m-again", "Oferta jeszcze raz", MAIL, Attached.pdf("oferta.pdf", TestPdfs.withPages(OFFER)));
        note();
        String again = payment("Wpłacić zaliczkę", DEPOSIT_LINE, "2500.00", "2026-12-01", "DEPOSIT");
        service.answerNextCallsWith(triageSays(true, 95), Reply.says(proposals(again)), triageSays(true, 95), Reply.says(proposals(again)));

        round.round();

        List<Map<String, Object>> all = allSuggestions();
        assertThat(all).extracting(s -> s.get("state")).containsExactlyInAnyOrder("ADDED", "WAITING");
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM entry", Long.class)).as("counted once in the budget").isEqualTo(250_000L);
    }

    private int entryCount() {
        return jdbc.queryForObject("SELECT count(*) FROM entry", Integer.class);
    }
}
