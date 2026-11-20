package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Person;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Text;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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
        assertThat(new MailSource.Letter(new Person("S", "s@x.example"), "Secret subject", java.time.Instant.EPOCH, new Text("Secret", false), java.util.List.of(), "secret-id@x.example", java.util.List.of(new MailSource.Pdf("secret-offer.pdf", 99, "att-1"))).toString())
                .doesNotContain("Secret").doesNotContain("s@x").doesNotContain("secret-offer");
        assertThat(new MailSource.Pdf("secret-offer.pdf", 99, "att-secret").toString()).doesNotContain("secret").doesNotContain("99");
        assertThat(new MailSource.Downloaded("%PDF-secret".getBytes()).toString()).doesNotContain("secret").doesNotContain("PDF");
        assertThat(new io.github.trufelek124.weddingplanner.mail.MailReading.Document("secret-offer.pdf", 99, "att-secret").toString())
                .doesNotContain("secret").doesNotContain("99");
        assertThat(new io.github.trufelek124.weddingplanner.mail.MailReading.Pdf("%PDF-secret".getBytes()).toString())
                .doesNotContain("secret").doesNotContain("PDF");
    }

    private static tools.jackson.databind.JsonNode part(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
    }

    @Test
    void aPdfPartIsListedByItsTypeOrItsNameWithAnAttachmentIdentifierAndNothingElseIs() {
        String payload = """
                {"mimeType":"multipart/mixed","parts":[
                  {"mimeType":"text/plain","filename":"","body":{"size":4,"data":"dGVzdA"}},
                  {"mimeType":"application/pdf","filename":"A.pdf","body":{"attachmentId":"att-1","size":1200}},
                  {"mimeType":"application/octet-stream","filename":"B.PDF","body":{"attachmentId":"att-2","size":34}},
                  {"mimeType":"application/pdf","filename":"no-size.pdf","body":{"attachmentId":"att-3"}},
                  {"mimeType":"application/pdf","filename":"negative.pdf","body":{"attachmentId":"att-4","size":-5}},
                  {"mimeType":"application/pdf","filename":"inline.pdf","body":{"size":9,"data":"dGVzdA"}},
                  {"mimeType":"image/png","filename":"pdf.png","body":{"attachmentId":"att-5","size":7}},
                  {"mimeType":"application/vnd.ms-excel","filename":"sheet.pdf.xls","body":{"attachmentId":"att-6","size":7}},
                  {"mimeType":"application/pdf","filename":"bad-id.pdf","body":{"attachmentId":"a b/../c","size":7}},
                  {"mimeType":"application/pdf","filename":"dots.pdf","body":{"attachmentId":"..","size":7}},
                  {"mimeType":"application/pdf","filename":"dot.pdf","body":{"attachmentId":".","size":7}}]}
                """;

        List<MailSource.Pdf> found = GmailLetters.pdfsOf(part(payload));

        assertThat(found).extracting(MailSource.Pdf::name, MailSource.Pdf::size, MailSource.Pdf::reference).containsExactly(
                org.assertj.core.groups.Tuple.tuple("A.pdf", 1200L, "att-1"), org.assertj.core.groups.Tuple.tuple("B.PDF", 34L, "att-2"),
                org.assertj.core.groups.Tuple.tuple("no-size.pdf", 0L, "att-3"), org.assertj.core.groups.Tuple.tuple("negative.pdf", 0L, "att-4"));
    }

    @Test
    void aPdfsNameIsDecodedMadeOneLineAndBounded() {
        String payload = "{\"mimeType\":\"multipart/mixed\",\"parts\":[{\"mimeType\":\"application/pdf\",\"filename\":\"" + b("UTF-8", "Oferta Pałac") + ".pdf\","
                + "\"body\":{\"attachmentId\":\"att-1\"}},{\"mimeType\":\"application/pdf\",\"filename\":\"line\\nbreak\\u0007.pdf\",\"body\":{\"attachmentId\":\"att-2\"}},"
                + "{\"mimeType\":\"application/pdf\",\"filename\":\"" + "n".repeat(500) + ".pdf\",\"body\":{\"attachmentId\":\"att-3\"}}]}";

        List<MailSource.Pdf> found = GmailLetters.pdfsOf(part(payload));

        assertThat(found.get(0).name()).isEqualTo("Oferta Pałac.pdf");
        assertThat(found.get(1).name()).isEqualTo("line break .pdf");
        assertThat(found.get(2).name()).hasSize(200);
    }

    @Test
    void whatBeginsLikeAPdfIsExactlyWhatStartsWithItsFiveCharacters() {
        assertThat(GmailLetters.beginsLikeAPdf("%PDF-1.7".getBytes())).isTrue();
        assertThat(GmailLetters.beginsLikeAPdf("%PDF-".getBytes())).isTrue();
        assertThat(GmailLetters.beginsLikeAPdf("%PDF".getBytes())).isFalse();
        assertThat(GmailLetters.beginsLikeAPdf("%pdf-1.7".getBytes())).isFalse();
        assertThat(GmailLetters.beginsLikeAPdf(" %PDF-1.7".getBytes())).isFalse();
        assertThat(GmailLetters.beginsLikeAPdf(new byte[0])).isFalse();
    }

    @Test
    void theIdentifierTheSenderGaveIsWhatIsBetweenTheAnglesAndNothingElseIsChanged() {
        assertThat(GmailLetters.identifierOf("<abc.123@vendor.example>")).isEqualTo("abc.123@vendor.example");
        assertThat(GmailLetters.identifierOf("abc.123@vendor.example")).isEqualTo("abc.123@vendor.example");
        assertThat(GmailLetters.identifierOf("  <abc.123@vendor.example>\t ")).isEqualTo("abc.123@vendor.example");
        assertThat(GmailLetters.identifierOf("< abc.123@vendor.example >")).isEqualTo("abc.123@vendor.example");
        assertThat(GmailLetters.identifierOf("<ABC.123@Vendor.example>")).as("the case stays").isEqualTo("ABC.123@Vendor.example");
        assertThat(GmailLetters.identifierOf("<a b@x>")).as("what is inside stays").isEqualTo("a b@x");
        assertThat(GmailLetters.identifierOf("<<a@x>>")).as("one pair only").isEqualTo("<a@x>");
        assertThat(GmailLetters.identifierOf("")).isEmpty();
        assertThat(GmailLetters.identifierOf("   ")).isEmpty();
        assertThat(GmailLetters.identifierOf("<>")).isEmpty();
        assertThat(GmailLetters.identifierOf(" < > ")).isEmpty();
    }
}
