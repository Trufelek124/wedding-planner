package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Place;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S04 section 5 and D-014: the server says where a passage stands. It is the server's finding in what was sent; nothing the
 * model answers is asked or trusted for it. All text is made up.
 */
class CheckingWhereAPassageStandsTest {

    private static final String MESSAGE = "Hello, the deposit of 2500 PLN is due on 1 December. Greetings, Jan";
    private static final String EARLIER = "Last week we agreed on the date of the tasting: Friday the 13th at noon.";
    private static final String DOCUMENT = "Offer 17\nSchedule:\nDeposit: 2500,00 PLN by 1 December 2026\nFinal payment: 7500,00 PLN";

    private static RawExtraction.Proposal proposal(String passage) {
        return new RawExtraction.Proposal(
                "PAYMENT", "Pay", "", "", "", "", "", "", "", "", "", "", "", 90, passage);
    }

    private static Place placeOf(String passage, Checking.Sent sent) {
        var answer = Checking.extracted(new RawExtraction(List.of(proposal(passage))), sent, MailExtraction.MOST_PROPOSALS_WITH_DOCUMENTS);
        assertThat(answer).isPresent();
        if (answer.get().proposals().isEmpty()) {
            return null;
        }
        return answer.get().proposals().get(0).place();
    }

    private static final Checking.Sent SENT = new Checking.Sent(MESSAGE, List.of(EARLIER), List.of(DOCUMENT));

    @Test
    void aPassageInTheMessageIsFoundInTheMessage() {
        assertThat(placeOf("the deposit of 2500 PLN is due on 1 December", SENT)).isEqualTo(Place.MESSAGE);
    }

    @Test
    void aPassageInAnEarlierMessageIsFoundInTheEarlierMessage() {
        assertThat(placeOf("Friday the 13th at noon", SENT)).isEqualTo(Place.EARLIER_MESSAGE);
    }

    @Test
    void aPassageInADocumentIsFoundInTheDocumentLineBreaksAndSpacesNotMattering() {
        assertThat(placeOf("Deposit: 2500,00 PLN by 1 December 2026", SENT)).isEqualTo(Place.DOCUMENT);
        assertThat(placeOf("Schedule:  Deposit:\n2500,00 PLN", SENT)).isEqualTo(Place.DOCUMENT);
    }

    @Test
    void aPassageThatStandsInBothTheDocumentAndTheMessageIsFromTheDocument() {
        Checking.Sent both = new Checking.Sent("Please pay Deposit: 2500,00 PLN by 1 December 2026 as written.", List.of(), List.of(DOCUMENT));

        assertThat(placeOf("Deposit: 2500,00 PLN by 1 December 2026", both)).isEqualTo(Place.DOCUMENT);
    }

    @Test
    void aPassageInTheMessageAndAnEarlierMessageIsFromTheMessage() {
        Checking.Sent both = new Checking.Sent(MESSAGE, List.of("As I said: the deposit of 2500 PLN is due on 1 December, thanks."), List.of());

        assertThat(placeOf("the deposit of 2500 PLN is due on 1 December", both)).isEqualTo(Place.MESSAGE);
    }

    @Test
    void aPassageThatStandsNowhereIsDroppedAndTheOthersStay() {
        var answer = Checking.extracted(
                new RawExtraction(List.of(proposal("Deposit: 99999,00 PLN by tomorrow"), proposal("Final payment: 7500,00 PLN"))), SENT,
                MailExtraction.MOST_PROPOSALS_WITH_DOCUMENTS);

        assertThat(answer.get().proposals()).hasSize(1);
        assertThat(answer.get().proposals().get(0).place()).isEqualTo(Place.DOCUMENT);
    }

    @Test
    void whenNoDocumentWasSentNothingIsEverFromADocument() {
        Checking.Sent none = new Checking.Sent(MESSAGE, List.of(EARLIER), List.of());

        assertThat(placeOf("Deposit: 2500,00 PLN by 1 December 2026", none)).isNull();
        assertThat(placeOf("the deposit of 2500 PLN is due on 1 December", none)).isEqualTo(Place.MESSAGE);
    }
}
