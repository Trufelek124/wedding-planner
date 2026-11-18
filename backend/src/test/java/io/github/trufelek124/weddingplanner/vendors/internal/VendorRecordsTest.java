package io.github.trufelek124.weddingplanner.vendors.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.vendors.VendorUse.Use;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.NewVendor;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorChange;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorResponse;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorRepository.VendorRow;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class VendorRecordsTest {

    private static final List<String> PERSONAL = List.of(
            "Fotografia Zielinska", "Halina Zielinska", "+48 611 222 333", "halina@zielinska.example", "https://zielinska.example",
            "Ogrodowa 7", "Prefers a call after six");

    /** Request and response bodies reach debug logs through toString(): it must give nothing away. */
    @Test
    void noRecordThatHoldsAVendorPrintsItsNameOrContactDetails() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        UUID id = UUID.randomUUID();
        VendorRow row = new VendorRow(id, PERSONAL.get(0), null, Status.BOOKED, PERSONAL.get(1), PERSONAL.get(2), PERSONAL.get(3),
                PERSONAL.get(4), PERSONAL.get(5), 450_000L, PERSONAL.get(6), 3, now, now);
        NewVendor added = new NewVendor(PERSONAL.get(0), null, Status.BOOKED, PERSONAL.get(1), PERSONAL.get(2), PERSONAL.get(3),
                PERSONAL.get(4), PERSONAL.get(5), 450_000L, PERSONAL.get(6));
        VendorChange change = new VendorChange(3, PERSONAL.get(0), null, Status.BOOKED, PERSONAL.get(1), PERSONAL.get(2),
                PERSONAL.get(3), PERSONAL.get(4), PERSONAL.get(5), 450_000L, PERSONAL.get(6));
        VendorResponse response = VendorResponse.of(row, Use.NONE);

        for (Object record : List.of(row, added, change, response)) {
            assertThat(record.toString()).as(record.getClass().getSimpleName()).doesNotContain(PERSONAL).doesNotContain("450000");
        }
        // It still says which vendor is meant, by its id.
        assertThat(row.toString()).contains(id.toString());
        assertThat(response.toString()).contains(id.toString());
    }

    @ParameterizedTest
    @CsvSource({
        "example.com, https://example.com",
        "www.example.com/oferta?x=1, https://www.example.com/oferta?x=1",
        "http://example.com, http://example.com",
        "HTTPS://Example.com/A, HTTPS://Example.com/A",
    })
    void aWebAddressIsKeptWithItsBeginning(String typed, String kept) {
        assertThat(VendorService.webAddress(typed)).contains(kept);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)", "data:text/html,x", "ftp://example.com", "mailto:a@example.com", "file:///etc/passwd",
        "not a site", "localhost", "https://", "https://user:secret@example.com", "//example.com"
    })
    void whatIsNotAWebAddressIsNotKept(String typed) {
        assertThat(VendorService.webAddress(typed)).isEmpty();
    }
}
