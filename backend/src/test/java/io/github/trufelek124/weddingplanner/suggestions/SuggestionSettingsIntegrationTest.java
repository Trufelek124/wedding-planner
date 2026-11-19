package io.github.trufelek124.weddingplanner.suggestions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.suggestions.Proposal.Kind;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** S05 section 6: how sure the import has to be, one setting for the wedding. */
class SuggestionSettingsIntegrationTest extends AddedByItselfTestBase {

    @Test
    void theSettingsBeginOnAt85AndBothFieldsAreAlwaysThere() throws Exception {
        assertThat(ok(http.getAs(anna, "/api/suggestions/settings"))).containsOnlyKeys("addsByItself", "threshold")
                .containsEntry("addsByItself", true).containsEntry("threshold", 85);
        assertThat(ok(http.getAs(piotr, "/api/suggestions/settings"))).containsEntry("addsByItself", true).containsEntry("threshold", 85);
    }

    @Test
    void oneOwnerChangesThemAndTheOtherReadsTheSame() throws Exception {
        Map<String, Object> changed = ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":70}"));

        assertThat(changed).containsEntry("addsByItself", true).containsEntry("threshold", 70);
        assertThat(ok(http.getAs(piotr, "/api/suggestions/settings"))).containsEntry("threshold", 70);
        assertThat(ok(http.putJsonAs(piotr, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":50}")))
                .containsEntry("threshold", 50);
        assertThat(ok(http.getAs(anna, "/api/suggestions/settings"))).containsEntry("threshold", 50);
        assertThat(ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":100}")))
                .containsEntry("threshold", 100);
    }

    @Test
    void switchingItOffKeepsTheNumberForWhenItIsSwitchedOnAgain() throws Exception {
        http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":65}");

        Map<String, Object> off = ok(http.putJsonAs(piotr, "/api/suggestions/settings", "{\"addsByItself\":false,\"threshold\":65}"));

        assertThat(off).containsEntry("addsByItself", false).containsEntry("threshold", 65);
        assertThat(ok(http.getAs(anna, "/api/suggestions/settings"))).containsEntry("addsByItself", false).containsEntry("threshold", 65);
        assertThat(ok(http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":65}")))
                .containsEntry("addsByItself", true);
    }

    @Test
    void aThresholdOutsideFiftyToAHundredIsRefusedWithAMessageNextToTheField() throws Exception {
        for (String body : new String[] {
            "{\"addsByItself\":true,\"threshold\":49}",
            "{\"addsByItself\":true,\"threshold\":101}",
            "{\"addsByItself\":true,\"threshold\":0}",
            "{\"addsByItself\":true,\"threshold\":-5}",
            "{\"addsByItself\":true,\"threshold\":null}",
            "{\"addsByItself\":true}"
        }) {
            HttpResponse<String> response = http.putJsonAs(anna, "/api/suggestions/settings", body);

            assertProblem(response, 400, "bad-request");
            assertThat(TestHttp.fieldMessages(response)).as(body).containsExactly("threshold: Choose a number from 50 to 100.");
        }
        assertProblem(http.putJsonAs(anna, "/api/suggestions/settings", "{\"threshold\":80}"), 400, "bad-request");
        assertThat(ok(http.getAs(anna, "/api/suggestions/settings"))).as("nothing changed").containsEntry("threshold", 85);
    }

    @Test
    void theThresholdDecidesWhatIsHandedInFromThenOn() throws Exception {
        http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":60}");
        UUID at60 = suggestions.handIn(proposal(Kind.TASK, "At sixty", 60));
        UUID at59 = suggestions.handIn(proposal(Kind.TASK, "At fifty-nine", 59));
        http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":100}");
        UUID at99 = suggestions.handIn(proposal(Kind.TASK, "At ninety-nine", 99));
        UUID at100 = suggestions.handIn(proposal(Kind.TASK, "At a hundred", 100));

        assertThat(state(at60)).isEqualTo("ADDED");
        assertThat(state(at59)).isEqualTo("WAITING");
        assertThat(state(at99)).isEqualTo("WAITING");
        assertThat(state(at100)).isEqualTo("ADDED");
    }

    @Test
    void aChangeAddsNothingThatWaitsAndUndoesNothingThatWasAdded() throws Exception {
        UUID waiting = suggestions.handIn(proposal(Kind.TASK, "Waits at seventy", 70));
        UUID added = added("Added at ninety");
        int before = entryCount();

        // Lower: what waits is not added now. Raise it above what was added: it is not undone.
        http.putJsonAs(anna, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":50}");
        assertThat(state(waiting)).isEqualTo("WAITING");
        http.putJsonAs(piotr, "/api/suggestions/settings", "{\"addsByItself\":true,\"threshold\":100}");
        http.putJsonAs(piotr, "/api/suggestions/settings", "{\"addsByItself\":false,\"threshold\":100}");

        assertThat(state(waiting)).isEqualTo("WAITING");
        assertThat(state(added)).isEqualTo("ADDED");
        assertThat(entryCount()).isEqualTo(before);
        assertThat(ids(anna, "?state=ADDED")).containsExactly(added.toString());
    }
}
