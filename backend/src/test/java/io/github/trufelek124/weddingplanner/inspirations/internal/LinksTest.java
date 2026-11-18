package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.Source;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LinksTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.instagram.com/p/abc123/",
        "http://example.com",
        "HTTPS://Example.com/a?b=c#d",
        "  https://example.com/with-spaces-around  ",
    })
    void aWebAddressIsALink(String text) {
        assertThat(Links.parse(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "example.com/no-scheme",
        "javascript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "file:///etc/passwd",
        "ftp://example.com/file",
        "mailto:someone@example.com",
        "https://",
        "https:///path-without-a-host",
        "https://exa mple.com",
    })
    void anythingElseIsNot(String text) {
        assertThat(Links.parse(text)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void aLinkTooLongToKeepIsNotOne() {
        assertThat(Links.parse("https://example.com/" + "a".repeat(Links.MAX_LENGTH))).isEmpty();
        assertThat(Links.parse(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "https://www.instagram.com/p/abc/, INSTAGRAM",
        "https://instagram.com/reel/abc/, INSTAGRAM",
        "https://instagr.am/p/abc/, INSTAGRAM",
        "https://www.tiktok.com/@someone/video/1, TIKTOK",
        "https://vm.tiktok.com/ZMabc/, TIKTOK",
        "https://pin.it/abc, PINTEREST",
        "https://www.pinterest.com/pin/1/, PINTEREST",
        "https://pl.pinterest.com/pin/1/, PINTEREST",
        "https://www.pinterest.co.uk/pin/1/, PINTEREST",
        "https://pinterest.de/pin/1/, PINTEREST",
        "https://example.com/wedding-ideas, WEB",
        // A name that only looks like one of them stays what it is: some web page.
        "https://instagram.com.example.org/p/abc/, WEB",
        "https://notinstagram.com/p/abc/, WEB",
        "https://tiktok.com.example.org/video, WEB",
        "https://pinterest.attacker.example/pin, WEB",
        "https://example.com/instagram.com, WEB",
    })
    void theSourceIsReadFromWhereTheLinkLeads(String link, Source expected) {
        assertThat(Links.sourceOf(URI.create(link))).isEqualTo(expected);
    }
}
