package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.inspirations.internal.CanvasPlacement.Place;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a new idea arrives on the canvas (S08 section 2), with no database. */
class CanvasPlacementTest {

    private static Place at(int x, int y) {
        return new Place(x, y);
    }

    /** Adds ideas one after the other, as the server does, and says where each one went. */
    private static List<Place> arrivals(List<Place> before, int howMany) {
        List<Place> all = new ArrayList<>(before);
        for (int i = 0; i < howMany; i++) {
            all.add(CanvasPlacement.next(all));
        }
        return all;
    }

    @Test
    void theFirstOneLiesAtTheOrigin() {
        assertThat(CanvasPlacement.next(List.of())).isEqualTo(at(0, 0));
    }

    @Test
    void theNextOneLiesOneSlotToTheRightOfTheLowest() {
        assertThat(CanvasPlacement.next(List.of(at(0, 0)))).isEqualTo(at(220, 0));
        assertThat(CanvasPlacement.next(List.of(at(0, 0), at(220, 0)))).isEqualTo(at(440, 0));
    }

    @Test
    void ofSeveralEquallyLowTheRightMostCounts() {
        assertThat(CanvasPlacement.next(List.of(at(440, 260), at(0, 260), at(220, 260), at(900, 0)))).isEqualTo(at(660, 260));
    }

    @Test
    void theLowestCountsEvenWhenItIsNotTheNewest() {
        assertThat(CanvasPlacement.next(List.of(at(1000, 0), at(0, 520), at(220, 0)))).isEqualTo(at(220, 520));
    }

    @Test
    void sixLieInARowAndTheSeventhBeginsANewOne() {
        List<Place> six = arrivals(List.of(), 6);
        assertThat(six).containsExactly(at(0, 0), at(220, 0), at(440, 0), at(660, 0), at(880, 0), at(1100, 0));

        assertThat(CanvasPlacement.next(six)).as("the seventh is not in the first row").isEqualTo(at(0, 260));
        assertThat(arrivals(List.of(), 14).get(12)).isEqualTo(at(0, 520));
        assertThat(arrivals(List.of(), 14).get(13)).isEqualTo(at(220, 520));
    }

    @Test
    void aNewRowIsStartedWhereTheSlotBesideTheLowestIsTakenByACardThatWasMovedThere() {
        // The lowest is (20, 100); beside it would be (240, 100), but a card was dragged to (240, -100), within a slot of it.
        List<Place> covered = List.of(at(0, 0), at(240, -100), at(20, 100));

        assertThat(CanvasPlacement.next(covered)).isEqualTo(at(0, 360));
    }

    @Test
    void aCardThatLiesAFullSlotAwayDoesNotTakeTheSlot() {
        // 260 down is a whole slot: (240, -160) and (240, 100) do not share one.
        List<Place> apart = List.of(at(0, 0), at(240, -160), at(20, 100));

        assertThat(CanvasPlacement.next(apart)).isEqualTo(at(240, 100));
    }

    @Test
    void afterACardWasMovedFarBelowNewOnesFollowIt() {
        List<Place> before = List.of(at(0, 0), at(220, 0), at(300, 5000));

        assertThat(CanvasPlacement.next(before)).isEqualTo(at(520, 5000));
    }

    @Test
    void afterACardWasMovedFarToTheRightTheSixSlotRuleCountsFromTheLeftEdgeOfEverything() {
        List<Place> before = List.of(at(0, 0), at(5000, 0));
        // Beside the lowest (5000, 0) would lie at 5220: far more than five slots from the left edge, 0.
        assertThat(CanvasPlacement.next(before)).isEqualTo(at(0, 260));

        List<Place> shifted = List.of(at(-3000, 0), at(-2000, 0));
        assertThat(CanvasPlacement.next(shifted)).as("the left edge is -3000: -1780 is 1220 from it").isEqualTo(at(-3000, 260));

        List<Place> near = List.of(at(-3000, 0), at(-2300, 0));
        assertThat(CanvasPlacement.next(near)).as("-2080 is 920 from the left edge").isEqualTo(at(-2080, 0));
    }

    @Test
    void aNewRowBeginsAtTheLeftEdgeOfEverythingNotAtZero() {
        List<Place> before = List.of(at(-440, 0), at(0, 0), at(220, 0), at(440, 0), at(660, 0), at(880, 0));

        assertThat(CanvasPlacement.next(before)).isEqualTo(at(-440, 260));
    }

    @Test
    void neverBeyondTheLimits() {
        assertThat(CanvasPlacement.next(List.of(at(100_000, 0)))).as("beside it would be past the right limit").isEqualTo(at(100_000, 260));
        assertThat(CanvasPlacement.next(List.of(at(99_900, 0), at(99_800, 0)))).isEqualTo(at(99_800, 260));
        assertThat(CanvasPlacement.next(List.of(at(0, 100_000)))).as("past the bottom limit it stays on the limit")
                .isEqualTo(at(220, 100_000));
        assertThat(CanvasPlacement.next(List.of(at(100_000, 100_000)))).isEqualTo(at(100_000, 100_000));
        assertThat(CanvasPlacement.next(List.of(at(-100_000, -100_000)))).isEqualTo(at(-99_780, -100_000));
    }

    @Test
    void aCardAtTheFarCornerStillGetsAPlaceWithinTheLimits() {
        Place next = CanvasPlacement.next(List.of(at(-100_000, 99_900), at(100_000, 99_900)));

        assertThat(next.x()).isBetween(-100_000, 100_000);
        assertThat(next.y()).isBetween(-100_000, 100_000);
    }
}
