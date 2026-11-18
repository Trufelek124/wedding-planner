package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.trufelek124.weddingplanner.inspirations.internal.Destinations.Refused;
import io.github.trufelek124.weddingplanner.inspirations.internal.LinkFetcher.Fetched;
import io.github.trufelek124.weddingplanner.inspirations.internal.LinkFetcher.Unusable;
import io.github.trufelek124.weddingplanner.inspirations.internal.StandIn.Answer;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The fetcher against a stand-in server on this machine: what it gets, what it refuses, what it gives up on. */
class LinkFetcherTest {

    private static final String ANY = "*/*";

    private StandIn standIn;
    private LinkFetcher fetcher;

    @BeforeEach
    void start() {
        standIn = new StandIn();
        fetcher = new LinkFetcher(
                new StandInDestinations(standIn.port()), Duration.ofSeconds(2), Duration.ofMillis(400), Duration.ofMillis(900));
    }

    @AfterEach
    void stop() throws Exception {
        fetcher.destroy();
        standIn.stop();
    }

    // ------------------------------------------------------------ what it gets

    @Test
    void fetchesAPageAndSaysWhatKindItIs() throws Exception {
        standIn.serve("/page", Answer.of(200, "Text/HTML; Charset=\"ISO-8859-2\"", "<title>Zażółć</title>".getBytes("ISO-8859-2")));

        Fetched fetched = fetcher.get(standIn.at("/page"), "text/html", 10_000);

        assertThat(fetched.contentType()).isEqualTo("text/html");
        assertThat(fetched.charset()).isEqualTo("ISO-8859-2");
        assertThat(new String(fetched.body(), "ISO-8859-2")).isEqualTo("<title>Zażółć</title>");
        assertThat(fetched.from()).isEqualTo(standIn.at("/page"));
    }

    @Test
    void sendsNothingThatSaysWhoIsAsking() throws Exception {
        standIn.serve("/first", Answer.html("one").with("Set-Cookie", "tracking=1; Path=/"));
        standIn.serve("/second", Answer.html("two"));

        fetcher.get(standIn.at("/first"), "text/html", 10_000);
        fetcher.get(standIn.at("/second"), "text/html", 10_000);

        for (StandIn.Seen request : standIn.seen()) {
            assertThat(request.headers().keySet()).as("headers of " + request.path())
                    .doesNotContain("Cookie", "Authorization", "Referer", "Origin", "X-forwarded-for", "Forwarded");
            assertThat(request.headers().get("User-agent")).containsExactly("Mozilla/5.0 (compatible; link-preview)");
        }
        assertThat(standIn.seen()).hasSize(2);
    }

