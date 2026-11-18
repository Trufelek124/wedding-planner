package io.github.trufelek124.weddingplanner.guests.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestChange;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.GuestResponse;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HeadCount;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HouseholdName;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.HouseholdResponse;
import io.github.trufelek124.weddingplanner.guests.internal.GuestDtos.NewGuest;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.GuestRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestRepository.HouseholdRow;
import io.github.trufelek124.weddingplanner.guests.internal.GuestTypes.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GuestRecordsTest {

    private static final List<String> PERSONAL = List.of(
            "Bronislawa Wisniewska", "Coeliac, strictly", "+48 622 333 444", "bronka@wisniewscy.example", "Uses a wheelchair",
            "Rodzina Wisniewskich");

    /** The web framework prints what an endpoint is called with through toString(): it must give nothing away. */
    @Test
    void noRecordThatHoldsAGuestOrAHouseholdPrintsWhatItHolds() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        UUID id = UUID.randomUUID();
        UUID side = UUID.randomUUID();
        GuestRow row = new GuestRow(id, PERSONAL.get(0), side, null, Status.INVITED, true, 2, PERSONAL.get(1), true, PERSONAL.get(2),
                PERSONAL.get(3), PERSONAL.get(4), 3, now, now);
        NewGuest added = new NewGuest(PERSONAL.get(0), side, null, Status.INVITED, true, 2, PERSONAL.get(1), true, PERSONAL.get(2),
                PERSONAL.get(3), PERSONAL.get(4));
        GuestChange change = new GuestChange(3, PERSONAL.get(0), side, null, Status.INVITED, true, 2, PERSONAL.get(1), true,
                PERSONAL.get(2), PERSONAL.get(3), PERSONAL.get(4));
        HouseholdRow household = new HouseholdRow(id, PERSONAL.get(5), 4);

        List<Object> records = List.of(
                row, added, change, GuestResponse.of(row), household, new HouseholdName(PERSONAL.get(5)), HouseholdResponse.of(household));
        for (Object record : records) {
            assertThat(record.toString()).as(record.getClass().getSimpleName()).doesNotContain(PERSONAL);
            // Not even whose side, with whom, or what they need.
            assertThat(record.toString()).as(record.getClass().getSimpleName())
                    .doesNotContain(side.toString()).doesNotContain("plusOne").doesNotContain("children").doesNotContain("Accommodation");
        }
        assertThat(row.toString()).contains(id.toString());
    }

    @Test
    void aGuestStandsForHerselfHerPlusOneAndHerChildren() {
        Instant now = Instant.parse("2026-11-18T10:00:00Z");
        GuestRow alone = new GuestRow(UUID.randomUUID(), "A", null, null, Status.INVITED, false, 0, "", false, "", "", "", 0, now, now);
        GuestRow family = new GuestRow(UUID.randomUUID(), "B", null, null, Status.INVITED, true, 3, "", false, "", "", "", 0, now, now);

        assertThat(GuestResponse.of(alone).heads()).isEqualTo(1);
        assertThat(GuestResponse.of(family).heads()).isEqualTo(5);
        assertThat(HeadCount.of(2, 1, 3).plus(HeadCount.of(1, 1, 0))).isEqualTo(new HeadCount(3, 2, 3, 8));
    }
}
