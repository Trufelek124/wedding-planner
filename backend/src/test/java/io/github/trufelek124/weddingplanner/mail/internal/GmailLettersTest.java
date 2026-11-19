package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Person;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Text;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** How a made-up message's words are made out of what Gmail gives: encoded headers, senders, HTML and the cut. No server. */
class GmailLettersTest {

    static String b(String charset, String text) {
        return "=?" + charset + "?B?" + Base64.getEncoder().encodeToString(text.getBytes(Charset.forName(charset))) + "?=";
    }

    @Test
    void anEncodedWordIsTurnedIntoTheTextItStandsFor() {
        assertThat(GmailLetters.decodedWords(b("UTF-8", "Zaproszenie na wesele - słuby"))).isEqualTo("Zaproszenie na wesele - słuby");
        assertThat(GmailLetters.decodedWords(b("UTF-8", "Вітаємо"))).isEqualTo("Вітаємо");
        // ISO-8859-2, quoted-printable: "Sala w pałacu" (ł is 0xB3), with an underscore for a blank.
        assertThat(GmailLetters.decodedWords("=?ISO-8859-2?Q?Sala_w_pa=B3acu?=")).isEqualTo("Sala w pałacu");
        assertThat(GmailLetters.decodedWords("=?iso-8859-2?q?Sala_w_pa=b3acu?=")).isEqualTo("Sala w pałacu");
    }

    @Test
    void wordsNextToEachOtherAreOneTextAndBlanksBetweenThemAreLost() {
        String text = GmailLetters.decodedWords(b("UTF-8", "Zaproszenie ") + "\r\n " + b("UTF-8", "na ślub"));
        assertThat(text).isEqualTo("Zaproszenie na ślub");
        // A character cut between two words is whole again.
        byte[] bytes = "ł".getBytes(StandardCharsets.UTF_8);
        String split = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(new byte[] {bytes[0]}) + "?= =?UTF-8?B?"
                + Base64.getEncoder().encodeToString(new byte[] {bytes[1]}) + "?=";
        assertThat(GmailLetters.decodedWords(split)).isEqualTo("ł");
    }

    @Test
    void plainWordsAroundAnEncodedOneStay() {
        assertThat(GmailLetters.decodedWords("Re: " + b("UTF-8", "Ślub") + " (pilne)")).isEqualTo("Re: Ślub (pilne)");
        assertThat(GmailLetters.decodedWords("No words here")).isEqualTo("No words here");
    }

    @Test
    void aWordThatIsNotWhatItSaysStaysAsItWasAndAnUnknownCharsetIsReadAsUtf8() {
        assertThat(GmailLetters.decodedWords("=?UTF-8?B?***?=")).isEqualTo("=?UTF-8?B?***?=");
        assertThat(GmailLetters.decodedWords("=?UTF-8?Q?broken=Z?=")).isEqualTo("=?UTF-8?Q?broken=Z?=");
        assertThat(GmailLetters.decodedWords("=?x-no-such-charset?Q?Sala_=C5=82?=")).isEqualTo("Sala ł");
    }

    @Test
    void aSenderWithAndWithoutAName() {
        Person named = GmailLetters.person("Jan from Palace on the Hill <jan@palace.example>");
        assertThat(named).isEqualTo(new Person("Jan from Palace on the Hill", "jan@palace.example"));
        assertThat(GmailLetters.person("\"Palace, on the Hill\" <jan@palace.example>")).isEqualTo(new Person("Palace, on the Hill", "jan@palace.example"));
        assertThat(GmailLetters.person("jan@palace.example")).isEqualTo(new Person("", "jan@palace.example"));
        assertThat(GmailLetters.person("<jan@palace.example>")).isEqualTo(new Person("", "jan@palace.example"));
        assertThat(GmailLetters.person(b("UTF-8", "Gospoda Łódź") + " <gospoda@lodz.example>"))
                .isEqualTo(new Person("Gospoda Łódź", "gospoda@lodz.example"));
        assertThat(GmailLetters.person("")).isEqualTo(new Person("", ""));
    }