    @Test
    void followsRedirectsOnTheSameTermsAndSaysWhereItEndedUp() throws Exception {
        standIn.serve("/short", Answer.redirect("/moved"));
        standIn.serve("/moved", Answer.redirect(standIn.at("/final").toString()));
        standIn.serve("/final", Answer.html("here"));

        Fetched fetched = fetcher.get(standIn.at("/short"), ANY, 10_000);

        assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).isEqualTo("here");
        assertThat(fetched.from()).isEqualTo(standIn.at("/final"));
    }

    // ------------------------------------------------------------ where it does not go

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "localhost", "[::1]", "0.0.0.0", "127.1", "2130706433", "0x7f.0.0.1", "[::ffff:127.0.0.1]"})
    void doesNotConnectToThisMachineHoweverItIsSpelled(String host) throws Exception {
        // The real rules, except that any port may be asked for: what is under test is the address.
        Destinations anyPort = new Destinations() {
            @Override
            public void checkAddress(URI address) {}

            @Override
            public void checkResolved(String name, InetAddress[] addresses) throws Refused {
                new PublicDestinations().checkResolved(name, addresses);
            }
        };
        LinkFetcher strict = new LinkFetcher(anyPort, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2));
        standIn.serve("/secret", Answer.html("the admin page"));
        try {
            URI address = URI.create("http://" + host + ":" + standIn.port() + "/secret");

            // Refused, or a spelling the name service does not even know: either way nothing was asked of the server.
            assertThatThrownBy(() -> strict.get(address, ANY, 10_000)).isInstanceOfAny(Refused.class, Unusable.class);
            assertThat(standIn.seen()).as("requests that reached the server").isEmpty();
        } finally {
            strict.destroy();
        }
    }

    @Test
    void theRealRulesRefuseTheStandInBeforeAnythingIsSent() throws Exception {
        LinkFetcher real = new LinkFetcher(new PublicDestinations());
        standIn.serve("/secret", Answer.html("the admin page"));
        try {
            assertThatThrownBy(() -> real.get(standIn.at("/secret"), ANY, 10_000)).isInstanceOf(Refused.class);
            assertThatThrownBy(() -> real.get(URI.create("http://169.254.169.254/latest/meta-data/"), ANY, 10_000)).isInstanceOf(Refused.class);
            assertThatThrownBy(() -> real.get(URI.create("http://10.0.0.1/"), ANY, 10_000)).isInstanceOf(Refused.class);
            assertThatThrownBy(() -> real.get(URI.create("http://[fd00::1]/"), ANY, 10_000)).isInstanceOf(Refused.class);
            assertThatThrownBy(() -> real.get(URI.create("file:///etc/passwd"), ANY, 10_000)).isInstanceOf(Refused.class);
            assertThat(standIn.seen()).isEmpty();
        } finally {
            real.destroy();
        }
    }

    @Test
    void aRedirectIsCheckedLikeAnyAddressAndIsNotFollowedInside() {
        standIn.serve("/to-private", Answer.redirect("http://10.11.12.13/admin"));
        standIn.serve("/to-metadata", Answer.redirect("http://169.254.169.254/latest/meta-data/"));
        standIn.serve("/to-another-port", Answer.redirect("http://127.0.0.1:" + (standIn.port() == 65535 ? 65534 : standIn.port() + 1) + "/"));
        standIn.serve("/to-a-file", Answer.redirect("file:///etc/passwd"));
        standIn.serve("/to-a-name-for-this-machine", Answer.redirect("http://localhost:" + standIn.port() + "/secret"));
        standIn.serve("/secret", Answer.html("the admin page"));

        for (String path : List.of("/to-private", "/to-metadata", "/to-another-port", "/to-a-file", "/to-a-name-for-this-machine")) {
            assertThatThrownBy(() -> fetcher.get(standIn.at(path), ANY, 10_000)).as(path).isInstanceOf(Refused.class);
        }
        assertThat(standIn.hits("/secret")).isZero();
    }

    @Test
    void givesUpAfterThreeRedirects() {
        standIn.serve("/1", Answer.redirect("/2"));
        standIn.serve("/2", Answer.redirect("/3"));
        standIn.serve("/3", Answer.redirect("/4"));
        standIn.serve("/4", Answer.redirect("/5"));
        standIn.serve("/5", Answer.html("never reached"));

        Unusable unusable = catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/1"), ANY, 10_000));

        assertThat(unusable).hasMessage("too many redirects");
        assertThat(unusable.worthAnotherTry()).isFalse();
        assertThat(standIn.hits("/5")).isZero();
        assertThat(standIn.hits("/4")).isEqualTo(1);
    }

    // ------------------------------------------------------------ what it gives up on

    @Test
    void stopsReadingAnAnswerThatIsLargerThanAllowed() {
        standIn.serve("/huge", Answer.of(200, "text/html", new byte[200_000]));

        Unusable unusable = catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/huge"), ANY, 50_000));

        assertThat(unusable).hasMessageContaining("larger than 50000");
        assertThat(unusable.worthAnotherTry()).isFalse();
    }

    @Test
    void doesNotWaitForAServerThatSaysNothing() {
        standIn.serve("/slow", Answer.html("late").after(3_000));

        long started = System.nanoTime();
        Unusable unusable = catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/slow"), ANY, 10_000));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
        assertThat(unusable.worthAnotherTry()).as("a slow server may be quicker tomorrow").isTrue();
    }

    @Test
    void tellsAnswersThatMayChangeFromThoseThatWillNot() {
        standIn.serve("/gone", Answer.status(404));
        standIn.serve("/forbidden", Answer.status(403));
        standIn.serve("/unwell", Answer.status(503));
        standIn.serve("/busy", Answer.status(429));
        standIn.serve("/nowhere", new Answer(302, java.util.Map.of(), new byte[0], 0));

        assertThat(catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/gone"), ANY, 100)).worthAnotherTry()).isFalse();
        assertThat(catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/forbidden"), ANY, 100)).worthAnotherTry()).isFalse();
        assertThat(catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/unwell"), ANY, 100)).worthAnotherTry()).isTrue();
        assertThat(catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/busy"), ANY, 100)).worthAnotherTry()).isTrue();
        assertThat(catchThrowableOfType(Unusable.class, () -> fetcher.get(standIn.at("/nowhere"), ANY, 100)).worthAnotherTry()).isFalse();
    }

    @Test
    void aServerThatIsNotThereIsWorthAnotherTry() {
        int port = standIn.port();
        standIn.stop();

        Unusable unusable = catchThrowableOfType(Unusable.class, () -> fetcher.get(URI.create("http://127.0.0.1:" + port + "/"), ANY, 100));

        assertThat(unusable.worthAnotherTry()).isTrue();
    }
}
