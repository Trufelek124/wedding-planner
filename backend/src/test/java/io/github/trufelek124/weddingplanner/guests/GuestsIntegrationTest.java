package io.github.trufelek124.weddingplanner.guests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.PostgresIntegrationTest;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.TestWedding;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The guest list, its households and the head count. Every guest here is made up. */
class GuestsIntegrationTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    TestWedding wedding;
    TestHttp http;
    String anna;
    String piotr;

    @BeforeEach
    void aFreshWedding() throws Exception {
        wedding = TestWedding.fresh(jdbc, port);
        http = wedding.http;
        anna = wedding.anna;
        piotr = wedding.piotr;
    }

    // ---------------------------------------------------------------- guests

    @Test
    void everyFieldOfAGuestComesBackAsSaved() throws Exception {
        String family = id(household("The Example family"));
        Map<String, Object> request = guest("  Babcia Zosia ");
        request.put("sideMemberId", wedding.annaId);
        request.put("householdId", family);
        request.put("status", "CONFIRMED");
        request.put("plusOne", true);
        request.put("children", 2);
        request.put("dietaryNeeds", "No nuts");
        request.put("needsAccommodation", true);
        request.put("phone", "+48 600 000 001");
        request.put("email", "zosia@example.org");
        request.put("notes", "Arrives by train on Friday.");

        Map<String, Object> saved = created(request);
        Map<String, Object> read = ok(http.getAs(piotr, "/api/guests/" + id(saved)));

        assertThat(read).isEqualTo(saved);
        assertThat(read)
                .containsEntry("name", "Babcia Zosia")
                .containsEntry("sideMemberId", wedding.annaId)
                .containsEntry("householdId", family)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("plusOne", true)
                .containsEntry("children", 2)
                .containsEntry("dietaryNeeds", "No nuts")
                .containsEntry("needsAccommodation", true)
                .containsEntry("phone", "+48 600 000 001")
                .containsEntry("email", "zosia@example.org")
                .containsEntry("notes", "Arrives by train on Friday.")
                // Herself, her plus-one and two children.
                .containsEntry("heads", 4)
                .containsEntry("version", 0);
    }

    @Test
    void aGuestNeedsOnlyANameAndAStatus() throws Exception {
        Map<String, Object> saved = created(guest("Wujek Staszek"));

        assertThat(saved)
                .containsEntry("status", "NOT_INVITED")
                .containsEntry("sideMemberId", null)
                .containsEntry("householdId", null)
                .containsEntry("plusOne", false)
                .containsEntry("children", 0)
                .containsEntry("dietaryNeeds", "")
                .containsEntry("needsAccommodation", false)
                .containsEntry("phone", "")
                .containsEntry("email", "")
                .containsEntry("notes", "")
                .containsEntry("heads", 1);
    }

    @Test
    void theListIsByNameAndNarrowsDownByStatusSideAndHousehold() throws Exception {
        String family = id(household("The Example family"));
        created(with(guest("Zenon"), "sideMemberId", wedding.piotrId, "status", "INVITED"));
        created(with(guest("ala"), "sideMemberId", wedding.annaId, "status", "CONFIRMED", "householdId", family));
        created(with(guest("Bolek"), "status", "DECLINED"));
        created(with(guest("Celina"), "sideMemberId", wedding.annaId, "status", "INVITED", "householdId", family));

        assertThat(names("")).containsExactly("ala", "Bolek", "Celina", "Zenon");
        assertThat(names("?status=INVITED")).containsExactly("Celina", "Zenon");
        assertThat(names("?status=INVITED&status=CONFIRMED")).containsExactly("ala", "Celina", "Zenon");
        assertThat(names("?sideMemberId=" + wedding.annaId)).containsExactly("ala", "Celina");
        assertThat(names("?bothSides=true")).containsExactly("Bolek");
        assertThat(names("?householdId=" + family)).containsExactly("ala", "Celina");
        assertThat(names("?withoutHousehold=true")).containsExactly("Bolek", "Zenon");
        assertThat(names("?sideMemberId=" + wedding.annaId + "&status=INVITED")).containsExactly("Celina");
    }

    @Test
    void aGuestsStatusIsChangedInOneActionWhateverElseIsBeingEdited() throws Exception {
        Map<String, Object> saved = created(with(guest("Ciocia Hela"), "status", "INVITED"));

        Map<String, Object> confirmed = ok(http.putJsonAs(piotr, "/api/guests/" + id(saved) + "/status", "{\"status\":\"CONFIRMED\"}"));

        assertThat(confirmed).containsEntry("status", "CONFIRMED").containsEntry("name", "Ciocia Hela").containsEntry("version", 1);
        // Anna, who still has the first version on her screen, learns of it when she saves.
        HttpResponse<String> late = http.putJsonAs(anna, "/api/guests/" + id(saved), TestHttp.jsonOf(with(change(saved), "notes", "Likes to dance")));
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat((String) TestHttp.json(late).get("type")).endsWith(":changed-meanwhile");
        assertThat(ok(http.getAs(anna, "/api/guests/" + id(saved)))).containsEntry("status", "CONFIRMED").containsEntry("notes", "");
    }

    @Test
    void aChangeIsBasedOnAVersion() throws Exception {
        Map<String, Object> saved = created(guest("Wujek Staszek"));

        Map<String, Object> changed = ok(http.putJsonAs(anna, "/api/guests/" + id(saved),
                TestHttp.jsonOf(with(change(saved), "plusOne", true, "children", 1, "status", "MAYBE"))));

        assertThat(changed).containsEntry("plusOne", true).containsEntry("children", 1).containsEntry("status", "MAYBE")
                .containsEntry("heads", 3).containsEntry("version", 1);
    }

    @Test
    void whatCannotBeAGuestIsRefusedWithWordsForThePerson() throws Exception {
        String nobody = "00000000-0000-4000-8000-000000000000";

        assertThat(refused(guest(" "))).containsExactly("name: Enter a name.");
        assertThat(refused(with(guest("A"), "status", null)))
                .containsExactly("status: Say where this guest stands: not invited yet, invited, confirmed, declined or maybe.");
        assertThat(refused(with(guest("A"), "children", -1))).containsExactly("children: Enter a number from 0 to 20.");
        assertThat(refused(with(guest("A"), "children", 21))).containsExactly("children: Enter a number from 0 to 20.");
        assertThat(refused(with(guest("A"), "children", null))).containsExactly("children: Enter a number from 0 to 20.");
        assertThat(refused(with(guest("A"), "plusOne", null))).containsExactly("plusOne: Say yes or no.");
        assertThat(refused(with(guest("A"), "sideMemberId", nobody))).containsExactly("sideMemberId: Choose one of you, or both.");
        assertThat(refused(with(guest("A"), "householdId", nobody))).containsExactly("householdId: Choose one of the households.");
        assertThat(refused(with(guest("A"), "email", "zosia at example")))
                .containsExactly("email: Enter an e-mail address like name@example.com, or leave it empty.");
        assertThat(names("")).isEmpty();
    }

    @Test
    void aGuestIsDeletedAndIsThenNotFound() throws Exception {
        String id = id(created(guest("Wujek Staszek")));

        assertThat(http.deleteAs(anna, "/api/guests/" + id).statusCode()).isEqualTo(204);

        assertThat(http.getAs(anna, "/api/guests/" + id).statusCode()).isEqualTo(404);
        assertThat(http.deleteAs(anna, "/api/guests/" + id).statusCode()).isEqualTo(404);
        assertThat(http.putJsonAs(anna, "/api/guests/" + id + "/status", "{\"status\":\"INVITED\"}").statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- households

    @Test
    void householdsAreAddedRenamedAndListedByNameWithTheirGuests() throws Exception {
        String family = id(household("the Example family"));
        String couple = id(household("Ala and Bolek"));
        created(with(guest("Babcia Zosia"), "householdId", family));
        created(with(guest("Dziadek Jan"), "householdId", family));

        Map<String, Object> renamed = ok(http.putJsonAs(anna, "/api/households/" + family, "{\"name\":\" The Example family \"}"));

        assertThat(renamed).containsEntry("name", "The Example family").containsEntry("guests", 2);
        List<Map<String, Object>> all = TestHttp.jsonList(http.getAs(piotr, "/api/households"));
        assertThat(all).extracting(one -> one.get("name")).containsExactly("Ala and Bolek", "The Example family");
        assertThat(all).extracting(one -> one.get("guests")).containsExactly(0, 2);
        assertThat(all.get(0)).containsEntry("id", couple);
    }

    @Test
    void aHouseholdNameIsNeededAndTakenOnlyOnce() throws Exception {
        household("The Example family");

        assertThat(TestHttp.fieldMessages(http.postJsonAs(anna, "/api/households", "{\"name\":\"the example FAMILY\"}")))
                .containsExactly("name: There is a household of that name already.");
        assertThat(TestHttp.fieldMessages(http.postJsonAs(anna, "/api/households", "{\"name\":\"  \"}"))).containsExactly("name: Enter a name.");
    }

    @Test
    void aHouseholdThatIsRemovedLeavesItsGuestsOnTheList() throws Exception {
        String family = id(household("The Example family"));
        String zosia = id(created(with(guest("Babcia Zosia"), "householdId", family, "status", "CONFIRMED")));

        assertThat(http.deleteAs(anna, "/api/households/" + family).statusCode()).isEqualTo(204);

        assertThat(ok(http.getAs(anna, "/api/guests/" + zosia))).containsEntry("householdId", null).containsEntry("status", "CONFIRMED");
        assertThat(TestHttp.jsonList(http.getAs(anna, "/api/households"))).isEmpty();
        assertThat(http.deleteAs(anna, "/api/households/" + family).statusCode()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- the head count

    @Test
    void anEmptyListCountsNobodyButNamesEveryStatusAndEverySide() throws Exception {
        Map<String, Object> counts = ok(http.getAs(anna, "/api/guests/counts"));

        assertThat(counts).containsOnlyKeys("total", "byStatus", "bySide");
        assertThat(counts.get("total")).isEqualTo(Map.of("guests", 0, "plusOnes", 0, "children", 0, "heads", 0));
        assertThat(list(counts, "byStatus")).extracting(one -> one.get("status"))
                .containsExactly("NOT_INVITED", "INVITED", "CONFIRMED", "DECLINED", "MAYBE");
        // Anna, Piotr, and the guests of both.
        assertThat(list(counts, "bySide")).extracting(one -> one.get("sideMemberId")).containsExactly(wedding.annaId, wedding.piotrId, null);
        assertThat(list(list(counts, "bySide").get(0), "byStatus")).hasSize(5);
    }

    @Test
    void theHeadCountAddsPlusOnesAndChildrenPerStatusAndPerSide() throws Exception {
        created(with(guest("Babcia Zosia"), "sideMemberId", wedding.annaId, "status", "CONFIRMED", "plusOne", true, "children", 2));
        created(with(guest("Wujek Staszek"), "sideMemberId", wedding.annaId, "status", "CONFIRMED"));
        created(with(guest("Ciocia Hela"), "sideMemberId", wedding.piotrId, "status", "INVITED", "plusOne", true));
        created(with(guest("Kuzyn Marek"), "sideMemberId", wedding.piotrId, "status", "DECLINED", "children", 3));
        created(with(guest("Ala from work"), "status", "MAYBE", "plusOne", true));

        Map<String, Object> counts = ok(http.getAs(piotr, "/api/guests/counts"));

        assertThat(counts.get("total")).isEqualTo(Map.of("guests", 5, "plusOnes", 3, "children", 5, "heads", 13));
        assertThat(count(list(counts, "byStatus"), "status", "CONFIRMED")).isEqualTo(Map.of("guests", 2, "plusOnes", 1, "children", 2, "heads", 5));
        assertThat(count(list(counts, "byStatus"), "status", "INVITED")).isEqualTo(Map.of("guests", 1, "plusOnes", 1, "children", 0, "heads", 2));
        assertThat(count(list(counts, "byStatus"), "status", "DECLINED")).isEqualTo(Map.of("guests", 1, "plusOnes", 0, "children", 3, "heads", 4));
        assertThat(count(list(counts, "byStatus"), "status", "NOT_INVITED")).isEqualTo(Map.of("guests", 0, "plusOnes", 0, "children", 0, "heads", 0));

        Map<String, Object> annas = one(list(counts, "bySide"), "sideMemberId", wedding.annaId);
        assertThat(annas.get("count")).isEqualTo(Map.of("guests", 2, "plusOnes", 1, "children", 2, "heads", 5));
        assertThat(count(list(annas, "byStatus"), "status", "CONFIRMED")).isEqualTo(Map.of("guests", 2, "plusOnes", 1, "children", 2, "heads", 5));
        Map<String, Object> piotrs = one(list(counts, "bySide"), "sideMemberId", wedding.piotrId);
        assertThat(piotrs.get("count")).isEqualTo(Map.of("guests", 2, "plusOnes", 1, "children", 3, "heads", 6));
        assertThat(count(list(piotrs, "byStatus"), "status", "DECLINED")).isEqualTo(Map.of("guests", 1, "plusOnes", 0, "children", 3, "heads", 4));
        Map<String, Object> boths = one(list(counts, "bySide"), "sideMemberId", null);
        assertThat(boths.get("count")).isEqualTo(Map.of("guests", 1, "plusOnes", 1, "children", 0, "heads", 2));
    }

    @Test
    void theHeadCountFollowsAnAnswerAtOnce() throws Exception {
        String hela = id(created(with(guest("Ciocia Hela"), "status", "INVITED", "plusOne", true)));

        assertThat(http.putJsonAs(anna, "/api/guests/" + hela + "/status", "{\"status\":\"CONFIRMED\"}").statusCode()).isEqualTo(200);

        Map<String, Object> counts = ok(http.getAs(anna, "/api/guests/counts"));
        assertThat(count(list(counts, "byStatus"), "status", "CONFIRMED")).isEqualTo(Map.of("guests", 1, "plusOnes", 1, "children", 0, "heads", 2));
        assertThat(count(list(counts, "byStatus"), "status", "INVITED")).isEqualTo(Map.of("guests", 0, "plusOnes", 0, "children", 0, "heads", 0));
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> guest(String name) {
        Map<String, Object> guest = new HashMap<>();
        guest.put("name", name);
        guest.put("sideMemberId", null);
        guest.put("householdId", null);
        guest.put("status", "NOT_INVITED");
        guest.put("plusOne", false);
        guest.put("children", 0);
        guest.put("dietaryNeeds", "");
        guest.put("needsAccommodation", false);
        guest.put("phone", "");
        guest.put("email", "");
        guest.put("notes", "");
        return guest;
    }

    private static Map<String, Object> with(Map<String, Object> request, Object... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            request.put((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return request;
    }

    /** What a client sends to change a guest it has read: every field, and the version it read. */
    private static Map<String, Object> change(Map<String, Object> saved) {
        Map<String, Object> request = new HashMap<>();
        for (String field : List.of("name", "sideMemberId", "householdId", "status", "plusOne", "children", "dietaryNeeds",
                "needsAccommodation", "phone", "email", "notes", "version")) {
            request.put(field, saved.get(field));
        }
        return request;
    }

    private Map<String, Object> created(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/guests", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private Map<String, Object> household(String name) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/households", TestHttp.jsonOf(Map.of("name", name)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return TestHttp.json(response);
    }

    private List<String> refused(Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http.postJsonAs(anna, "/api/guests", TestHttp.jsonOf(request));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return TestHttp.fieldMessages(response);
    }

    private List<String> names(String query) throws Exception {
        HttpResponse<String> response = http.getAs(anna, "/api/guests" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.jsonList(response).stream().map(one -> (String) one.get("name")).toList();
    }

    private static Map<String, Object> ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> from, String field) {
        return (List<Map<String, Object>>) from.get(field);
    }

    private static Map<String, Object> one(List<Map<String, Object>> lines, String field, Object value) {
        return lines.stream().filter(line -> java.util.Objects.equals(line.get(field), value)).findFirst().orElseThrow();
    }

    private static Object count(List<Map<String, Object>> lines, String field, Object value) {
        return one(lines, field, value).get("count");
    }

    private static String id(Map<String, Object> saved) {
        return (String) saved.get("id");
    }
}
