package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.trufelek124.weddingplanner.TestPdfs;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** What the server takes out of a PDF document, and what it does when it cannot: no server, no mail, only files the test makes. */
class PdfTextTest {

    private final PdfText pdfText = new PdfText();

    // ---------------------------------------------------------------- what comes out

    @Test
    void thePolishAndUkrainianWordsOfADocumentComeOutAsTheyWere() {
        byte[] pdf = TestPdfs.withPages(
                "Oferta: Pałac na Wzgórzu, sala na 19 czerwca 2027\nZaliczka: 2500,50 zł do 1 grudnia 2026 r.",
                "Другий внесок: 5000 грн до 15 січня 2027\nЗалишок: Їжа, Євген, ґанок");

        Optional<PdfText.Read> read = pdfText.read(pdf);

        assertThat(read).isPresent();
        assertThat(read.get().text()).contains("Zaliczka: 2500,50 zł do 1 grudnia 2026 r.", "Pałac na Wzgórzu", "Другий внесок: 5000 грн до 15 січня 2027",
                "Їжа, Євген, ґанок");
        assertThat(read.get().cut()).isFalse();
    }

    @Test
    void whatTheTextHoldsIsPlainWithNoControlCharacters() {
        byte[] pdf = TestPdfs.withPages("Line one\n\n\n\nLine   two with   gaps");

        String text = pdfText.read(pdf).orElseThrow().text();

        assertThat(text).doesNotContainPattern("[\\p{Cntrl}&&[^\\n]]").doesNotContain("\n\n\n").doesNotContain("  ");
        assertThat(text).contains("Line one").contains("Line two with gaps");
    }

    @Test
    void aDocumentInAStandardFontIsReadToo() {
        assertThat(pdfText.read(TestPdfs.inAStandardFont("Deposit due on 1 December")).orElseThrow().text()).contains("Deposit due on 1 December");
    }

    // ---------------------------------------------------------------- what cannot be read

    @Test
    void aDocumentThatAsksForAPasswordIsNotRead() {
        byte[] locked = TestPdfs.locked("s3cret-Qx", "Zaliczka 2500 zł");

        assertThat(pdfText.read(locked)).isEmpty();
    }

    @Test
    void aDocumentThatIsOnlyPicturesOfPagesHasNoText() {
        assertThat(pdfText.read(TestPdfs.onlyAPicture())).isEmpty();
    }

    @Test
    void aFileThatIsNotAPdfOrIsCutOffIsNotRead() {
        byte[] whole = TestPdfs.withPages("Zaliczka 2500 zł do 1 grudnia");

        assertThat(pdfText.read("Not a document at all, only words.".getBytes())).isEmpty();
        assertThat(pdfText.read("%PDF-1.7\nbut nothing of a document follows".getBytes())).isEmpty();
        assertThat(pdfText.read(new byte[0])).isEmpty();
        assertThat(pdfText.read(TestPdfs.cutOffAt(whole, 40))).isEmpty();
        // Cut in the middle, the library may still find its way; whatever it does, it throws nothing.
        pdfText.read(TestPdfs.cutOffAt(whole, whole.length / 2));
        pdfText.read(TestPdfs.cutOffAt(whole, whole.length - 3));
    }

    @Test
    void aFileOfRandomBytesBehindAPdfBeginningIsNotRead() {
        byte[] bytes = new byte[5_000];
        new java.util.Random(7).nextBytes(bytes);
        System.arraycopy("%PDF-1.7\n".getBytes(), 0, bytes, 0, 9);

        assertThat(pdfText.read(bytes)).isEmpty();
    }

    // ---------------------------------------------------------------- the limits

    @Test
    void onlyTheFirstFiftyPagesAreReadAndTheDocumentIsSaidToBeCut() {
        Optional<PdfText.Read> over = pdfText.read(TestPdfs.ofPages(51));
        Optional<PdfText.Read> exactly = pdfText.read(TestPdfs.ofPages(50));

        assertThat(over.orElseThrow().text()).contains("PAGE-1-MARKER", "PAGE-50-MARKER").doesNotContain("PAGE-51-MARKER");
        assertThat(over.get().cut()).isTrue();
        assertThat(exactly.orElseThrow().text()).contains("PAGE-50-MARKER");
        assertThat(exactly.get().cut()).isFalse();
    }

    @Test
    void aDocumentIsCutAtTwentyThousandCharacters() {
        String line = "Ala ma kota, a kot ma Alę. Zaliczka 1000 zł płatna do dnia ustalonego";
        String page = String.join("\n", java.util.Collections.nCopies(40, line));
        String[] pages = new String[20];
        java.util.Arrays.fill(pages, page);

        PdfText.Read read = pdfText.read(TestPdfs.withPages(pages)).orElseThrow();

        assertThat(read.text().codePointCount(0, read.text().length())).isEqualTo(PdfText.MOST_CHARACTERS);
        assertThat(read.cut()).isTrue();
        assertThat(read.text()).startsWith("Ala ma kota");
    }

