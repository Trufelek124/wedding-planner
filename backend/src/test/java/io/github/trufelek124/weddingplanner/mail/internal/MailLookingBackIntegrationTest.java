package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.TestHttp;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Call;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Looking back through a mailbox, asked for by its owner (S03 section 5, D-009): which days, which messages, in what
 * order, how it is counted, when it is done, and what Google refusing or a restart does to it. Everything is a made-up
 * mailbox; the round of the sync is run by the test.
 */
class MailLookingBackIntegrationTest extends MailSyncTestBase {

    private static final Reply TOO_MANY = Reply.json(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}");

    UUID anna;
    Mailbox box;

    @BeforeEach
    void annaIsConnectedToAMailbox() throws Exception {
        anna = anna();
        box = annasBox();
        box.markerIs(7_391_000);
        connectAnna();
    }

    private void received(String id, Instant date) {
        box.deliver(id, "thread-" + id, date, "INBOX");
    }

    private HttpResponse<String> ask(String accessToken, Object days) throws Exception {
        return http.postJsonAs(accessToken, "/api/mail/gmail/look-back", "{\"days\":" + days + "}");
    }

    private Map<String, Object> asked(String accessToken, int days) throws Exception {
        HttpResponse<String> response = ask(accessToken, days);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return TestHttp.json(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lookBackOf(Map<String, Object> state) {
        return (Map<String, Object>) yours(state).get("lookBack");
    }

    private Map<String, Object> lookBackOf(String accessToken) throws Exception {
        return lookBackOf(mail(accessToken));
    }

    /** Anna's noted messages, the way the reading will take them: the newest first. */
    private List<String> inTheOrderOfReading() {
        return messageRepository.waiting(100).stream().map(MessageRepository.Waiting::messageId).toList();
    }

    private Instant askedAt(String accessToken) throws Exception {
        return Instant.parse((String) lookBackOf(accessToken).get("askedAt"));
    }

    // ---------------------------------------------------------------- the days asked for

    @Test
    void theDaysAskedForAreLookedThroughAndNotADayMoreWhateverTheClockSaysByThen() throws Exception {
        received("m-in-29d23h", TestClock.now().minus(Duration.ofDays(29)).minus(Duration.ofHours(23)));
        received("m-in-1h", hoursAgo(1));
        received("m-out-30d1h", TestClock.now().minus(Duration.ofDays(30)).minus(Duration.ofHours(1)));
        received("m-out-200d", daysAgo(200));
        asked(wedding.anna, 30);
        Instant askedAt = askedAt(wedding.anna);
        // Ten days pass before the sync gets to it: what was asked for was the last thirty days of the asking.
        TestClock.advance(Duration.ofDays(10));

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-in-1h", "m-in-29d23h");
        Map<String, String> listed = google.calls(MESSAGES).get(0).form();
        assertThat(listed.get("q")).startsWith("after:" + askedAt.minus(Duration.ofDays(30)).getEpochSecond() + " ");
        assertThat(listed.get("q")).contains("-in:sent", "-in:drafts", "-in:spam", "-in:trash", "-in:chats");
    }

    @Test
    void sevenDaysAndNinetyDaysAreTakenAndTheEdgeIsExact() throws Exception {
        received("m-6d23h", TestClock.now().minus(Duration.ofDays(6)).minus(Duration.ofHours(23)));
        received("m-7d1h", TestClock.now().minus(Duration.ofDays(7)).minus(Duration.ofHours(1)));
        received("m-89d23h", TestClock.now().minus(Duration.ofDays(89)).minus(Duration.ofHours(23)));
        received("m-90d1h", TestClock.now().minus(Duration.ofDays(90)).minus(Duration.ofHours(1)));
        asked(wedding.anna, 7);
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-6d23h");

        asked(wedding.anna, 90);
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-6d23h", "m-7d1h", "m-89d23h");
    }

    @Test
    void whatIsNotReceivedIsLeftOutOfALookBackToo() throws Exception {
        received("m-received", daysAgo(2));
        box.deliver("m-sent", "thread-a", daysAgo(2), "SENT");
        box.deliver("m-draft", "thread-b", daysAgo(2), "DRAFT");
        box.deliver("m-spam", "thread-c", daysAgo(2), "SPAM");
        box.deliver("m-bin", "thread-d", daysAgo(2), "TRASH");
        box.deliver("m-chat", "thread-e", daysAgo(2), "CHAT");
        box.deliver("m-archived", "thread-f", daysAgo(2));
        asked(wedding.anna, 30);

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-archived", "m-received");
    }

    // ---------------------------------------------------------------- the order, the pages, the count

    @Test
    void theNewestAreNotedFirstAndReadFirstPageByPage() throws Exception {
        for (int i = 7; i >= 1; i--) {
            received("m-" + i + "d", daysAgo(i));
        }
        box.pageSize(3);
        asked(wedding.anna, 30);

        sync.lookAtAll();

        assertThat(inTheOrderOfReading()).containsExactly("m-1d", "m-2d", "m-3d", "m-4d", "m-5d", "m-6d", "m-7d");
        List<Call> lists = google.calls(MESSAGES);
        assertThat(lists).hasSize(3);
        assertThat(lists.get(0).form()).doesNotContainKey("pageToken");
        assertThat(lists.get(1).form()).containsKey("pageToken");
        assertThat(lists.get(2).form()).containsKey("pageToken");
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("found", 7);
    }

    @Test
    void mailThatCameAfterTheFirstLookIsReadBeforeWhatIsLookedBackFor() throws Exception {
        received("m-old", daysAgo(10));
        sync.lookAtAll();
        received("m-new", hoursAgo(1));
        asked(wedding.anna, 30);

        sync.lookAtAll();

        assertThat(inTheOrderOfReading()).hasSize(2).containsExactlyInAnyOrder("m-new", "m-old");
        assertThat(lookBackOf(wedding.anna)).as("m-new came by the history, m-old by the look back").containsEntry("found", 1);
    }

    @Test
    void aLookBackIsDoneOnlyWithItsLastPageAndOneThatWasCutOffIsDoneAgainCountingEveryMessageOnce() throws Exception {
        for (int i = 5; i >= 1; i--) {
            received("m-" + i, daysAgo(i));
        }
        box.pageSize(2);
        asked(wedding.anna, 30);
        google.answerNextCallsToWith(MESSAGES, Reply.normally(), Reply.slow(2_600));

        sync.lookAtAll();

        // The first page was stored as it came; the second was too slow, and the look back is not done.
        assertThat(noted(anna)).containsExactly("m-1", "m-2");
        Map<String, Object> halfway = lookBackOf(wedding.anna);
        assertThat(halfway).containsEntry("state", "UNDER_WAY").containsEntry("found", 2);
        assertThat(halfway.get("doneAt")).isNull();
        assertThat(refusals(anna)).as("a cut off is no refusal").isZero();
        int lists = google.count(MESSAGES);

        sync.lookAtAll();

        // Done again from its first page: the two that were noted are met again, and counted once.
        assertThat(google.calls(MESSAGES).get(lists).form()).doesNotContainKey("pageToken");
        assertThat(noted(anna)).containsExactly("m-1", "m-2", "m-3", "m-4", "m-5");
        assertThat(inTheOrderOfReading()).containsExactly("m-1", "m-2", "m-3", "m-4", "m-5");
        Map<String, Object> done = lookBackOf(wedding.anna);
        assertThat(done).containsEntry("state", "DONE").containsEntry("found", 5).containsEntry("days", 30);
        assertThat(Instant.parse((String) done.get("doneAt"))).isAfterOrEqualTo(Instant.parse((String) done.get("askedAt")));
        // And a done one is not looked back again by every round.
        int afterwards = google.count(MESSAGES);
        sync.lookAtAll();
        assertThat(google.count(MESSAGES)).isEqualTo(afterwards);
    }

    @Test
    void storingWhatALookBackFoundNeverMovesTheMarkerOrTheTimeOfTheLastLook() throws Exception {
        sync.lookAtAll();
        String marker = marker(anna);
        Instant lastLook = lastLook(anna);
        asked(wedding.anna, 30);
        ConnectionRepository.Connection began = connections.find(anna).orElseThrow();
        TestClock.advance(Duration.ofMinutes(5));

        var stored = messageRepository.noteLookBack(anna, began.connectedAt(), began.lookBack().look(),
                List.of(new MailSource.Noted("m-1", "thread-1")), TestClock.now(), true);

        assertThat(stored).contains(1);
        assertThat(marker(anna)).isEqualTo(marker);
        assertThat(lastLook(anna)).isEqualTo(lastLook);
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("found", 1);
    }

    // ---------------------------------------------------------------- Google refusing

    @Test
    void whileGoogleRefusesALookBackWaitsWithTheMailboxAndThenIsDone() throws Exception {
        received("m-1", daysAgo(2));
        received("m-2", daysAgo(1));
        sync.lookAtAll();
        asked(wedding.anna, 30);
        google.answerNextCallsToWith(MESSAGES, TOO_MANY);

        sync.lookAtAll();

        assertThat(refusals(anna)).isEqualTo(1);
        Duration pause = Duration.between(TestClock.now(), putOffUntil(anna));
        assertThat(pause).isBetween(Duration.ofMinutes(5).minusSeconds(10), Duration.ofMinutes(5).plusSeconds(1));
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "UNDER_WAY").containsEntry("found", 0);
        assertThat(noted(anna)).isEmpty();
        int calls = google.calls().size();
        assertThat(sync.lookAtAll()).as("not before its time").isZero();
        assertThat(google.calls()).hasSize(calls);

        TestClock.advance(Duration.ofMinutes(6));
        assertThat(sync.lookAtAll()).isEqualTo(1);

        assertThat(noted(anna)).containsExactly("m-1", "m-2");
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("found", 2);
        assertThat(refusals(anna)).isZero();
        assertThat(putOffUntil(anna)).isNull();
    }

    @Test
    void aLookBackThatWasAskedForWhileGoogleWasRefusingIsDoneWhenItStopsAndSurvivesARestart() throws Exception {
        received("m-1", daysAgo(2));
        sync.lookAtAll();
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, TOO_MANY);
        sync.lookAtAll();
        assertThat(putOffUntil(anna)).isNotNull();
        asked(wedding.anna, 30);
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        // What was asked is in the database: a server started afresh, a moment after the pause, does it.
        MailSync restarted = new MailSync(setup, connections, messageRepository, source, clock,
                new org.springframework.core.task.SyncTaskExecutor(), false);
        TestClock.advance(Duration.ofMinutes(6));

        assertThat(restarted.lookAtAll()).isEqualTo(1);

        assertThat(noted(anna)).containsExactly("m-1");
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE");
    }