    @Test
    void htmlBecomesTextWithNoMarkupScriptOrStyle() {
        String html = """
                <!DOCTYPE html><html><head><title>Hidden title</title><style>p { color: red }</style>
                <script>var stolen = 'script-secret';</script></head>
                <body><!-- a comment nobody reads -->
                <p>Dzień dobry,</p><p>sala jest wolna <b>19 czerwca</b> &amp; kosztuje 5&nbsp;000 zł.</p>
                <script>alert('inline-script')</script><style>.x{display:none}</style>
                <ul><li>Zaliczka</li><li>Menu</li></ul>
                <table><tr><td>Data</td><td>2027-06-19</td></tr></table>
                <noscript>noscript-secret</noscript><svg><text>svg-secret</text></svg><template>template-secret</template>
                <a href="https://tracking.example/x">link text</a><br>Pozdrawiam<img src="x.png" alt="not text"></body></html>
                """;
        String text = GmailLetters.htmlText(html);
        assertThat(text).contains("Dzień dobry,", "sala jest wolna 19 czerwca & kosztuje 5 000 zł.", "Zaliczka", "Menu", "Data 2027-06-19", "link text", "Pozdrawiam");
        assertThat(text).doesNotContain("<").doesNotContain(">").doesNotContain("script").doesNotContain("color").doesNotContain("Hidden title")
                .doesNotContain("comment").doesNotContain("tracking.example").doesNotContain("display").doesNotContain("not text")
                .doesNotContain("noscript-secret").doesNotContain("svg-secret").doesNotContain("template-secret");
        assertThat(text).contains("Dzień dobry,\n\nsala jest wolna").contains("Zaliczka\nMenu");
    }

    @Test
    void htmlOfNothingButMarkupIsEmpty() {
        assertThat(GmailLetters.htmlText("<html><head><style>a{}</style></head><body><script>x()</script><div> </div></body></html>")).isEmpty();
        assertThat(GmailLetters.htmlText("")).isEmpty();
    }

    @Test
    void plainTextKeepsItsLinesAndLosesRaggedBlanks() {
        assertThat(GmailLetters.plainText("Hello,\r\n\r\n\r\n\r\n  second   line \r\nthird\r")).isEqualTo("Hello,\n\nsecond line\nthird");
    }

    @Test
    void aTextIsCutAtOneHundredThousandCharactersAndTheCutIsSaid() {
        assertThat(GmailLetters.cut("a".repeat(100_000))).isEqualTo(new Text("a".repeat(100_000), false));
        Text cut = GmailLetters.cut("b".repeat(100_001));
        assertThat(cut.text()).hasSize(100_000);
        assertThat(cut.cut()).isTrue();
        // Never in the middle of a character made of two.
        String pairs = "a".repeat(99_999) + "😀😀";
        Text safe = GmailLetters.cut(pairs);
        assertThat(safe.cut()).isTrue();
        assertThat(safe.text()).hasSize(99_999);
        assertThat(Character.isHighSurrogate(safe.text().charAt(safe.text().length() - 1))).isFalse();
    }

    @Test
    void whatCarriesWordsHidesThemFromToString() {
        assertThat(new Person("Secret Name", "secret@x.example").toString()).doesNotContain("Secret").doesNotContain("secret");
        assertThat(new Text("Secret text", false).toString()).doesNotContain("Secret");
        assertThat(new MailSource.Earlier(new Person("S", "s@x.example"), java.time.Instant.EPOCH, new Text("Secret", false)).toString())
                .doesNotContain("Secret").doesNotContain("s@x");
        assertThat(new MailSource.Letter(new Person("S", "s@x.example"), "Secret subject", java.time.Instant.EPOCH, new Text("Secret", false), java.util.List.of()).toString())
                .doesNotContain("Secret").doesNotContain("s@x");
    }
}