    @Test
    void aDocumentThatFitsIsNotSaidToBeCut() {
        assertThat(pdfText.read(TestPdfs.withPages("Short.")).orElseThrow().cut()).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void workThatTakesTooLongIsGivenUpOnAndNoSecondDocumentIsStartedBesideIt() throws Exception {
        AtomicInteger started = new AtomicInteger();
        PdfText slow = new PdfText(Duration.ofMillis(200), (pdf, stop) -> {
            if (started.incrementAndGet() == 1) {
                while (!stop.isGivenUp()) {
                    Thread.sleep(10);
                }
                return Optional.of(new PdfText.Read("too late", false));
            }
            return Optional.of(new PdfText.Read("quick", false));
        });
        long before = System.nanoTime();

        assertThat(slow.read(new byte[] {1})).as("given up on").isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(5));
        // The one that was given up on ends by itself (it was told); then the next document is read.
        long deadline = System.currentTimeMillis() + 5_000;
        Optional<PdfText.Read> next = Optional.empty();
        while (next.isEmpty() && System.currentTimeMillis() < deadline) {
            next = slow.read(new byte[] {1});
            Thread.sleep(20);
        }
        assertThat(next.orElseThrow().text()).isEqualTo("quick");
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void aDocumentThatNeverEndsBlocksNoOtherThanItselfForGood() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        PdfText stuck = new PdfText(Duration.ofMillis(100), (pdf, stop) -> {
            release.await();
            return Optional.empty();
        });

        assertThat(stuck.read(new byte[] {1})).isEmpty();
        long before = System.nanoTime();
        assertThat(stuck.read(new byte[] {2})).as("no second one is started beside it").isEmpty();

        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofMillis(90));
        release.countDown();
    }

    // ---------------------------------------------------------------- nothing is thrown outward

    @Test
    void whateverTheWorkThrowsIsOnlyThatTheDocumentCouldNotBeRead() {
        for (Throwable thrown : new Throwable[] {
            new RuntimeException("Secret-Qx words of the document"), new IOException("Secret-Qx"), new OutOfMemoryError("Java heap space"),
            new StackOverflowError(), new AssertionError("Secret-Qx"), new LinkageError("Secret-Qx")}) {
            PdfText failing = new PdfText(Duration.ofSeconds(5), (pdf, stop) -> sneak(thrown));

            assertThat(failing.read(new byte[] {1})).as(thrown.getClass().getSimpleName()).isEmpty();
            assertThat(failing.read(new byte[] {1})).as("and the next one can be tried").isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneak(Throwable thrown) throws T {
        throw (T) thrown;
    }

    // ---------------------------------------------------------------- nothing in the file is run or followed, nothing is written

    @Test
    void aScriptALinkAndALaunchActionInTheFileAreNotRunOrFollowed() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        elsewhere.createContext("/", exchange -> {
            asked.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        elsewhere.start();
        try {
            byte[] pdf = TestPdfs.thatTriesThings("http://127.0.0.1:" + elsewhere.getAddress().getPort(), "Zaliczka 2500 zł do 1 grudnia");

            PdfText.Read read = pdfText.read(pdf).orElseThrow();
            Thread.sleep(300);

            assertThat(read.text()).contains("Zaliczka 2500 zł do 1 grudnia");
            assertThat(asked).as("nothing in the file was fetched").hasValue(0);
        } finally {
            elsewhere.stop(0);
        }
    }

    @Test
    void readingADocumentWritesNoFileAnywhere() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> before = names(home, temp);

        pdfText.read(TestPdfs.inAStandardFont("Deposit due"));
        pdfText.read(TestPdfs.withPages("Zaliczka 2500 zł"));
        pdfText.read(TestPdfs.onlyAPicture());
        pdfText.read(TestPdfs.locked("Secret-Qx", "Zaliczka"));

        assertThat(names(home, temp)).isEqualTo(before);
    }

    private static Set<String> names(Path... directories) throws IOException {
        Set<String> names = new HashSet<>();
        for (Path directory : directories) {
            try (Stream<Path> files = Files.list(directory)) {
                names.addAll(files.filter(path -> path.getFileName().toString().matches("(?i).*(pdf|font|cache|\\.tmp$|^tmp).*"))
                        .map(path -> path + ":" + path.toFile().length()).collect(Collectors.toSet()));
            }
        }
        return names;
    }

    @Test
    void aMissingPieceOfTheRuntimeIsLoggedAtWarnWithItsKindAndABadDocumentIsNot() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PdfText.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PdfText(Duration.ofSeconds(5), (pdf, stop) -> sneak(new NoClassDefFoundError("Secret-Qx/Missing"))).read(new byte[] {1});
            new PdfText(Duration.ofSeconds(5), (pdf, stop) -> sneak(new IOException("Secret-Qx"))).read(new byte[] {1});
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("NoClassDefFoundError").doesNotContain("Secret-Qx");
            assertThat(appender.list.get(1).getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(appender.list.get(1).getFormattedMessage()).doesNotContain("Secret-Qx");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void theReaderSaysWhetherADocumentIsStillInWorkWithoutWaiting() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        PdfText stuck = new PdfText(Duration.ofMillis(50), (pdf, stop) -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    // A stuck library does not look.
                }
            }
            return Optional.empty();
        });
        assertThat(stuck.busy()).isFalse();
        assertThat(stuck.read(new byte[] {1})).isEmpty();

        assertThat(stuck.busy()).isTrue();
        release.countDown();
    }
}
