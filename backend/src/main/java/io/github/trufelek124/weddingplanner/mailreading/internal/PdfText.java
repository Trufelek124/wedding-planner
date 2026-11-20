package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.FontInfo;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontProvider;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The text of a PDF document (S04 sections 3 and 5, D-011): from bytes to words, and nothing else. The library reads the file
 * and runs nothing in it: no script, no link, no embedded file and no address in it is followed or fetched, the file is
 * never written anywhere (the library's stream cache is the memory-only one, so no temporary file exists) and no font
 * or picture of it is saved.
 *
 * <p>What bounds the work: the first {@link #MOST_PAGES} pages, at most {@link #MOST_CHARACTERS} characters of the text, a time
 * limit (the work runs on a thread of its own that is given up on when the time is over), and one document at a time on the whole
 * server. What the work throws, a memory that is short included, is only "this document could not be read": nothing of the
 * exception is kept, because it may hold the document's own words. A document that asks for a password, is damaged, forbids
 * taking its text, or holds no text (pictures of pages) is the same.
 *
 * <p>The text is somebody else's words: it is never logged and never kept beyond the round (it is only returned).
 */
@Component
final class PdfText {

    static {
        // The library draws with the JDK's own geometry classes, never with a screen: where it is run on a desktop, no window
        // or icon must come of it.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PdfText.class);

    static {
        noFontsOfTheMachine();
    }

    /** Only the first pages are read. */
    static final int MOST_PAGES = 50;
    /** One document's text is cut here, in characters (Unicode code points). */
    static final int MOST_CHARACTERS = 20_000;
    /** How long the work on one document may take. */
    static final Duration LIMIT = Duration.ofSeconds(10);

    /**
     * What was read: plain words, and whether the document goes on beyond them (more pages than are read, or more text than
     * fits). Never empty. Somebody's words: nothing of them is printed.
     */
    record Read(String text, boolean cut) {

        @Override
        public String toString() {
            return "Read[]";
        }
    }

    /** The one thing a document is put through. A test puts another in its place to make it slow. */
    @FunctionalInterface
    interface Work {

        /** Empty for a document that cannot be read. May throw anything. */
        Optional<Read> run(byte[] pdf, Stop stop) throws Exception;
    }

    /** Whether the work has been given up on: the work looks at it between pages and between pieces of text and ends. */
    static final class Stop {

        private volatile boolean given;

        boolean isGivenUp() {
            return given;
        }

        void giveUp() {
            given = true;
        }
    }

    private final Duration limit;
    private final Work work;
    /** The one document that may be in work. A document that is still being worked on after its time was over keeps it. */
    private final Semaphore one = new Semaphore(1);

    @Autowired
    PdfText() {
        this(LIMIT, PdfText::extract);
    }

    PdfText(Duration limit, Work work) {
        this.limit = limit;
        this.work = work;
    }

    /** Whether a document that was given up on is still being worked on: then no other is read. No waiting. */
    boolean busy() {
        return one.availablePermits() == 0;
    }

    /**
     * The text of this document, or empty when it cannot be read. Never throws.
     */
    Optional<Read> read(byte[] pdf) {
        if (!one.tryAcquire()) {
            // A document that was given up on is still being worked on: no second one is started beside it.
            log.warn("A document could not be read: the one before it is still being worked on");
            return Optional.empty();
        }
        Stop stop = new Stop();
        CompletableFuture<Optional<Read>> done = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                done.complete(work.run(pdf, stop));
            } catch (Throwable failure) {
                // Whatever it was, a memory that was short included; its message may be the document's own words.
                if (failure instanceof LinkageError) {
                    // Not the document's fault: a class or a library the runtime lacks.
                    log.warn("A document could not be read: the runtime lacks something ({})", failure.getClass().getSimpleName());
                } else {
                    log.info("A document could not be read ({})", failure.getClass().getSimpleName());
                }
                done.complete(Optional.empty());
            } finally {
                one.release();
            }
        }, "pdf-text");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable notStarted) {
            one.release();
            return Optional.empty();
        }
        try {
            return done.get(limit.toMillis(), TimeUnit.MILLISECONDS).filter(read -> !read.text().isBlank());
        } catch (TimeoutException tooSlow) {
            stop.giveUp();
            worker.interrupt();
            log.info("A document could not be read: it took longer than {} seconds", limit.toSeconds());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            stop.giveUp();
            worker.interrupt();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException impossible) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- the library

    /** The fonts of the machine: none. The library would otherwise look through them and write a list of them into the home directory. */
    private static final class NoFonts extends FontProvider {

        @Override
        public String toDebugString() {
            return "none";
        }

        @Override
        public List<? extends FontInfo> getFontInfo() {
            return List.of();
        }
    }

    /**
     * Taking the text out of a document never needs a font of the machine: the characters come from the file's own tables. Where
     * a file does not carry a font it names (one of the standard ones), the library looks the machine's fonts up for the sizes of
     * the letters, and writes a list of what it found ({@code .pdfbox.cache}) into the home directory. Neither is wanted
     * on a server, so the library is told that the machine has no fonts. If the library no longer has this door, the document
     * is read as before and a test says that something is written.
     */
    private static void noFontsOfTheMachine() {
        try {
            Object mapper = FontMappers.instance();
            java.lang.reflect.Method setProvider = mapper.getClass().getMethod("setProvider", FontProvider.class);
            // The class is not public (its method is): on the class path that is all that stands in the way.
            setProvider.setAccessible(true);
            setProvider.invoke(mapper, new NoFonts());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError doorIsGone) {
            log.warn("The library that reads documents could not be told that the machine has no fonts ({})", doorIsGone.getClass().getSimpleName());
        }
    }

    /** Nothing more is wanted. */
    private static final class Enough extends IOException {

        Enough() {
            super("enough");
        }
    }

    /** The text, up to the limit, counted in characters as people count them (code points), and no more kept than that. */
    private static final class Limited extends Writer {

        private final StringBuilder text = new StringBuilder();
        private final Stop stop;
        private boolean full;

        Limited(Stop stop) {
            this.stop = stop;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (stop.isGivenUp()) {
                throw new Enough();
            }
            text.append(buffer, offset, length);
            if (text.codePointCount(0, text.length()) > MOST_CHARACTERS) {
                text.setLength(text.offsetByCodePoints(0, MOST_CHARACTERS));
                full = true;
                throw new Enough();
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    }

    /** Looks at whether it was given up on before each page. */
    private static final class Stripper extends PDFTextStripper {

        private final Stop stop;

        Stripper(Stop stop) {
            this.stop = stop;
            setLineSeparator("\n");
            setParagraphEnd("\n");
            setArticleStart("");
            setArticleEnd("");
            setPageStart("");
            setPageEnd("\n");
            setStartPage(1);
            setEndPage(MOST_PAGES);
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            if (stop.isGivenUp()) {
                throw new Enough();
            }
            super.processPage(page);
        }
    }

    static Optional<Read> extract(byte[] bytes, Stop stop) throws IOException {
        // An empty password: a file that asks for another is not read. Only memory is used for what the library keeps.
        try (PDDocument document = Loader.loadPDF(bytes, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
            int pages = document.getNumberOfPages();
            Limited out = new Limited(stop);
            try {
                new Stripper(stop).writeText(document, out);
            } catch (Enough enough) {
                if (stop.isGivenUp()) {
                    return Optional.empty();
                }
            }
            String text = tidy(out.text.toString());
            return text.isBlank() ? Optional.empty() : Optional.of(new Read(text, out.full || pages > MOST_PAGES));
        }
    }

    /**
     * Words as a person would read them: no control character (a line break and a tab stay as they are), blanks made one, lines
     * trimmed, at most one empty line in a row.
     */
    static String tidy(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            int type = Character.getType(point);
            if (point == '\n') {
                out.append('\n');
            } else if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SPACE_SEPARATOR) {
                out.append(' ');
            } else if (type == Character.FORMAT || type == Character.SURROGATE || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE) {
                // Says nothing, or steers how the text looks.
                return;
            } else {
                out.appendCodePoint(point);
            }
        });
        StringBuilder tidy = new StringBuilder();
        int empty = 0;
        for (String line : out.toString().split("\n", -1)) {
            String trimmed = line.replaceAll(" {2,}", " ").strip();
            if (trimmed.isEmpty()) {
                empty++;
                continue;
            }
            if (tidy.length() > 0) {
                tidy.append(empty > 0 ? "\n\n" : "\n");
            }
            empty = 0;
            tidy.append(trimmed);
        }
        return tidy.toString();
    }
}
