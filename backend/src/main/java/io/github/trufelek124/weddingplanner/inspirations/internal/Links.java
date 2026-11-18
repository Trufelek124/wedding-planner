package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.Source;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/** What the planner makes of a link someone pasted or shared. */
final class Links {

    static final int MAX_LENGTH = 2000;

    private Links() {}

    /**
     * The link as it is kept: an address a browser can open, on http or https. Anything else is no link,
     * so that nothing but a web page is ever put behind a card's "open".
     */
    static Optional<URI> parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(text.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean web = scheme.equals("http") || scheme.equals("https");
            return web && uri.getHost() != null && !uri.getHost().isBlank() ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException notAnAddress) {
            return Optional.empty();
        }
    }

    /** Which of the known places a link leads to. */
    static Source sourceOf(URI link) {
        String host = link.getHost().toLowerCase(Locale.ROOT);
        if (isOrIsUnder(host, "instagram.com") || isOrIsUnder(host, "instagr.am")) {
            return Source.INSTAGRAM;
        }
        if (isOrIsUnder(host, "tiktok.com")) {
            return Source.TIKTOK;
        }
        if (isOrIsUnder(host, "pin.it") || isPinterest(host)) {
            return Source.PINTEREST;
        }
        return Source.WEB;
    }

    private static boolean isOrIsUnder(String host, String site) {
        return host.equals(site) || host.endsWith("." + site);
    }

    /**
     * Pinterest has an address per country: pinterest.com, pinterest.co.uk, pl.pinterest.com, pinterest.de.
     * The name counts only where a site's own name stands: followed by nothing but a country or .com ending.
     */
    private static boolean isPinterest(String host) {
        String[] labels = host.split("\\.");
        for (int at = 0; at < labels.length - 1; at++) {
            if (labels[at].equals("pinterest")) {
                int after = labels.length - 1 - at;
                boolean ending = after == 1 || (after == 2 && labels[at + 1].length() <= 3 && labels[at + 2].length() == 2);
                if (ending) {
                    return true;
                }
            }
        }
        return false;
    }
}
