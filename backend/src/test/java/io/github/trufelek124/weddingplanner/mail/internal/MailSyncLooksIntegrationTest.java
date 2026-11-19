package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Call;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a look notes, and when it counts: all against a made-up mailbox. Everything here is invented. */
class MailSyncLooksIntegrationTest extends MailSyncTestBase {

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
        box.deliver(id, "thread-" + id, date, "INBOX", "UNREAD");
    }

    // ---------------------------------------------------------------- the first look

    @Test
    void theFirstLookNotesTheReceivedMessagesOfTheLastNinetyDaysAndNoneOlder() {
        received("m-day-1", daysAgo(1));
        received("m-day-89", daysAgo(89));
        received("m-day-91", daysAgo(91));
        received("m-day-400", daysAgo(400));
        // Archived mail is still received mail: it has no INBOX label.
        box.deliver("m-archived", "thread-m-archived", daysAgo(30));

        assertThat(sync.lookAtAll()).isEqualTo(1);

        assertThat(noted(anna)).containsExactly("m-archived", "m-day-1", "m-day-89");
    }

    @Test
    void theFirstLookLeavesOutWhatWasSentDraftsSpamTheBinAndChats() {
        received("m-received", daysAgo(2));
        box.deliver("m-sent", "thread-a", daysAgo(2), "SENT");
        box.deliver("m-sent-and-inbox", "thread-a", daysAgo(2), "SENT", "INBOX");
        box.deliver("m-draft", "thread-b", daysAgo(2), "DRAFT");
        box.deliver("m-spam", "thread-c", daysAgo(2), "SPAM");
        box.deliver("m-bin", "thread-d", daysAgo(2), "TRASH");
        box.deliver("m-chat", "thread-e", daysAgo(2), "CHAT");

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-received");
    }

    @Test
    void theFirstLookReadsEveryPageAndTakesTheMarkerBeforeTheFirstOfThem() {
        for (int i = 1; i <= 7; i++) {
            received("m-page-" + i, daysAgo(i));
        }
        box.pageSize(3);
        Instant before = TestClock.now();

        sync.lookAtAll();

        assertThat(noted(anna)).hasSize(7);
        List<Call> calls = google.calls().stream().filter(call -> call.path().startsWith("/gmail/")).toList();
        // The connection asked for the address; then the marker, and only then the pages: 3 + 3 + 1 messages.
        assertThat(calls.stream().map(Call::path).toList())
                .containsExactly(PROFILE, PROFILE, MESSAGES, MESSAGES, MESSAGES);
        assertThat(calls.get(2).form()).doesNotContainKey("pageToken");
        assertThat(calls.get(3).form()).containsKey("pageToken");
        assertThat(marker(anna)).isEqualTo(box.marker());
        assertThat(lastLook(anna)).isBetween(before, TestClock.now());
    }

    @Test
    void theQueryAsksForTheLastNinetyDaysAndLeavesOutWhatIsNotReceived() {
        Instant before = TestClock.now();

        sync.lookAtAll();

        Map<String, String> asked = google.calls(MESSAGES).get(0).form();
        assertThat(asked.get("q"))
                .contains("-in:sent", "-in:drafts", "-in:spam", "-in:trash", "-in:chats");
        long after = Long.parseLong(asked.get("q").split(" ")[0].substring("after:".length()));
        assertThat(Instant.ofEpochSecond(after)).isBetween(before.minus(Duration.ofDays(90)).minusSeconds(2), TestClock.now().minus(Duration.ofDays(90)));
    }

    @Test
    void anEmptyMailboxIsALookTooAndCounts() {
        sync.lookAtAll();

        assertThat(noted(anna)).isEmpty();
        assertThat(marker(anna)).isEqualTo(box.marker());
        assertThat(lastLook(anna)).isNotNull();
    }

    // ---------------------------------------------------------------- a later look

    @Test
    void aLaterLookNotesOnlyWhatWasAddedSinceAndLeavesOutTheSameKinds() {
        received("m-old", daysAgo(3));
        sync.lookAtAll();
        String markerAfterFirst = marker(anna);
        int listsBefore = google.count(MESSAGES);

        received("m-new-1", hoursAgo(1));
        box.deliver("m-new-sent", "thread-s", hoursAgo(1), "SENT");
        box.deliver("m-new-draft", "thread-d", hoursAgo(1), "DRAFT");
        box.deliver("m-new-spam", "thread-p", hoursAgo(1), "SPAM", "UNREAD");
        box.deliver("m-new-bin", "thread-t", hoursAgo(1), "TRASH");
        box.deliver("m-new-chat", "thread-c", hoursAgo(1), "CHAT");
        received("m-new-2", hoursAgo(1));
        box.pageSize(2);
        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-new-1", "m-new-2", "m-old");
        assertThat(google.count(MESSAGES)).as("the history is asked, not the whole list").isEqualTo(listsBefore);
        assertThat(google.calls(HISTORY).get(0).form()).containsEntry("startHistoryId", markerAfterFirst);
        assertThat(marker(anna)).isEqualTo(box.marker()).isNotEqualTo(markerAfterFirst);
    }

    @Test
    void aLookWithNothingNewStillCountsAndKeepsTheMarkerCurrent() {
        received("m-old", daysAgo(3));
        sync.lookAtAll();
        Instant first = lastLook(anna);
        TestClock.advance(Duration.ofMinutes(5));

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-old");
        assertThat(lastLook(anna)).isAfter(first);
        assertThat(marker(anna)).isEqualTo(box.marker());
    }

    // ---------------------------------------------------------------- never twice

    @Test
    void lookingTwiceNotesNothingTwice() {
        received("m-1", daysAgo(3));
        received("m-2", daysAgo(2));
        sync.lookAtAll();
        sync.lookAtAll();
        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-1", "m-2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message", Integer.class)).isEqualTo(2);
    }

    @Test
    void aMessageTheFirstLookAndTheHistoryBothNameIsStoredOnce() {
        received("m-both", hoursAgo(2));
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-both");
        // As when the message arrived while the first look was under way: the marker is from before it.
        jdbc.update("UPDATE mail_connection SET sync_marker = ?", "7391000");

        sync.lookAtAll();

        assertThat(google.calls(HISTORY)).as("the history named it").isNotEmpty();
        assertThat(noted(anna)).containsExactly("m-both");
        assertThat(messageRepository.count(anna)).isEqualTo(1);
    }

    @Test
    void theStoreItselfNotesAMessageOnceHoweverOftenItIsToldOfIt() {
        received("m-1", daysAgo(1));
        sync.lookAtAll();
        UUID owner = anna;
        Instant connectedAt = connections.find(owner).orElseThrow().connectedAt();
        MailSource.Noted again = new MailSource.Noted("m-1", "thread-m-1");

        int added = messageRepository.note(owner, connectedAt, List.of(again, again), TestClock.now(), null).orElseThrow();

        assertThat(added).isZero();
        assertThat(messageRepository.count(owner)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- a look counts only when complete

    @Test
    void aFirstLookThatIsCutOffLeavesNoMarkerAndIsDoneAgain() {
        for (int i = 1; i <= 5; i++) {
            received("m-" + i, daysAgo(i));
        }
        box.pageSize(2);
        google.answerNextCallsToWith(MESSAGES, Reply.normally(), Reply.slow(2_600));

        sync.lookAtAll();

        assertThat(marker(anna)).isNull();
        assertThat(lastLook(anna)).isNull();
        assertThat(noted(anna)).as("what was found is kept as it comes").hasSize(2);
        assertThat(refusals(anna)).as("a cut off is no refusal").isZero();

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-1", "m-2", "m-3", "m-4", "m-5");
        assertThat(marker(anna)).isEqualTo(box.marker());
        assertThat(lastLook(anna)).isNotNull();
    }

    @Test
    void aLaterLookThatIsCutOffLeavesTheMarkerAndTheTimeWhereTheyWere() {
        received("m-0", daysAgo(1));
        sync.lookAtAll();
        String marker = marker(anna);
        Instant lookedAt = lastLook(anna);
        for (int i = 1; i <= 5; i++) {
            received("m-" + i, hoursAgo(i));
        }
        box.pageSize(2);
        google.answerNextCallsToWith(HISTORY, Reply.normally(), Reply.slow(2_600));
        TestClock.advance(Duration.ofMinutes(5));

        sync.lookAtAll();

        assertThat(marker(anna)).isEqualTo(marker);
        assertThat(lastLook(anna)).isEqualTo(lookedAt);
        assertThat(noted(anna)).as("the first page was stored as it came").hasSize(3);

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-0", "m-1", "m-2", "m-3", "m-4", "m-5");
        assertThat(marker(anna)).isEqualTo(box.marker()).isNotEqualTo(marker);
        assertThat(lastLook(anna)).isAfter(lookedAt);
    }

    @Test
    void aLookThatIsTooSlowIsCutOffToo() {
        received("m-1", daysAgo(1));
        google.answerNextCallsToWith(MESSAGES, Reply.slow(2_600));

        sync.lookAtAll();

        assertThat(marker(anna)).isNull();
        assertThat(noted(anna)).isEmpty();
        assertThat(refusals(anna)).isZero();
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-1");
    }

    // ---------------------------------------------------------------- the marker too old

    @Test
    void aMarkerThatHasGrownTooOldIsReplacedByALookByDateFromTheDayBeforeTheLastCompleteLook() {
        received("m-before", daysAgo(10));
        sync.lookAtAll();
        Instant lastComplete = lastLook(anna);
        TestClock.advance(Duration.ofDays(4));
        Instant dayBefore = lastComplete.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        // Inside the days that are looked through again, and just outside them.
        received("m-inside", dayBefore.plusSeconds(60));
        received("m-after", TestClock.now().minus(Duration.ofHours(3)));
        box.deliver("m-outside", "thread-outside", dayBefore.minusSeconds(60), "INBOX");
        box.deliver("m-sent", "thread-sent", dayBefore.plusSeconds(60), "SENT");
        box.forgetHistory();
        int profilesBefore = google.count(PROFILE);

        sync.lookAtAll();

        assertThat(noted(anna)).containsExactly("m-after", "m-before", "m-inside");
        assertThat(google.calls(HISTORY)).as("history was tried first").hasSize(1);
        Map<String, String> listed = google.calls(MESSAGES).get(google.count(MESSAGES) - 1).form();
        assertThat(listed.get("q")).startsWith("after:" + dayBefore.getEpochSecond() + " ");
        assertThat(google.count(PROFILE)).as("the marker is taken afresh, first").isEqualTo(profilesBefore + 1);
        assertThat(marker(anna)).isEqualTo(box.marker());
        assertThat(lastLook(anna)).isAfter(lastComplete);

        // And from there on the history is good again.
        received("m-later", TestClock.now());
        sync.lookAtAll();
        assertThat(noted(anna)).contains("m-later");
        assertThat(google.calls(HISTORY)).hasSize(2);
    }

    // ---------------------------------------------------------------- the owners' mailboxes

    @Test
    void twoMailboxesAreLookedAtOneAfterTheOther() throws Exception {
        connectPiotr();
        received("m-anna-1", daysAgo(1));
        received("m-anna-2", daysAgo(2));
        piotrsBox().deliver("m-piotr-1", "thread-piotr", daysAgo(1), "INBOX");
        box.pageSize(1);
        int before = google.calls().size();

        assertThat(sync.lookAtAll()).isEqualTo(2);

        assertThat(noted(anna)).containsExactly("m-anna-1", "m-anna-2");
        assertThat(noted(piotr())).containsExactly("m-piotr-1");
        List<String> whoAsked = google.calls().stream().skip(before)
                .filter(call -> call.path().startsWith("/gmail/") && call.authorization() != null)
                .map(Call::authorization).toList();
        // Anna's token for all her calls, and only then Piotr's: never interleaved.
        List<String> distinctInOrder = whoAsked.stream().distinct().toList();
        assertThat(distinctInOrder).hasSize(2);
        assertThat(whoAsked.subList(0, whoAsked.lastIndexOf(distinctInOrder.get(0)) + 1)).containsOnly(distinctInOrder.get(0));
    }

    @Test
    void aLookWhileAnotherIsRunningDoesNotStartASecond() throws Exception {
        received("m-1", daysAgo(1));
        google.answerNextCallsToWith(MESSAGES, Reply.slow(1_200));

        CompletableFuture<Integer> running = CompletableFuture.supplyAsync(sync::lookAtAll);
        long waited = System.nanoTime();
        while (google.count(MESSAGES) == 0 && System.nanoTime() - waited < TimeUnit.SECONDS.toNanos(5)) {
            Thread.sleep(10);
        }
        int callsWhileRunning = google.calls().size();

        assertThat(sync.lookAtAll()).as("not started a second time").isZero();
        assertThat(google.calls()).hasSize(callsWhileRunning);
        assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(noted(anna)).containsExactly("m-1");
        assertThat(google.count(MESSAGES)).isEqualTo(1);
        // And once it has ended, the next look can start.
        assertThat(sync.lookAtAll()).isEqualTo(1);
    }

    @Test
    void disconnectingRemovesTheNotedMessagesAndOnlyTheirs() throws Exception {
        connectPiotr();
        received("m-anna", daysAgo(1));
        piotrsBox().deliver("m-piotr", "thread-piotr", daysAgo(1), "INBOX");
        sync.lookAtAll();
        assertThat(noted(anna)).hasSize(1);

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(noted(anna)).isEmpty();
        assertThat(noted(piotr())).containsExactly("m-piotr");
        assertThat(yours(mail(wedding.piotr))).containsEntry("messagesNoted", 1);
    }

    @Test
    void connectingAnotherMailboxForgetsTheNotedMessagesAndTheMarker() throws Exception {
        connectPiotr();
        received("m-anna", daysAgo(1));
        piotrsBox().deliver("m-piotr", "thread-piotr", daysAgo(1), "INBOX");
        sync.lookAtAll();
        assertThat(noted(anna)).hasSize(1);

        assertThat(location(connect(wedding.anna, "anna.other@planner.example"))).isEqualTo(YES);

        assertThat(noted(anna)).isEmpty();
        assertThat(marker(anna)).isNull();
        assertThat(lastLook(anna)).isNull();
        assertThat(noted(piotr())).containsExactly("m-piotr");
        google.mailbox("anna.other@planner.example").deliver("m-other", "thread-other", daysAgo(1), "INBOX");
        sync.lookAtAll();
        assertThat(noted(anna)).containsExactly("m-other");
    }

    @Test
    void connectingTheSameMailboxAgainKeepsWhatWasNoted() throws Exception {
        received("m-anna", daysAgo(1));
        sync.lookAtAll();
        String marker = marker(anna);

        assertThat(location(connect(wedding.anna, ANNA_MAILBOX))).isEqualTo(YES);

        assertThat(noted(anna)).containsExactly("m-anna");
        assertThat(marker(anna)).isEqualTo(marker);
    }

    @Test
    void aLookThatMeetsAnotherMailboxConnectedMeanwhileStoresNothingOfTheOldOne() throws Exception {
        received("m-old-box", daysAgo(1));
        ConnectionRepository.Connection began = connections.find(anna).orElseThrow();
        assertThat(location(connect(wedding.anna, "anna.other@planner.example"))).isEqualTo(YES);

        var stored = messageRepository.note(anna, began.connectedAt(), List.of(new MailSource.Noted("m-late", "thread-late")),
                TestClock.now(), new MessageRepository.Completion("1", TestClock.now()));

        assertThat(stored).isEmpty();
        assertThat(noted(anna)).isEmpty();
        assertThat(marker(anna)).isNull();
    }

    // ---------------------------------------------------------------- what the page says

    @Test
    @SuppressWarnings("unchecked")
    void theStateSaysHowManyMessagesAreNotedAndWhenTheServerLastLooked() throws Exception {
        assertThat(yours(mail(wedding.anna))).containsEntry("messagesNoted", 0).containsEntry("lastLookAt", null);
        received("m-1", daysAgo(1));
        received("m-2", daysAgo(2));
        received("m-3", daysAgo(3));
        sync.lookAtAll();

        Map<String, Object> mine = yours(mail(wedding.anna));

        assertThat(mine).containsEntry("messagesNoted", 3);
        assertThat(Instant.parse((String) mine.get("lastLookAt"))).isEqualTo(lastLook(anna));
        // The partner sees that the server looked, and neither how many nor what.
        Map<String, Object> partner = partner(mail(wedding.piotr));
        assertThat(partner.keySet()).containsExactlyInAnyOrder("joined", "state", "lastLookAt");
        assertThat(Instant.parse((String) partner.get("lastLookAt"))).isEqualTo(lastLook(anna));
        assertThat(yours(mail(wedding.piotr))).containsEntry("messagesNoted", 0);
    }

    // ---------------------------------------------------------------- what is kept

    @Test
    void ofAMessageTheTableHoldsTheTwoIdentifiersAndNothingElse() {
        box.deliver("m-secret-looking", "thread-secret-looking", daysAgo(1), "INBOX", "IMPORTANT", "CATEGORY_UPDATES", "Label_17");
        sync.lookAtAll();

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'mail_message' ORDER BY column_name", String.class);
        assertThat(columns).containsExactlyInAnyOrder("member_id", "message_id", "thread_id", "noted_at", "read_by_import");
        String row = jdbc.queryForObject("SELECT row_to_json(t)::text FROM mail_message t", String.class);
        assertThat(row).contains("m-secret-looking").contains("thread-secret-looking");
        assertThat(row).doesNotContain("INBOX").doesNotContain("IMPORTANT").doesNotContain("CATEGORY").doesNotContain("Label_17")
                .doesNotContain("UNREAD").doesNotContain(ANNA_MAILBOX);
        assertThat(jdbc.queryForObject("SELECT read_by_import FROM mail_message", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT thread_id FROM mail_message", String.class)).isEqualTo("thread-secret-looking");
    }

    @Test
    void theSyncAsksGoogleForNoMoreThanTheIdentifiers() {
        received("m-1", daysAgo(1));
        sync.lookAtAll();
        received("m-2", hoursAgo(1));
        sync.lookAtAll();

        for (Call call : google.calls()) {
            assertThat(call.path()).as("a call to Google").isIn("/token", PROFILE, MESSAGES, HISTORY);
            assertThat(call.form().keySet()).doesNotContain("format", "fields", "metadataHeaders");
        }
        assertThat(google.calls(MESSAGES)).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
    }
}
