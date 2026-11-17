package io.github.trufelek124.weddingplanner.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Assessment;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Facts;
import io.github.trufelek124.weddingplanner.planning.internal.PriorityRule.Reason;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The table of S01 section 5, row by row and day by day around each boundary. */
class PriorityRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 3, 10);

    @ParameterizedTest(name = "a task due in {0} days is {1} ({2})")
    @CsvSource({
        "-30, CRITICAL, OVERDUE",
        " -1, CRITICAL, OVERDUE",
        "  0, CRITICAL, DUE_WITHIN_3_DAYS",
        "  3, CRITICAL, DUE_WITHIN_3_DAYS",
        "  4, HIGH,     DUE_WITHIN_14_DAYS",
        " 14, HIGH,     DUE_WITHIN_14_DAYS",
        " 15, NORMAL,   DUE_WITHIN_60_DAYS",
        " 60, NORMAL,   DUE_WITHIN_60_DAYS",
        " 61, LOW,      DUE_LATER",
        "400, LOW,      DUE_LATER",
    })
    void aTaskByItsDueDate(int days, Priority priority, Reason reason) {
        assertThat(assess(task(days))).isEqualTo(new Assessment(priority, reason));
    }

    @Test
    void whatHasNoDateIsLow() {
        assertThat(assess(task(null))).isEqualTo(new Assessment(Priority.LOW, Reason.NO_DATE));
    }

    @ParameterizedTest(name = "a payment due in {0} days is {1} ({2})")
    @CsvSource({
        " 3, CRITICAL, DUE_WITHIN_3_DAYS",
        " 4, CRITICAL, PAYMENT_DUE_WITHIN_7_DAYS",
        " 7, CRITICAL, PAYMENT_DUE_WITHIN_7_DAYS",
        " 8, HIGH,     DUE_WITHIN_14_DAYS",
        "-1, CRITICAL, OVERDUE",
    })
    void aPaymentIsCriticalAWeekAhead(int days, Priority priority, Reason reason) {
        Facts payment = new Facts(Kind.TASK, Status.NOT_STARTED, null, TODAY.plusDays(days), true, null);

        assertThat(assess(payment)).isEqualTo(new Assessment(priority, reason));
    }

    @ParameterizedTest(name = "due in {0} days, something due in {1} days waits for it: {2} ({3})")
    @CsvSource({
        // The entry itself is far off or undated; what waits for it decides.
        "100,  30, HIGH,   NEEDED_BY_ANOTHER",
        "100,  31, LOW,    DUE_LATER",
        "100,  -5, HIGH,   NEEDED_BY_ANOTHER",
        " 40,  20, HIGH,   NEEDED_BY_ANOTHER",
        " 40,  31, NORMAL, DUE_WITHIN_60_DAYS",
        // Its own date decides when that is the stronger reason.
        " 10,  20, HIGH,     DUE_WITHIN_14_DAYS",
        "  2,  20, CRITICAL, DUE_WITHIN_3_DAYS",
    })
    void anEntryAnotherOneWaitsFor(int days, int dependentDays, Priority priority, Reason reason) {
        Facts facts = new Facts(Kind.PLAN_ITEM, Status.IN_PROGRESS, null, TODAY.plusDays(days), false, TODAY.plusDays(dependentDays));

        assertThat(assess(facts)).isEqualTo(new Assessment(priority, reason));
    }

    @Test
    void anUndatedEntryThatSomethingSoonWaitsForIsHigh() {
        Facts facts = new Facts(Kind.PLAN_ITEM, Status.NOT_STARTED, null, null, false, TODAY.plusDays(10));

        assertThat(assess(facts)).isEqualTo(new Assessment(Priority.HIGH, Reason.NEEDED_BY_ANOTHER));
    }

    @ParameterizedTest(name = "started {0} days ago, ends in {1} days, status {2}: {3} ({4})")
    @CsvSource({
        // Should have started (the start is in the past) and nobody has begun: one level up.
        "1, 100, NOT_STARTED, NORMAL,   SHOULD_HAVE_STARTED",
        "1,  40, NOT_STARTED, HIGH,     SHOULD_HAVE_STARTED",
        "1,  10, NOT_STARTED, CRITICAL, SHOULD_HAVE_STARTED",
        "1,   2, NOT_STARTED, CRITICAL, DUE_WITHIN_3_DAYS",
        // Starting today or later is not late.
        "0,  40, NOT_STARTED, NORMAL,   DUE_WITHIN_60_DAYS",
        // Once someone has begun, only the end date counts.
        "9,  40, IN_PROGRESS, NORMAL,   DUE_WITHIN_60_DAYS",
    })
    void aPlanItemThatShouldHaveStarted(int startedDaysAgo, int endsInDays, Status status, Priority priority, Reason reason) {
        Facts facts = new Facts(Kind.PLAN_ITEM, status, TODAY.minusDays(startedDaysAgo), TODAY.plusDays(endsInDays), false, null);

        assertThat(assess(facts)).isEqualTo(new Assessment(priority, reason));
    }

    @Test
    void whatIsDoneOrDroppedNeedsNoAttentionHoweverLate() {
        for (Status finished : new Status[] {Status.DONE, Status.DROPPED}) {
            Facts overdue = new Facts(Kind.PLAN_ITEM, finished, TODAY.minusDays(30), TODAY.minusDays(10), true, TODAY.minusDays(1));

            assertThat(assess(overdue)).isEqualTo(new Assessment(Priority.LOW, Reason.FINISHED));
        }
    }

    private static Facts task(Integer dueInDays) {
        return new Facts(Kind.TASK, Status.NOT_STARTED, null, dueInDays == null ? null : TODAY.plusDays(dueInDays), false, null);
    }

    private static Assessment assess(Facts facts) {
        return PriorityRule.assess(facts, TODAY);
    }
}
