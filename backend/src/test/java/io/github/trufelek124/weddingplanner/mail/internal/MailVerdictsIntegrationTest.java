package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Counts;
import io.github.trufelek124.weddingplanner.mail.MailReading.CouldNotBeRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.Keys;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotAboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Reason;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which noted messages wait, newest first; what marking one does; the counts; the key a message is named by. Two owners,
 * their made-up mailboxes. Nothing goes to the real Google.
 */
class MailVerdictsIntegrationTest extends MailLettersTestBase {

    private List<String> waitingIds(int most) {
        return reading.waiting(most).stream().map(MessageHandle::messageId).toList();
    }

    /** Anna looks first (a2 newer than a1), then Piotr (p2, p1), then Anna again with what came since (a3, a4). */
    private void threeLooks() throws Exception {
        connectAnna();
        annasBox().pageSize(1);
        annasBox().deliver("a1", "ta1", daysAgo(3), "INBOX");
        annasBox().deliver("a2", "ta2", daysAgo(2), "INBOX");
        sync.lookAtAll();
        connectPiotr();
        piotrsBox().pageSize(1);
        piotrsBox().deliver("p1", "tp1", daysAgo(5), "INBOX");
        piotrsBox().deliver("p2", "tp2", daysAgo(4), "INBOX");
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        annasBox().deliver("a3", "ta3", hoursAgo(3), "INBOX");
        annasBox().deliver("a4", "ta4", hoursAgo(2), "INBOX");
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
    }

    // ---------------------------------------------------------------- what waits

    @Test
    void theNewestWaitFirstAcrossBothMailboxesAndAcrossLooksWhateverTheMessagesOwnDatesSay() throws Exception {
        threeLooks();

        // Newest look first; inside the first look and Piotr's, the order Google listed in (newest first); inside the
        // history look, what was added last is the newest.
        assertThat(waitingIds(100)).containsExactly("a4", "a3", "p2", "p1", "a2", "a1");
        assertThat(reading.waiting(100)).allSatisfy(handle -> assertThat(handle.weddingId()).isEqualTo(weddingId()));
        assertThat(reading.waiting(100).stream().map(MessageHandle::memberId).toList())
                .containsExactly(anna(), anna(), piotr(), piotr(), anna(), anna());
        assertThat(reading.waiting(100).stream().map(MessageHandle::threadId).toList()).containsExactly("ta4", "ta3", "tp2", "tp1", "ta2", "ta1");
    }