    @Test
    void aLookBackIsNotAskedOfAMailboxThatHasToBeConnectedAgainAndWaitsUntilItIs() throws Exception {
        received("m-1", daysAgo(2));
        asked(wedding.anna, 30);
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));

        sync.lookAtAll();

        assertThat(state(anna)).isEqualTo("NEEDS_RECONNECTING");
        assertThat(google.count(MESSAGES)).isZero();
        assertThat(noted(anna)).isEmpty();
        assertThat(lookBackOf(wedding.anna)).as("what was asked is still said").containsEntry("state", "UNDER_WAY");
    }

    // ---------------------------------------------------------------- asking again

    @Test
    void askingWhileOneIsUnderWayReplacesItAndStartsAnew() throws Exception {
        for (int i = 5; i >= 1; i--) {
            received("m-" + i, daysAgo(i));
        }
        box.pageSize(2);
        asked(wedding.anna, 7);
        google.answerNextCallsToWith(MESSAGES, Reply.normally(), Reply.slow(2_600));
        sync.lookAtAll();
        assertThat(lookBackOf(wedding.anna)).containsEntry("found", 2).containsEntry("days", 7);

        Map<String, Object> answer = asked(wedding.anna, 60);

        assertThat(lookBackOf(answer)).containsEntry("state", "UNDER_WAY").containsEntry("days", 60).containsEntry("found", 0);
        int lists = google.count(MESSAGES);
        sync.lookAtAll();
        assertThat(google.calls(MESSAGES).get(lists).form()).doesNotContainKey("pageToken");
        assertThat(noted(anna)).containsExactly("m-1", "m-2", "m-3", "m-4", "m-5");
        // Counted from the new ask: the two that were already noted are not counted again.
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("days", 60).containsEntry("found", 3);
    }

    @Test
    void askingForLongerNotesOnlyTheOlderOnesAndNothingTwice() throws Exception {
        received("m-3d", daysAgo(3));
        received("m-20d", daysAgo(20));
        received("m-50d", daysAgo(50));
        asked(wedding.anna, 7);
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-3d");
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("days", 7).containsEntry("found", 1);

        asked(wedding.anna, 30);
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "UNDER_WAY").containsEntry("found", 0).containsEntry("days", 30);
        sync.lookAtAll();
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE").containsEntry("found", 1);

        asked(wedding.anna, 90);
        sync.lookAtAll();
        assertThat(lookBackOf(wedding.anna)).containsEntry("found", 1);

        asked(wedding.anna, 90);
        sync.lookAtAll();
        assertThat(lookBackOf(wedding.anna)).as("nothing left to find").containsEntry("state", "DONE").containsEntry("found", 0);
        assertThat(noted(anna)).containsExactly("m-20d", "m-3d", "m-50d");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message", Integer.class)).isEqualTo(3);
        assertThat(yours(mail(wedding.anna))).containsEntry("messagesNoted", 3);
    }

    @Test
    void aMessageThatWasReadIsNotReadAgainByAskingAgain() throws Exception {
        received("m-1", daysAgo(2));
        asked(wedding.anna, 30);
        sync.lookAtAll();
        jdbc.update("UPDATE mail_message SET verdict = 'NOT_WEDDING', read_at = now() WHERE message_id = 'm-1'");

        asked(wedding.anna, 90);
        sync.lookAtAll();

        assertThat(jdbc.queryForObject("SELECT verdict FROM mail_message WHERE message_id = 'm-1'", String.class)).isEqualTo("NOT_WEDDING");
        assertThat(messageRepository.waiting(100)).isEmpty();
    }

    // ---------------------------------------------------------------- the request

    @Test
    void sixAndNinetyOneAreRefusedWithTheMessageNextToDaysAndNothingIsRecorded() throws Exception {
        for (Object days : List.of(6, 91, 0, -30, 1000)) {
            HttpResponse<String> response = ask(wedding.anna, days);

            assertThat(response.statusCode()).as("days " + days).isEqualTo(400);
            assertThat(TestHttp.fieldMessages(response)).containsExactly("days: Choose a number of days from 7 to 90.");
        }
        HttpResponse<String> none = http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{}");
        assertThat(none.statusCode()).isEqualTo(400);
        assertThat(TestHttp.fieldMessages(none)).containsExactly("days: Choose a number of days from 7 to 90.");
        assertThat(http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":null}").statusCode()).isEqualTo(400);
        assertThat(http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":30.5}").statusCode()).isEqualTo(400);
        assertThat(http.postJsonAs(wedding.anna, "/api/mail/gmail/look-back", "{\"days\":\"thirty\"}").statusCode()).isEqualTo(400);

        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "NONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connection WHERE look_back_days IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void sevenAndNinetyAreTaken() throws Exception {
        assertThat(lookBackOf(asked(wedding.anna, 7))).containsEntry("days", 7);
        assertThat(lookBackOf(asked(wedding.anna, 90))).containsEntry("days", 90);
    }

    @Test
    void withoutAConnectionTheAnswerIsMailNotConnectedAndNothingIsStored() throws Exception {
        HttpResponse<String> response = ask(wedding.piotr, 30);

        assertThat(response.statusCode()).isEqualTo(409);
        Map<String, Object> problem = TestHttp.json(response);
        assertThat(problem).containsEntry("type", "urn:wedding-planner:problem:mail-not-connected");
        assertThat((String) problem.get("detail")).isEqualTo("Your mailbox is not connected, or it has to be connected again. Connect it first.");
        assertThat(lookBackOf(wedding.piotr)).containsEntry("state", "NONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_connection WHERE member_id = ?", Integer.class, piotr())).isZero();
    }

    @Test
    void aConnectionThatHasToBeMadeAgainAnswersMailNotConnectedToo() throws Exception {
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));
        sync.lookAtAll();
        assertThat(state(anna)).isEqualTo("NEEDS_RECONNECTING");

        HttpResponse<String> response = ask(wedding.anna, 30);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(TestHttp.json(response)).containsEntry("type", "urn:wedding-planner:problem:mail-not-connected");
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "NONE");
    }

    @Test
    void theRequestNeedsASignIn() throws Exception {
        assertThat(http.postJson("/api/mail/gmail/look-back", "{\"days\":30}").statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- what is answered

    @Test
    void theAnswerOfTheRequestAndOfTheStateSayTheSameInEveryState() throws Exception {
        assertThat(lookBackOf(wedding.anna)).as("nothing asked").containsExactlyInAnyOrderEntriesOf(
                nullEntries("NONE", 0));
        received("m-1", daysAgo(2));
        received("m-2", daysAgo(3));
        Instant before = TestClock.now();

        Map<String, Object> answer = asked(wedding.anna, 30);

        Map<String, Object> underWay = lookBackOf(answer);
        assertThat(underWay.keySet()).containsExactlyInAnyOrder("state", "days", "askedAt", "doneAt", "found");
        assertThat(underWay).containsEntry("state", "UNDER_WAY").containsEntry("days", 30).containsEntry("found", 0).containsEntry("doneAt", null);
        assertThat(Instant.parse((String) underWay.get("askedAt"))).isBetween(before, TestClock.now());
        assertThat(answer).containsEntry("setUp", true);
        assertThat(answer.keySet()).containsExactlyInAnyOrder("setUp", "yours", "partner", "reading");
        assertThat(yours(answer)).containsEntry("state", "CONNECTED").containsEntry("address", ANNA_MAILBOX);
        assertThat(lookBackOf(wedding.anna)).isEqualTo(underWay);

        sync.lookAtAll();

        Map<String, Object> done = lookBackOf(wedding.anna);
        assertThat(done).containsEntry("state", "DONE").containsEntry("days", 30).containsEntry("found", 2);
        assertThat(done.get("askedAt")).isEqualTo(underWay.get("askedAt"));
        assertThat(Instant.parse((String) done.get("doneAt"))).isBetween(Instant.parse((String) done.get("askedAt")), TestClock.now());
    }

    private static Map<String, Object> nullEntries(String state, int found) {
        Map<String, Object> entries = new java.util.HashMap<>();
        entries.put("state", state);
        entries.put("days", null);
        entries.put("askedAt", null);
        entries.put("doneAt", null);
        entries.put("found", found);
        return entries;
    }

    @Test
    void someoneWhoIsNotConnectedSeesNothingAskedToo() throws Exception {
        assertThat(lookBackOf(wedding.piotr)).containsExactlyInAnyOrderEntriesOf(nullEntries("NONE", 0));
    }

    // ---------------------------------------------------------------- only your own mailbox

    @Test
    void eachOwnerAsksForTheirOwnMailboxOnlyAndNothingInTheRequestNamesAnother() throws Exception {
        connectPiotr();
        received("m-anna", daysAgo(2));
        piotrsBox().deliver("m-piotr", "thread-piotr", daysAgo(2), "INBOX");

        HttpResponse<String> sneaky = http.postJsonAs(wedding.piotr, "/api/mail/gmail/look-back",
                "{\"days\":30,\"memberId\":\"" + anna + "\",\"address\":\"" + ANNA_MAILBOX + "\",\"owner\":\"anna\"}");
        assertThat(sneaky.statusCode()).isEqualTo(200);
        sync.lookAtAll();

        assertThat(noted(piotr())).containsExactly("m-piotr");
        assertThat(noted(anna)).isEmpty();
        assertThat(lookBackOf(wedding.piotr)).containsEntry("state", "DONE").containsEntry("found", 1);
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "NONE");
        // The partner sees that the server looked, and neither a look back nor how far it went.
        assertThat(partner(mail(wedding.anna)).keySet()).containsExactlyInAnyOrder("joined", "state", "lastLookAt");
        assertThat(partner(mail(wedding.piotr)).keySet()).containsExactlyInAnyOrder("joined", "state", "lastLookAt");
        assertThat(google.calls(MESSAGES)).as("only Piotr's mailbox was listed").hasSize(1);

        asked(wedding.anna, 14);
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-anna");
        assertThat(lookBackOf(wedding.piotr)).as("Piotr's is as it was").containsEntry("days", 30).containsEntry("found", 1);
    }

    // ---------------------------------------------------------------- disconnecting and connecting anew

    @Test
    void disconnectingClearsWhatWasAskedAndConnectingAgainStartsWithNewMailOnly() throws Exception {
        received("m-1", daysAgo(2));
        asked(wedding.anna, 30);
        sync.lookAtAll();
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "DONE");

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(lookBackOf(wedding.anna)).containsExactlyInAnyOrderEntriesOf(nullEntries("NONE", 0));
        connectAnna();
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "NONE");
        sync.lookAtAll();
        assertThat(noted(anna)).as("what was in the mailbox is not noted by connecting").isEmpty();
    }

    @Test
    void connectingAnotherMailboxClearsWhatWasAsked() throws Exception {
        received("m-1", daysAgo(2));
        asked(wedding.anna, 30);
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "UNDER_WAY");

        assertThat(location(connect(wedding.anna, "anna.other@planner.example"))).isEqualTo(YES);

        assertThat(lookBackOf(wedding.anna)).containsExactlyInAnyOrderEntriesOf(nullEntries("NONE", 0));
        sync.lookAtAll();
        assertThat(google.count(MESSAGES)).as("nothing is looked back through").isZero();
    }

    @Test
    void connectingTheSameMailboxAgainKeepsWhatWasAskedLikeItKeepsTheMarker() throws Exception {
        received("m-1", daysAgo(2));
        asked(wedding.anna, 30);
        sync.lookAtAll();
        Map<String, Object> before = lookBackOf(wedding.anna);

        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        assertThat(lookBackOf(wedding.anna)).isEqualTo(before);
    }

    @Test
    void aLookBackThatMeetsAConnectionMadeAgainMeanwhileStoresNothing() throws Exception {
        asked(wedding.anna, 30);
        ConnectionRepository.Connection began = connections.find(anna).orElseThrow();
        assertThat(location(connect(wedding.anna, "anna.other@planner.example"))).isEqualTo(YES);

        var stored = messageRepository.noteLookBack(anna, began.connectedAt(), began.lookBack().look(),
                List.of(new MailSource.Noted("m-late", "thread-late")), TestClock.now(), true);

        assertThat(stored).isEmpty();
        assertThat(noted(anna)).isEmpty();
    }

    @Test
    void aLookBackThatMeetsANewAskMeanwhileStoresNothingOfTheOldOne() throws Exception {
        asked(wedding.anna, 30);
        ConnectionRepository.Connection began = connections.find(anna).orElseThrow();
        asked(wedding.anna, 60);

        var stored = messageRepository.noteLookBack(anna, began.connectedAt(), began.lookBack().look(),
                List.of(new MailSource.Noted("m-late", "thread-late")), TestClock.now(), true);

        assertThat(stored).isEmpty();
        assertThat(noted(anna)).isEmpty();
        assertThat(lookBackOf(wedding.anna)).containsEntry("state", "UNDER_WAY").containsEntry("days", 60);
    }

    @Test
    void theTableRefusesWhatCannotBeTrue() {
        ConnectionRepository.Connection connection = connections.find(anna).orElseThrow();
        for (String set : List.of(
                "look_back_days = 6, look_back_asked_at = now(), look_back_from = now() - interval '6 days', look_back_look = 1",
                "look_back_days = 91, look_back_asked_at = now(), look_back_from = now() - interval '91 days', look_back_look = 1",
                "look_back_days = 30",
                "look_back_found = 3",
                "look_back_found = -1",
                "look_back_days = 30, look_back_asked_at = now(), look_back_from = now() + interval '1 day', look_back_look = 1",
                "look_back_done_at = now()")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> jdbc.update("UPDATE mail_connection SET " + set + " WHERE member_id = ?", connection.memberId()))
                    .as(set)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
