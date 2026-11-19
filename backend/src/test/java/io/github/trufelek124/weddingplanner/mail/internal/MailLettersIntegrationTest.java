package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.TestClock;
import io.github.trufelek124.weddingplanner.mail.MailReading.EarlierMessage;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.Person;
import io.github.trufelek124.weddingplanner.mail.MailReading.Why;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mailbox;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Mime;
import io.github.trufelek124.weddingplanner.mail.StandInGoogle.Reply;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A noted message is read from Gmail with the earlier messages of its thread: what is in it, what is left out and what
 * happens when it cannot be had. Everything is made up and nothing goes to the real Google.
 */
class MailLettersIntegrationTest extends MailLettersTestBase {

    Mailbox box;

    @BeforeEach
    void annaIsConnected() throws Exception {
        connectAnna();
        box = annasBox();
    }

    private final Instant base = TestClock.now().truncatedTo(ChronoUnit.MILLIS);

    private Instant at(long daysAgo) {
        return base.minus(Duration.ofDays(daysAgo));
    }

    // ---------------------------------------------------------------- what a message says

    @Test
    void aPlainTextMessageIsReadWithItsSenderSubjectDateAndText() {
        Instant arrived = at(2);
        box.write("m-1", "t-1", arrived, JAN, "Wolny termin 19 czerwca", plain("Dzień dobry,\nsala jest wolna 19 czerwca.\n\nJan"), "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.from()).isEqualTo(new Person("Jan from Palace on the Hill", "jan@palace-on-the-hill.example"));
        assertThat(letter.subject()).isEqualTo("Wolny termin 19 czerwca");
        assertThat(letter.arrived()).as("Gmail's own date, not the Date header").isEqualTo(arrived);
        assertThat(letter.text().text()).isEqualTo("Dzień dobry,\nsala jest wolna 19 czerwca.\n\nJan");
        assertThat(letter.text().cut()).isFalse();
        assertThat(letter.earlier()).isEmpty();
        // One call for the thread, in full, and nothing else.
        assertThat(google.calls(THREADS)).hasSize(1);
        assertThat(google.calls(THREADS).getFirst().form()).containsEntry("format", "full").containsEntry("id", "t-1");
        assertThat(google.count(MESSAGES)).isZero();
        assertThat(google.count(BELOW_A_MESSAGE)).isZero();
    }

    @Test
    void anHtmlOnlyMessageIsReadAsTextWithNoMarkupScriptOrStyle() {
        String html = "<html><head><style>p{color:red}</style><script>track('html-secret')</script></head><body>"
                + "<p>Вітаємо!</p><p>Зал вільний <b>19 червня</b>.</p>"
                + "<script>alert(1)</script><!-- hidden --></body></html>";
        box.write("m-1", "t-1", at(1), JAN, "Hi", Mime.html(html), "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.text().text()).isEqualTo("Вітаємо!\n\nЗал вільний 19 червня.");
        assertThat(letter.text().text()).doesNotContain("<").doesNotContain("script").doesNotContain("html-secret").doesNotContain("color").doesNotContain("hidden");
    }

    @Test
    void whereThereAreBothThePlainPartWins() {
        box.write("m-1", "t-1", at(1), JAN, "Hi",
                Mime.multipart("alternative", plain("The plain words"), Mime.html("<p>The <i>html</i> words</p>")), "INBOX");

        assertThat(letterOf("m-1", "t-1").text().text()).isEqualTo("The plain words");
    }

    @Test
    void partsAreReadThroughNestedMultiparts() {
        Mime body = Mime.multipart("mixed",
                Mime.multipart("related", Mime.multipart("alternative", Mime.html("<p>deep html</p>"), plain("deep plain")),
                        Mime.file("logo.png", "image/png", "att-logo")),
                Mime.file("contract.pdf", "application/pdf", "att-pdf"));
        box.write("m-1", "t-1", at(1), JAN, "Hi", body, "INBOX");

        assertThat(letterOf("m-1", "t-1").text().text()).isEqualTo("deep plain");
    }

    @Test
    void htmlInsideNestedMultipartsIsReadWhenThereIsNoPlainPart() {
        box.write("m-1", "t-1", at(1), JAN, "Hi",
                Mime.multipart("mixed", Mime.multipart("alternative", Mime.html("<div>only <b>html</b></div>"))), "INBOX");

        assertThat(letterOf("m-1", "t-1").text().text()).isEqualTo("only html");
    }

    @Test
    void anAttachmentIsSkippedWhateverItsTypeAndNeverAskedFor() {
        Mime body = Mime.multipart("mixed",
                plain("See the offer."),
                Mime.file("offer.pdf", "application/pdf", "att-1"),
                Mime.inlineFile("notes.txt", "text/plain", "FILE-TEXT-NOT-THE-MESSAGE"),
                Mime.inlineFile("page.html", "text/html", "<p>FILE-HTML-NOT-THE-MESSAGE</p>"),
                plain("DISPOSED-NOT-THE-MESSAGE").with("Content-Disposition", "attachment"));
        box.write("m-1", "t-1", at(1), JAN, "Offer", body, "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.text().text()).isEqualTo("See the offer.");
        assertThat(google.count(BELOW_A_MESSAGE)).as("no attachment was asked for").isZero();
        assertThat(google.count(MESSAGES)).isZero();
        assertThat(google.calls(THREADS)).hasSize(1);
    }

    @Test
    void aMessageThatIsOnlyAnAttachmentHasNoText() {
        box.write("m-1", "t-1", at(1), JAN, "File", Mime.multipart("mixed", Mime.inlineFile("notes.txt", "text/plain", "FILE-TEXT")), "INBOX");
        box.write("m-2", "t-2", at(1), JAN, "File", Mime.file("a.txt", "text/plain", "att-2"), "INBOX");

        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.NO_TEXT);
        assertThat(whyNot("m-2", "t-2")).isEqualTo(Why.NO_TEXT);
        assertThat(google.count(BELOW_A_MESSAGE)).isZero();
    }

    @Test
    void aPartsCharsetIsHonouredAndAnUnknownOneIsReadAsUtf8() {
        box.write("m-pl", "t-pl", at(3), JAN, "Pl", Mime.plain("Zaproszenie na ślub: sala w pałacu, złożymy zaliczkę.", "ISO-8859-2"), "INBOX");
        box.write("m-ua", "t-ua", at(2), JAN, "Ua", Mime.plain("Запрошення на весілля, українською.", "windows-1251"), "INBOX");
        box.write("m-pl2", "t-pl2", at(2), JAN, "Pl2", Mime.html("<p>Złożymy zaliczkę.</p>", "windows-1250"), "INBOX");
        box.write("m-x", "t-x", at(1), JAN, "X", Mime.plainClaimingCharset("Sala w pałacu – Łódź", "x-no-such-charset"), "INBOX");

        assertThat(letterOf("m-pl", "t-pl").text().text()).isEqualTo("Zaproszenie na ślub: sala w pałacu, złożymy zaliczkę.");
        assertThat(letterOf("m-ua", "t-ua").text().text()).isEqualTo("Запрошення на весілля, українською.");
        assertThat(letterOf("m-pl2", "t-pl2").text().text()).isEqualTo("Złożymy zaliczkę.");
        assertThat(letterOf("m-x", "t-x").text().text()).isEqualTo("Sala w pałacu – Łódź");
    }

    @Test
    void anEncodedSubjectAndSenderAreDecoded() {
        box.write("m-1", "t-1", at(1), word("UTF-8", "Gospoda Łódź") + " <gospoda@lodz.example>",
                word("UTF-8", "Termin na ślub ") + " " + word("UTF-8", "– potwierdzenie"), plain("ok"), "INBOX");
        box.write("m-2", "t-2", at(1), word("UTF-8", "Віктор з будинку") + " <viktor@budynok.example>",
                "=?ISO-8859-2?Q?Rezerwacja_sali_w_pa=B3acu?=", plain("ok"), "INBOX");

        Letter first = letterOf("m-1", "t-1");
        assertThat(first.from()).isEqualTo(new Person("Gospoda Łódź", "gospoda@lodz.example"));
        assertThat(first.subject()).isEqualTo("Termin na ślub – potwierdzenie");
        Letter second = letterOf("m-2", "t-2");
        assertThat(second.from()).isEqualTo(new Person("Віктор з будинку", "viktor@budynok.example"));
        assertThat(second.subject()).isEqualTo("Rezerwacja sali w pałacu");
    }

    @Test
    void aSenderWithoutANameAndAMessageWithoutHeadersAreReadAsTheyAre() {
        box.write("m-1", "t-1", at(1), "registry@city.example", "Akt", plain("ok"), "INBOX");
        box.write("m-2", "t-2", at(1), null, null, plain("no headers at all"), "INBOX");

        assertThat(letterOf("m-1", "t-1").from()).isEqualTo(new Person("", "registry@city.example"));
        Letter bare = letterOf("m-2", "t-2");
        assertThat(bare.from()).isEqualTo(new Person("", ""));
        assertThat(bare.subject()).isEmpty();
        assertThat(bare.text().text()).isEqualTo("no headers at all");
    }

    // ---------------------------------------------------------------- the thread

    @Test
    void theEarlierMessagesOfTheThreadComeOldestFirstWithTheOwnersOwnReplyAndWithoutDraftsSpamBinOrLaterOnes() {
        // Written in no order: the thread's order is by arrival.
        box.write("m-2", "t-1", at(4), "Anna <" + MAILBOX_OF_ANNA + ">", "Re: Wolny termin", plain("Czy można 19 czerwca?"), "SENT");
        box.write("m-draft", "t-1", at(3), "Anna <" + MAILBOX_OF_ANNA + ">", "Re: Wolny termin", plain("DRAFT-WORDS"), "DRAFT");
        box.write("m-bin", "t-1", at(3), JAN, "Re: Wolny termin", plain("BIN-WORDS"), "TRASH");
        box.write("m-spam", "t-1", at(3), JAN, "Re: Wolny termin", plain("SPAM-WORDS"), "SPAM");
        box.write("m-1", "t-1", at(5), JAN, "Wolny termin", Mime.html("<p>Mamy <b>wolny</b> termin.</p>"), "INBOX");
        box.write("m-4", "t-1", at(1), JAN, "Re: Wolny termin", plain("LATER-WORDS"), "INBOX");
        box.write("m-3", "t-1", at(2), JAN, "Re: Wolny termin", plain("Tak, 19 czerwca jest wolny."), "INBOX");
        box.write("m-other", "t-other", at(2), JAN, "Other", plain("OTHER-THREAD-WORDS"), "INBOX");

        Letter letter = letterOf("m-3", "t-1");

        assertThat(letter.text().text()).isEqualTo("Tak, 19 czerwca jest wolny.");
        assertThat(letter.earlier()).extracting(EarlierMessage::arrived).containsExactly(at(5), at(4));
        assertThat(letter.earlier().get(0).from()).isEqualTo(new Person("Jan from Palace on the Hill", "jan@palace-on-the-hill.example"));
        assertThat(letter.earlier().get(0).text().text()).isEqualTo("Mamy wolny termin.");
        assertThat(letter.earlier().get(1).from()).as("what the owner sent is the context of a reply").isEqualTo(new Person("Anna", MAILBOX_OF_ANNA));
        assertThat(letter.earlier().get(1).text().text()).isEqualTo("Czy można 19 czerwca?");
        assertThat(letter.earlier()).hasSize(2);
        assertThat(google.calls(THREADS)).hasSize(1);
    }

    private static final String MAILBOX_OF_ANNA = ANNA_MAILBOX;

    @Test
    void anEarlierMessageWithoutTextIsThereWithEmptyTextAndALongOneIsCutAndSaid() {
        box.write("m-1", "t-1", at(3), JAN, "Photos", Mime.file("a.jpg", "image/jpeg", "att-1"), "INBOX");
        box.write("m-2", "t-1", at(2), JAN, "Long", plain("x".repeat(100_050)), "INBOX");
        box.write("m-3", "t-1", at(1), JAN, "Now", plain("short"), "INBOX");

        Letter letter = letterOf("m-3", "t-1");

        assertThat(letter.earlier()).hasSize(2);
        assertThat(letter.earlier().get(0).text().text()).isEmpty();
        assertThat(letter.earlier().get(1).text().text()).hasSize(100_000);
        assertThat(letter.earlier().get(1).text().cut()).isTrue();
        assertThat(letter.earlier().get(0).text().cut()).isFalse();
    }

    @Test
    void aMessageAtTheSameMomentAsAnotherOneKeepsTheThreadsOrder() {
        Instant moment = at(1);
        box.write("m-1", "t-1", moment, JAN, "A", plain("first"), "INBOX");
        box.write("m-2", "t-1", moment, JAN, "B", plain("second"), "INBOX");

        assertThat(letterOf("m-2", "t-1").earlier()).extracting(e -> e.text().text()).containsExactly("first");
        assertThat(letterOf("m-1", "t-1").earlier()).isEmpty();
    }

    // ---------------------------------------------------------------- what is not a letter

    @Test
    void aMessageThatIsGoneIsGone() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");
        box.write("m-bin", "t-bin", at(1), JAN, "Hi", plain("binned"), "TRASH");
        box.write("m-spam", "t-spam", at(1), JAN, "Hi", plain("spammed"), "SPAM");

        assertThat(whyNot("m-1", "t-nowhere")).as("no such thread").isEqualTo(Why.GONE);
        assertThat(whyNot("m-nowhere", "t-1")).as("the thread has no such message").isEqualTo(Why.GONE);
        assertThat(whyNot("m-bin", "t-bin")).as("moved to the bin since").isEqualTo(Why.GONE);
        assertThat(whyNot("m-spam", "t-spam")).as("moved to spam since").isEqualTo(Why.GONE);
    }

    @Test
    void aMessageWithNoTextAtAllHasNoText() {
        box.write("m-1", "t-1", at(1), JAN, "Empty", Mime.html("<html><head><style>p{}</style></head><body><script>x()</script><div> </div></body></html>"), "INBOX");
        box.write("m-2", "t-2", at(1), JAN, "Blank", plain(" \n\n "), "INBOX");
        box.deliver("m-3", "t-3", at(1), "INBOX");

        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.NO_TEXT);
        assertThat(whyNot("m-2", "t-2")).isEqualTo(Why.NO_TEXT);
        assertThat(whyNot("m-3", "t-3")).as("delivered without any parts").isEqualTo(Why.NO_TEXT);
    }

