package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.Destinations.Refused;
import io.github.trufelek124.weddingplanner.inspirations.internal.LinkFetcher.Fetched;
import io.github.trufelek124.weddingplanner.inspirations.internal.LinkFetcher.Unusable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Looks at the page behind a link the way a messenger does before showing a preview: the title the
 * page gives itself and the picture it offers for that purpose. Nothing on the page is run.
 */
@Component
class PreviewReader {

    private static final Logger log = LoggerFactory.getLogger(PreviewReader.class);

    static final int MOST_PAGE_BYTES = 1024 * 1024;
    static final int MOST_PICTURE_BYTES = 10 * 1024 * 1024;
    static final int TITLE_LENGTH = 200;
    private static final String PAGES = "text/html,application/xhtml+xml;q=0.9,image/*;q=0.8,*/*;q=0.1";
    private static final String PICTURES = "image/jpeg,image/png,image/webp,image/gif,image/*;q=0.5";

    /** Where a page says what its preview picture is, most telling first. */
    private static final List<String> PICTURE_PLACES = List.of(
            "meta[property=og:image:secure_url]", "meta[property=og:image]", "meta[name=og:image]",
            "meta[name=twitter:image]", "meta[property=twitter:image]", "meta[name=twitter:image:src]");

    private static final List<String> TITLE_PLACES =
            List.of("meta[property=og:title]", "meta[name=og:title]", "meta[name=twitter:title]", "meta[property=twitter:title]");

    private final LinkFetcher fetcher;

    PreviewReader(LinkFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** What was found out about a link. */
    sealed interface Outcome {
        /**
         * @param title empty when the page names none
         * @param picture the bytes of the preview picture, or null; whether they are a picture is still to be checked
         */
        record Found(String title, byte[] picture) implements Outcome {}

        /** The link gives nothing, or the server does not go there. Trying again would not help. */
        record Nothing() implements Outcome {}

        /** No answer this time. */
        record TryAgain() implements Outcome {}
    }

    /** Never throws: whatever goes wrong becomes an outcome. Why is only logged, without the address. */
    Outcome read(URI link) {
        Fetched page;
        try {
            page = fetcher.get(link, PAGES, MOST_PAGE_BYTES);
        } catch (Refused refused) {
            log.info("A link was not fetched for a preview: {}", refused.getMessage());
            return new Outcome.Nothing();
        } catch (Unusable unusable) {
            log.debug("A link gave no preview: {}", unusable.getMessage());
            return unusable.worthAnotherTry() ? new Outcome.TryAgain() : new Outcome.Nothing();
        }
        if (page.contentType().startsWith("image/")) {
            // The link is a picture itself.
            return new Outcome.Found("", page.body());
        }
        if (!page.contentType().equals("text/html") && !page.contentType().equals("application/xhtml+xml")) {
            return new Outcome.Nothing();
        }
        Document document;
        try {
            document = Jsoup.parse(new ByteArrayInputStream(page.body()), charsetName(page.charset()), page.from().toString());
        } catch (IOException | RuntimeException unreadable) {
            return new Outcome.Nothing();
        }
        String title = titleOf(document);
        byte[] picture = pictureAddress(document, page.from()).map(this::picture).orElse(null);
        return title.isEmpty() && picture == null ? new Outcome.Nothing() : new Outcome.Found(title, picture);
    }

    private byte[] picture(URI address) {
        try {
            return fetcher.get(address, PICTURES, MOST_PICTURE_BYTES).body();
        } catch (Refused | Unusable noPicture) {
            // A page with a title and no reachable picture is still worth its title.
            log.debug("A preview picture was not fetched: {}", noPicture.getMessage());
            return null;
        }
    }

    private static String titleOf(Document document) {
        for (String place : TITLE_PLACES) {
            Element found = document.selectFirst(place);
            if (found != null && !tidy(found.attr("content")).isEmpty()) {
                return tidy(found.attr("content"));
            }
        }
        return tidy(document.title());
    }

    private static Optional<URI> pictureAddress(Document document, URI page) {
        for (String place : PICTURE_PLACES) {
            Element found = document.selectFirst(place);
            if (found != null && !found.attr("content").isBlank()) {
                return resolved(page, found.attr("content"));
            }
        }
        Element link = document.selectFirst("link[rel=image_src]");
        return link == null || link.attr("href").isBlank() ? Optional.empty() : resolved(page, link.attr("href"));
    }

    private static Optional<URI> resolved(URI page, String address) {
        try {
            return Optional.of(page.resolve(new URI(address.strip())));
        } catch (URISyntaxException | IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
    }

    /** One line of plain text, no longer than a title may be. */
    private static String tidy(String text) {
        String oneLine = text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return oneLine.length() > TITLE_LENGTH ? oneLine.substring(0, TITLE_LENGTH - 1).strip() + "…" : oneLine;
    }

    /** The character set the server named, if Java knows it; otherwise the parser looks into the page. */
    private static String charsetName(String named) {
        try {
            return named != null && Charset.isSupported(named) ? named : null;
        } catch (IllegalArgumentException notAName) {
            return null;
        }
    }
}
