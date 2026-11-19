package io.github.trufelek124.weddingplanner.mailreading.internal;

import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.proposal;
import static io.github.trufelek124.weddingplanner.mailreading.internal.Answers.with;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** From a noted message to what the two owners see in the inbox of suggestions: all against stand-ins, with made-up mail. */
class ReadingTheWholeWayIntegrationTest extends RoundTestBase {

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
    }

    private UUID aVendorWithAddress(String name, String email) throws Exception {
        Map<String, Object> vendor = new java.util.HashMap<>();
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

    @Test
    void aMessageInPolishAboutTheWeddingBecomesASuggestionThatTheOwnersSeeAndIsMarked() throws Exception {
        UUID palace = aVendorWithAddress("Pałac na Wzgórzu", JANS_ADDRESS);
        annaGets("m-polish", "Rezerwacja sali na 19 czerwca", POLISH);
        note();
        String deposit = with(with(with(with(with(proposal("PAYMENT", "Wpłacić zaliczkę za salę", DEPOSIT_PASSAGE),
                "amount", "2500.50"), "currency", "PLN"), "paymentKind", "DEPOSIT"), "dueDay", "2026-12-01"), "notes", "Zaliczka za salę.");
        service.answerNextCallsWith(triageSays(true, 95), extractionSays(deposit));

        assertThat(round.round()).isEqualTo(1);

        List<Map<String, Object>> shown = waitingSuggestions();
        assertThat(shown).hasSize(1);
        Map<String, Object> suggestion = shown.get(0);
        assertThat(suggestion).containsEntry("kind", "PAYMENT").containsEntry("state", "WAITING")
                .containsEntry("title", "Wpłacić zaliczkę za salę")
                .containsEntry("senderName", "Jan from Palace on the Hill").containsEntry("senderAddress", JANS_ADDRESS)
                .containsEntry("subject", "Rezerwacja sali na 19 czerwca")
                .containsEntry("inboxMemberId", wedding.annaId)
                .containsEntry("passage", DEPOSIT_PASSAGE)
                .containsEntry("dueDate", "2026-12-01").containsEntry("amount", 250_050).containsEntry("paymentKind", "DEPOSIT")
                .containsEntry("vendorId", palace.toString()).containsEntry("confidence", 80);
        assertThat(suggestion.get("vendorName")).as("a vendor the planner has needs no name of its own").isNull();
        assertThat((String) suggestion.get("mailLink")).startsWith("https://mail.google.com/mail/?authuser=").endsWith("#all/m-polish");
        assertThat(suggestion.get("receivedAt")).isNotNull();
        assertThat(verdictOf("m-polish")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 1);
        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isEqualTo(1);
    }

    @Test
    void aMessageThatIsNotAboutTheWeddingIsMarkedAndExtractionIsNeverAsked() throws Exception {
        annaGets("m-news", "Newsletter", "Nasz nowy katalog sukien dla panien młodych jest już dostępny.");
        note();
        service.answerNextCallsWith(triageSays(false, 97));

        assertThat(round.round()).isEqualTo(1);

        assertThat(verdictOf("m-news")).containsEntry("verdict", "NOT_WEDDING");
        assertThat(triageCalls()).isEqualTo(1);
        assertThat(extractionCalls()).isZero();
        assertThat(suggestionRows()).isZero();
    }

    @Test
    void aNoThatIsLessSureThanTheShareIsReadOnAsIfItWereAYes() throws Exception {
        annaGets("m-unsure", "Oferta", POLISH);
        annaGets("m-sure-enough", "Oferta 2", POLISH);
        note();
        // The newest is read first. 59 is below the share of 60 and is read on; 60 is not below it and is not.
        service.answerNextCallsWith(triageSays(false, 59), extractionSays(), triageSays(false, 60));

        assertThat(round.round()).isEqualTo(2);

        assertThat(extractionCalls()).isEqualTo(1);
        assertThat(List.of(verdictOf("m-unsure").get("verdict"), verdictOf("m-sure-enough").get("verdict")))
                .containsExactlyInAnyOrder("WEDDING", "NOT_WEDDING");
    }

    @Test
    void aMessageAboutTheWeddingThatAsksForNothingIsReadWithNoProposals() throws Exception {
        annaGets("m-thanks", "Dziękujemy", POLISH);
        note();
        service.answerNextCallsWith(triageSays(true, 90), extractionSays());

        round.round();

        assertThat(verdictOf("m-thanks")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
        assertThat(suggestionRows()).isZero();
    }
}