    @Test
    void aThreadBeyondTheLimitIsTooLargeAndIsNotAskedForAgain() {
        // About 2.9 MB once it is base64: more than the 2 MB a thread may be.
        box.write("m-1", "t-1", at(1), JAN, "Huge", plain("y".repeat(2_200_000)), "INBOX");

        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.TOO_LARGE);
        assertThat(google.calls(THREADS)).as("an outcome, not an error to try again").hasSize(1);
    }

    @Test
    void aThreadThatIsLargerThanAListMayBeButWithinTheLimitIsRead() {
        // 300 KB of text: far beyond the 64 KB an ordinary answer may be, within what a thread may.
        box.write("m-1", "t-1", at(1), JAN, "Big", plain("z".repeat(300_000)), "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.text().text()).hasSize(100_000);
        assertThat(letter.text().cut()).as("cut at one hundred thousand characters, and said").isTrue();
    }

    @Test
    void aTextOfExactlyTheLimitIsNotCut() {
        box.write("m-1", "t-1", at(1), JAN, "Edge", plain("e".repeat(100_000)), "INBOX");

        Letter letter = letterOf("m-1", "t-1");

        assertThat(letter.text().text()).hasSize(100_000);
        assertThat(letter.text().cut()).isFalse();
    }

    // ---------------------------------------------------------------- when Google or the mailbox is not well

    @Test
    void aRefusedAccessTokenIsMadeAnewOnceAndTheMessageIsThenRead() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");
        google.answerNextCallsToWith(THREADS, Reply.json(401, "{\"error\":{\"code\":401}}"), Reply.normally());

        assertThat(letterOf("m-1", "t-1").text().text()).isEqualTo("words");
        assertThat(google.calls(THREADS)).hasSize(2);
        assertThat(google.calls(THREADS).get(1).authorization()).isNotEqualTo(google.calls(THREADS).get(0).authorization());
    }

    @Test
    void aTokenRefusedTwiceIsGoogleRefusingForNow() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");
        google.answerNextCallsToWith(THREADS, Reply.json(401, "{}"), Reply.json(401, "{}"));

        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.REFUSED_FOR_NOW);
        assertThat(google.calls(THREADS)).hasSize(2);
    }

    @Test
    void tooManyRequestsAndGooglesOwnFailuresAreRefusedForNow() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");
        for (int status : new int[] {429, 500, 502, 503}) {
            google.answerNextCallsToWith(THREADS, Reply.json(status, "{\"error\":{\"code\":" + status + "}}"));
            assertThat(whyNot("m-1", "t-1")).as("status " + status).isEqualTo(Why.REFUSED_FOR_NOW);
        }
        google.answerNextCallsToWith(THREADS, Reply.json(403, "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));
        assertThat(whyNot("m-1", "t-1")).as("a rate limit as a 403").isEqualTo(Why.REFUSED_FOR_NOW);
        // And afterwards it is fine again.
        assertThat(letterOf("m-1", "t-1").text().text()).isEqualTo("words");
    }

    @Test
    void thePermissionGoneMeansTheMailboxHasToBeConnectedAgain() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");
        google.withdraw(google.refreshTokenOf(ANNA_MAILBOX));

        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.NEEDS_RECONNECTING);
        assertThat(state(anna())).isEqualTo("NEEDS_RECONNECTING");
        assertThat(google.count(THREADS)).as("Google was not asked for the thread").isZero();
        assertThat(whyNot("m-1", "t-1")).isEqualTo(Why.NEEDS_RECONNECTING);
    }

    @Test
    void aMailboxThatIsNotConnectedIsNotReadFrom() throws Exception {
        assertThat(reading.read(piotrs("m-1", "t-1"))).isEqualTo(new io.github.trufelek124.weddingplanner.mail.MailReading.Unavailable(Why.NOT_CONNECTED));
        assertThat(google.count(THREADS)).isZero();
    }

    @Test
    void aSlowAnswerOrOneThatIsNotUsableIsInterrupted() {
        box.write("m-1", "t-1", at(1), JAN, "Hi", plain("words"), "INBOX");

        google.answerNextCallsToWith(THREADS, Reply.slow(2_600));
        assertThat(whyNot("m-1", "t-1")).as("slow").isEqualTo(Why.INTERRUPTED);
        // The client may ask once more when a connection is closed on it: every call is dropped, not one.
        google.answerEveryGmailCallOfWith(ANNA_MAILBOX, Reply.dropped());
        assertThat(whyNot("m-1", "t-1")).as("cut off").isEqualTo(Why.INTERRUPTED);
        google.stopAnsweringGmailCallsOf(ANNA_MAILBOX);
        google.answerNextCallsToWith(THREADS, Reply.text(200, "<html>not json</html>"));
        assertThat(whyNot("m-1", "t-1")).as("not JSON").isEqualTo(Why.INTERRUPTED);
        google.answerNextCallsToWith(THREADS, Reply.json(200, "{\"messages\":\"not a list\"}"));
        assertThat(whyNot("m-1", "t-1")).as("not what a thread looks like").isEqualTo(Why.GONE);
        google.answerNextCallsToWith(THREADS, Reply.redirectTo(google.elsewhere()));
        assertThat(whyNot("m-1", "t-1")).as("a redirect is not followed").isEqualTo(Why.INTERRUPTED);
        assertThat(google.count("/elsewhere")).isZero();
    }

    @Test
    void identifiersThatAreNotGooglesKindAreNeverSentOn() {
        assertThat(whyNot("m 1/../x", "t-1")).isEqualTo(Why.GONE);
        assertThat(whyNot("m-1", "t/../profile")).isEqualTo(Why.GONE);
        assertThat(google.count(THREADS)).isZero();
    }
}
