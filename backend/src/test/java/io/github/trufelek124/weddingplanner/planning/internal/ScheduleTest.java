package io.github.trufelek124.weddingplanner.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.planning.internal.DependencyRepository.Link;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.EntryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PriorityMode;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    private final UUID a = id(1);
    private final UUID b = id(2);
    private final UUID c = id(3);
    private final UUID d = id(4);

    @Test
    void aPlanItemCannotWaitForItself() {
        assertThat(schedule().wouldCloseACircle(a, a)).isTrue();
    }

    @Test
    void twoPlanItemsCannotWaitForEachOther() {
        Schedule schedule = schedule(link(a, b));

        assertThat(schedule.wouldCloseACircle(b, a)).isTrue();
        assertThat(schedule.wouldCloseACircle(a, b)).as("saying the same again is no circle").isFalse();
    }

    @Test
    void aCircleThroughOthersIsFoundToo() {
        // a waits for b, b waits for c, c waits for d
        Schedule schedule = schedule(link(a, b), link(b, c), link(c, d));

        assertThat(schedule.wouldCloseACircle(d, a)).isTrue();
        assertThat(schedule.wouldCloseACircle(c, a)).isTrue();
        assertThat(schedule.wouldCloseACircle(a, d)).as("a shortcut in the same direction is fine").isFalse();
        assertThat(schedule.wouldCloseACircle(a, c)).isFalse();
    }

    @Test
    void twoItemsMayWaitForTheSameThirdAndOneForTwo() {
        // A diamond: a waits for b and c, both of which wait for d.
        Schedule schedule = schedule(link(a, b), link(a, c), link(b, d), link(c, d));

        assertThat(schedule.wouldCloseACircle(b, c)).isFalse();
        assertThat(schedule.wouldCloseACircle(d, a)).isTrue();
    }

    @Test
    void anItemConflictsWithWhatItWaitsForWhenThatEndsAfterItBegins() {
        List<EntryRow> entries = List.of(
                item(a, LocalDate.of(2027, 3, 10), LocalDate.of(2027, 3, 20), Status.NOT_STARTED),
                item(b, null, LocalDate.of(2027, 3, 9), Status.NOT_STARTED),
                item(c, null, LocalDate.of(2027, 3, 10), Status.NOT_STARTED),
                item(d, null, LocalDate.of(2027, 3, 11), Status.NOT_STARTED));
        Schedule schedule = new Schedule(entries, List.of(link(a, b), link(a, c), link(a, d)));

        // b ends the day before a begins, c on the very day (allowed), d a day too late.
        assertThat(schedule.conflicts(a)).containsExactly(d);
    }

    @Test
    void anItemWithoutAStartIsMeasuredByItsEndAndUndatedItemsNeverConflict() {
        List<EntryRow> entries = List.of(
                item(a, null, LocalDate.of(2027, 3, 10), Status.NOT_STARTED),
                item(b, null, LocalDate.of(2027, 3, 15), Status.NOT_STARTED),
                item(c, null, null, Status.NOT_STARTED),
                item(d, null, null, Status.NOT_STARTED));
        Schedule schedule = new Schedule(entries, List.of(link(a, b), link(a, c), link(d, b)));

        assertThat(schedule.conflicts(a)).containsExactly(b);
        assertThat(schedule.conflicts(d)).isEmpty();
    }

    @Test
    void theEarliestUnfinishedItemThatWaitsDecidesHowSoonSomethingIsNeeded() {
        List<EntryRow> entries = List.of(
                item(a, null, null, Status.NOT_STARTED),
                item(b, null, LocalDate.of(2027, 5, 1), Status.NOT_STARTED),
                item(c, null, LocalDate.of(2027, 4, 1), Status.IN_PROGRESS),
                item(d, null, LocalDate.of(2027, 3, 1), Status.DONE));
        Schedule schedule = new Schedule(entries, List.of(link(b, a), link(c, a), link(d, a)));

        assertThat(schedule.earliestDependentDue(a)).as("what is done no longer waits").isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(schedule.earliestDependentDue(b)).isNull();
    }

    private Schedule schedule(Link... links) {
        List<EntryRow> entries = new ArrayList<>();
        for (UUID id : List.of(a, b, c, d)) {
            entries.add(item(id, null, null, Status.NOT_STARTED));
        }
        return new Schedule(entries, List.of(links));
    }

    private static Link link(UUID entry, UUID dependsOn) {
        return new Link(entry, dependsOn);
    }

    private static EntryRow item(UUID id, LocalDate start, LocalDate due, Status status) {
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        return new EntryRow(
                id, Kind.PLAN_ITEM, "item", "", null, start, due, status, status == Status.DONE ? now : null,
                PriorityMode.AUTO, null, null, new ArrayList<>(), 0, 0, now, now);
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}