    @Test
    void atMostSoManyAreHandedOutAndTheRestStayInOrder() throws Exception {
        threeLooks();

        assertThat(waitingIds(1)).containsExactly("a4");
        assertThat(waitingIds(4)).containsExactly("a4", "a3", "p2", "p1");
        assertThatThrownBy(() -> reading.waiting(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reading.waiting(-3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMessageWithAVerdictIsNeverHandedOutAgain() throws Exception {
        threeLooks();
        List<MessageHandle> waiting = reading.waiting(100);

        assertThat(reading.mark(waiting.get(0), new NotAboutTheWedding())).isTrue();
        assertThat(reading.mark(waiting.get(3), new AboutTheWedding(2))).isTrue();
        assertThat(reading.mark(waiting.get(5), new CouldNotBeRead(Reason.GONE))).isTrue();

        assertThat(waitingIds(100)).containsExactly("a3", "p2", "a2");
        // Not by a later look either, which notes the same message again.
        annasBox().deliver("a5", "ta5", hoursAgo(1), "INBOX");
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();
        assertThat(waitingIds(100)).containsExactly("a5", "a3", "p2", "a2");
    }

    @Test
    void aMailboxThatHasToBeConnectedAgainHasNothingToHandOutButStillCounts() throws Exception {
        threeLooks();
        google.withdraw(google.refreshTokenOf(PIOTR_MAILBOX));
        TestClock.advance(Duration.ofHours(1));
        sync.lookAtAll();
        assertThat(state(piotr())).isEqualTo("NEEDS_RECONNECTING");

        assertThat(waitingIds(100)).containsExactly("a4", "a3", "a2", "a1");
        assertThat(reading.counts(weddingId()).waiting()).isEqualTo(6);
    }

    // ---------------------------------------------------------------- what marking does

    @Test
    void eachVerdictIsStoredWithItsOwnDetailAndCounted() throws Exception {
        threeLooks();
        List<MessageHandle> waiting = reading.waiting(100);
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(6, 0, 0, 0, 0));

        reading.mark(waiting.get(0), new NotAboutTheWedding());
        reading.mark(waiting.get(1), new AboutTheWedding(3));
        reading.mark(waiting.get(2), new AboutTheWedding(0));
        reading.mark(waiting.get(3), new CouldNotBeRead(Reason.DECLINED));
        reading.mark(waiting.get(4), new CouldNotBeRead(Reason.TOO_MANY_PROPOSALS));

        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(1, 5, 2, 2, 3));
        assertThat(stored("a4")).containsEntry("verdict", "NOT_WEDDING").containsEntry("unread_reason", null).containsEntry("proposals", null);
        assertThat(stored("a3")).containsEntry("verdict", "WEDDING").containsEntry("unread_reason", null).containsEntry("proposals", 3);
        assertThat(stored("p2")).containsEntry("verdict", "WEDDING").containsEntry("proposals", 0);
        assertThat(stored("p1")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "DECLINED").containsEntry("proposals", null);
        assertThat(stored("a2")).containsEntry("verdict", "UNREADABLE").containsEntry("unread_reason", "TOO_MANY_PROPOSALS");
        assertThat(stored("a1")).containsEntry("verdict", null).containsEntry("read_at", null);
        assertThat(stored("a4").get("read_at")).as("when it was read").isNotNull();
    }

    @Test
    void everyReasonCanBeStored() throws Exception {
        threeLooks();
        for (Reason reason : Reason.values()) {
            MessageHandle message = reading.waiting(1).getFirst();
            assertThat(reading.mark(message, new CouldNotBeRead(reason))).isTrue();
            assertThat(stored(message.messageId())).containsEntry("unread_reason", reason.name());
        }
        assertThat(reading.counts(weddingId()).couldNotBeRead()).isEqualTo(Reason.values().length);
    }

    @Test
    void markingTwiceChangesNothing() throws Exception {
        threeLooks();
        MessageHandle message = reading.waiting(1).getFirst();

        assertThat(reading.mark(message, new AboutTheWedding(2))).isTrue();
        Map<String, Object> once = stored(message.messageId());
        TestClock.advance(Duration.ofMinutes(10));
        assertThat(reading.mark(message, new AboutTheWedding(2))).isFalse();
        assertThat(reading.mark(message, new NotAboutTheWedding())).isFalse();
        assertThat(reading.mark(message, new CouldNotBeRead(Reason.GONE))).isFalse();

        assertThat(stored(message.messageId())).isEqualTo(once);
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(5, 1, 1, 0, 2));
    }

    @Test
    void aNegativeNumberOfProposalsIsNoVerdict() {
        assertThatThrownBy(() -> new AboutTheWedding(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CouldNotBeRead(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTableRefusesAVerdictThatIsNotWhole() throws Exception {
        threeLooks();
        for (String update : List.of(
                "verdict = 'MAYBE', read_at = now()",
                "verdict = 'WEDDING', proposals = -1, read_at = now()",
                "verdict = 'WEDDING'",
                "verdict = 'WEDDING', proposals = 1",
                "verdict = 'NOT_WEDDING', proposals = 1, read_at = now()",
                "verdict = 'NOT_WEDDING', unread_reason = 'GONE', read_at = now()",
                "verdict = 'UNREADABLE', read_at = now()",
                "verdict = 'UNREADABLE', unread_reason = 'BORED', read_at = now()",
                "verdict = 'UNREADABLE', unread_reason = 'GONE', proposals = 1, read_at = now()",
                "unread_reason = 'GONE'",
                "proposals = 2",
                "read_at = now()")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE mail_message SET " + update + " WHERE message_id = 'a1'"))
                    .as(update).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test
    void aVerdictIsGivenOnlyToTheMessageOfThatMemberAndThatWedding() throws Exception {
        threeLooks();
        MessageHandle message = reading.waiting(1).getFirst();

        assertThat(reading.mark(new MessageHandle(UUID.randomUUID(), message.memberId(), message.messageId(), message.threadId()),
                new NotAboutTheWedding())).as("another wedding").isFalse();
        assertThat(reading.mark(new MessageHandle(message.weddingId(), piotr(), message.messageId(), message.threadId()),
                new NotAboutTheWedding())).as("the other member's mailbox").isFalse();
        assertThat(reading.mark(new MessageHandle(message.weddingId(), message.memberId(), "nobody-knows", "t"),
                new NotAboutTheWedding())).as("a message that is not noted").isFalse();
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(6, 0, 0, 0, 0));
        assertThat(reading.counts(UUID.randomUUID())).as("another wedding has its own count").isEqualTo(new Counts(0, 0, 0, 0, 0));
    }

    @Test
    void disconnectingTakesTheNotesAndTheirVerdictsAndAMessageBeingReadMeanwhileIsMarkedForNothing() throws Exception {
        threeLooks();
        List<MessageHandle> waiting = reading.waiting(100);
        reading.mark(waiting.get(0), new AboutTheWedding(1));
        MessageHandle beingRead = waiting.get(1);

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(reading.mark(beingRead, new AboutTheWedding(1))).as("a no-op, not an error").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_message WHERE member_id = ?", Integer.class, anna())).isZero();
        assertThat(waitingIds(100)).containsExactly("p2", "p1");
        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(2, 0, 0, 0, 0));
    }

    // ---------------------------------------------------------------- the key

    @Test
    void aMessagesKeyIsTheSameForEverDifferentForAnotherAndSaysNothingReadable() throws Exception {
        threeLooks();
        MessageHandle a4 = reading.waiting(1).getFirst();
        Keys first = reading.keysOf(a4);

        assertThat(reading.keysOf(a4)).isEqualTo(first);
        assertThat(reading.keysOf(new MessageHandle(UUID.randomUUID(), a4.memberId(), a4.messageId(), a4.threadId())))
                .as("it does not depend on what the wedding is called here").isEqualTo(first);
        assertThat(first.message()).matches("m1-[A-Za-z0-9_-]{43}").hasSizeLessThanOrEqualTo(100);
        assertThat(first.thread()).matches("t1-[A-Za-z0-9_-]{43}").hasSizeLessThanOrEqualTo(100);
        assertThat(first.message()).isNotEqualTo(first.thread());
        // Identifiers as long as Gmail's own: two letters could stand in a key by chance, sixteen cannot.
        MessageHandle telling = new MessageHandle(a4.weddingId(), a4.memberId(), "18f3c2a9d07b4e61", "18f3c2a9d07b0000");
        Keys ofTelling = reading.keysOf(telling);
        assertThat(ofTelling.message())
                .doesNotContain(telling.messageId()).doesNotContain(telling.threadId()).doesNotContain(a4.memberId().toString());
        assertThat(ofTelling.thread()).doesNotContain(telling.threadId()).doesNotContain(a4.memberId().toString());

        MessageHandle other = reading.waiting(2).get(1);
        assertThat(reading.keysOf(other).message()).isNotEqualTo(first.message());
        assertThat(reading.keysOf(other).thread()).isNotEqualTo(first.thread());
        // The same identifiers in the other owner's mailbox are another message.
        assertThat(reading.keysOf(new MessageHandle(a4.weddingId(), piotr(), a4.messageId(), a4.threadId())).message()).isNotEqualTo(first.message());
        // A message that is told apart from another only by its identifier.
        assertThat(reading.keysOf(new MessageHandle(a4.weddingId(), a4.memberId(), "a4x", a4.threadId())).message()).isNotEqualTo(first.message());
    }

    @Test
    void twoMessagesOfOneThreadShareTheThreadKeyAndAreTold() throws Exception {
        connectAnna();
        MessageHandle first = annas("m-1", "t-1");
        MessageHandle second = annas("m-2", "t-1");
        MessageHandle elsewhere = annas("m-3", "t-2");

        assertThat(reading.keysOf(first).thread()).isEqualTo(reading.keysOf(second).thread());
        assertThat(reading.keysOf(first).message()).isNotEqualTo(reading.keysOf(second).message());
        assertThat(reading.keysOf(elsewhere).thread()).isNotEqualTo(reading.keysOf(first).thread());
    }

    @Test
    void theKeyIsTheSameAfterTheSameMailboxIsConnectedAgain() throws Exception {
        connectAnna();
        annasBox().deliver("a1", "ta1", daysAgo(1), "INBOX");
        sync.lookAtAll();
        Keys before = reading.keysOf(reading.waiting(1).getFirst());

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);
        assertThat(reading.waiting(1)).isEmpty();
        connectAnna();
        TestClock.advance(Duration.ofMinutes(5));
        sync.lookAtAll();

        assertThat(reading.waiting(1)).hasSize(1);
        assertThat(reading.keysOf(reading.waiting(1).getFirst())).isEqualTo(before);
    }

    // ---------------------------------------------------------------- what the module hides

    @Test
    void theHandlesAndTheKeysHideWhatTheyHoldFromToString() throws Exception {
        connectAnna();
        MessageHandle handle = annas("msg-hidden-zq", "thr-hidden-zq");

        assertThat(handle.toString()).doesNotContain("msg-hidden-zq").doesNotContain("thr-hidden-zq");
        assertThat(reading.keysOf(handle).toString()).doesNotContain(reading.keysOf(handle).message());
    }

    // ---------------------------------------------------------------- leaving mailboxes out

    @Test
    void waitingLeavingOutAMailboxHandsOutTheOthersStillNewestFirstUpToMost() throws Exception {
        threeLooks();

        assertThat(reading.waiting(100, List.of(piotr())).stream().map(MessageHandle::messageId).toList())
                .containsExactly("a4", "a3", "a2", "a1");
        assertThat(reading.waiting(3, List.of(piotr())).stream().map(MessageHandle::messageId).toList())
                .as("Piotr's messages do not take places").containsExactly("a4", "a3", "a2");
        assertThat(reading.waiting(100, List.of(anna())).stream().map(MessageHandle::messageId).toList())
                .containsExactly("p2", "p1");
        assertThat(reading.waiting(1, List.of(anna())).stream().map(MessageHandle::messageId).toList()).containsExactly("p2");
        assertThat(reading.waiting(100, List.of(anna(), piotr()))).as("both left out").isEmpty();
        assertThat(reading.waiting(100, List.of(UUID.randomUUID())).stream().map(MessageHandle::messageId).toList())
                .as("a member nobody knows leaves nobody out").containsExactly("a4", "a3", "p2", "p1", "a2", "a1");
        assertThat(reading.waiting(100, List.of())).isEqualTo(reading.waiting(100));
        assertThatThrownBy(() -> reading.waiting(0, List.of(piotr()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leavingAMailboxOutDoesNotBringBackWhatWasMarked() throws Exception {
        threeLooks();
        reading.mark(reading.waiting(1).getFirst(), new NotAboutTheWedding());

        assertThat(reading.waiting(100, List.of(piotr())).stream().map(MessageHandle::messageId).toList())
                .containsExactly("a3", "a2", "a1");
    }

    // ---------------------------------------------------------------- the link to a message

    private static final String ANNAS_ADDRESS_AS_A_VALUE = "anna.mailbox%40planner.example";

    @Test
    void theLinkNamesTheMailboxItIsInWithTheAddressEncodedAsAQueryValue() throws Exception {
        connectAnna();
        connectPiotr();

        assertThat(reading.linkTo(annas("18c0ffee00000001", "t1")))
                .contains("https://mail.google.com/mail/?authuser=" + ANNAS_ADDRESS_AS_A_VALUE + "#all/18c0ffee00000001");
        assertThat(reading.linkTo(piotrs("Ab_-9", "t1")))
                .contains("https://mail.google.com/mail/?authuser=piotr.mailbox%40planner.example#all/Ab_-9");
    }

    @Test
    void anAddressThatNeedsEncodingIsEncoded() throws Exception {
        assertThat(location(connect(wedding.anna, "anna+wedding@planner.example"))).isEqualTo(YES);

        assertThat(reading.linkTo(annas("m-1", "t-1")))
                .contains("https://mail.google.com/mail/?authuser=anna%2Bwedding%40planner.example#all/m-1");
    }

    @Test
    void theLinkPassesTheSuggestionsOwnCheckForAGmailLink() throws Exception {
        connectAnna();

        String link = reading.linkTo(annas("18c0ffee00000001", "t1")).orElseThrow();

        // The suggestions module accepts an https address at exactly mail.google.com, no user, no port, no spaces.
        java.net.URI uri = new java.net.URI(link);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getRawAuthority()).isEqualTo("mail.google.com");
        assertThat(uri.getUserInfo()).isNull();
        assertThat(uri.getPort()).isEqualTo(-1);
        assertThat(link).doesNotContainPattern("[\\s\\p{Cntrl}\\\\]").hasSizeLessThanOrEqualTo(2_000);
    }

    @Test
    void thereIsNoLinkWithoutAMailboxOrAfterItWasDisconnected() throws Exception {
        connectAnna();

        assertThat(reading.linkTo(piotrs("m-1", "t-1"))).as("Piotr has none connected").isEmpty();
        assertThat(reading.linkTo(annas("m-1", "t-1"))).isPresent();

        assertThat(http.deleteAs(wedding.anna, "/api/mail/gmail/connection").statusCode()).isEqualTo(204);

        assertThat(reading.linkTo(annas("m-1", "t-1"))).as("disconnected meanwhile").isEmpty();
    }

    @Test
    void aMailboxThatHasToBeConnectedAgainStillHasItsAddressForTheLink() throws Exception {
        threeLooks();
        google.withdraw(google.refreshTokenOf(PIOTR_MAILBOX));
        TestClock.advance(Duration.ofHours(1));
        sync.lookAtAll();
        assertThat(state(piotr())).isEqualTo("NEEDS_RECONNECTING");

        assertThat(reading.linkTo(piotrs("p1", "tp1")))
                .contains("https://mail.google.com/mail/?authuser=piotr.mailbox%40planner.example#all/p1");
    }

    @Test
    void anIdentifierThatGmailDoesNotGiveOutMakesNoLink() throws Exception {
        connectAnna();

        for (String identifier : List.of(
                "a/b", "a#b", "a b", "a?b", "a&b", "a%2Fb", "../x", "a\nb", "ąę", "", " ", "a.b", "x".repeat(65))) {
            assertThat(reading.linkTo(annas(identifier, "t-1"))).as("identifier " + identifier.length() + " characters").isEmpty();
        }
        assertThat(reading.linkTo(annas("x".repeat(64), "t-1"))).as("the longest there is").isPresent();
        assertThat(reading.linkTo(annas("A", "t-1"))).isPresent();
        assertThat(reading.linkTo(annas(null, "t-1"))).isEmpty();
    }

    @Test
    void nothingButTheMessageAndTheMailboxGoesIntoTheLink() throws Exception {
        connectAnna();

        String link = reading.linkTo(new MessageHandle(UUID.randomUUID(), anna(), "m-1", "thread-that-is-not-in-the-link")).orElseThrow();

        assertThat(link).doesNotContain("thread").doesNotContain(anna().toString());
    }

    // ---------------------------------------------------------------- the count of proposals

    @Test
    void theProposalsAddUpOverTheMessagesThatWereAboutTheWeddingOnly() throws Exception {
        threeLooks();
        List<MessageHandle> waiting = reading.waiting(100);

        reading.mark(waiting.get(0), new AboutTheWedding(2));
        reading.mark(waiting.get(1), new AboutTheWedding(3));
        reading.mark(waiting.get(2), new AboutTheWedding(0));
        reading.mark(waiting.get(3), new NotAboutTheWedding());
        reading.mark(waiting.get(4), new CouldNotBeRead(Reason.TOO_MANY_PROPOSALS));

        assertThat(reading.counts(weddingId())).isEqualTo(new Counts(1, 5, 3, 1, 5));
        assertThat(reading.counts(UUID.randomUUID()).proposals()).as("another wedding").isZero();
    }

    private Map<String, Object> stored(String messageId) {
        return jdbc.queryForMap("SELECT verdict, unread_reason, proposals, read_at FROM mail_message WHERE message_id = ?", messageId);
    }
}
