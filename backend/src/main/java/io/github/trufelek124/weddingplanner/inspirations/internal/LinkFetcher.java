package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.Destinations.Refused;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fetches what a link points at, for a preview, and nothing more than that:
 * only where {@link Destinations} allows, checked again at every redirect and at the moment of
 * connecting; within limits of time and size; sending nothing of the people the planner belongs to.
 */
@Component
class LinkFetcher implements DisposableBean {

    static final int MOST_REDIRECTS = 3;
    /** How long to wait for a connection, for the next bytes, and for one fetch as a whole. */
    private static final Duration CONNECT = Duration.ofSeconds(3);
    private static final Duration SILENCE = Duration.ofSeconds(5);
    private static final Duration WHOLE_FETCH = Duration.ofSeconds(12);
    /** Says what it is and names no one. */
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; link-preview)";

    private final Destinations destinations;
    private final CloseableHttpClient client;
    private final Duration wholeFetch;

    @Autowired
    LinkFetcher(Destinations destinations) {
        this(destinations, CONNECT, SILENCE, WHOLE_FETCH);
    }

    /** With patience of one's own choosing; tests do not wait as long as the real thing. */
    LinkFetcher(Destinations destinations, Duration connect, Duration silence, Duration wholeFetch) {
        this.destinations = destinations;
        this.wholeFetch = wholeFetch;
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new CheckedResolver(destinations))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(connect))
                                .setSocketTimeout(Timeout.of(silence))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(connect))
                        .setResponseTimeout(Timeout.of(silence))
                        .build())
                // A redirect is a new address and is checked like one; see get().
                .disableRedirectHandling()
                // Nothing is remembered between fetches and nothing is tried twice behind our back.
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .setUserAgent(USER_AGENT)
                .build();
    }

    /**
     * What was fetched.
     *
     * @param from the address that finally answered, after redirects
     * @param contentType as the server gave it, in lower case and without parameters; empty when it gave none
     * @param charset from the content type; null when it named none
     */
    record Fetched(URI from, String contentType, String charset, byte[] body) {}

    /** The answer was not usable: the wrong status, too large, too slow. Whether trying again may help. */
    static final class Unusable extends Exception {
        private final boolean worthAnotherTry;

        Unusable(String why, boolean worthAnotherTry) {
            super(why);
            this.worthAnotherTry = worthAnotherTry;
        }

        boolean worthAnotherTry() {
            return worthAnotherTry;
        }
    }

    /**
     * @param accept what to ask for, as an Accept header
     * @param mostBytes the body is given up on when it is longer than this
     * @throws Refused when the address, or one it redirects to, is not one the server goes to
     * @throws Unusable when no usable answer came
     */
    Fetched get(URI address, String accept, int mostBytes) throws Refused, Unusable {
        long deadline = System.nanoTime() + wholeFetch.toNanos();
        URI current = address;
        for (int hop = 0; ; hop++) {
            destinations.checkAddress(current);
            Answer answer = ask(current, accept, mostBytes, deadline);
            if (answer.redirectTo() == null) {
                return answer.fetched();
            }
            if (hop == MOST_REDIRECTS) {
                throw new Unusable("too many redirects", false);
            }
            current = answer.redirectTo();
        }
    }

    private record Answer(Fetched fetched, URI redirectTo) {}

    private Answer ask(URI address, String accept, int mostBytes, long deadline) throws Refused, Unusable {
        HttpGet request = new HttpGet(address);
        request.setHeader("Accept", accept);
        try {
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    Header location = response.getFirstHeader("Location");
                    if (location == null) {
                        throw new Carried(new Unusable("a redirect to nowhere", false));
                    }
                    try {
                        return new Answer(null, address.resolve(new URI(location.getValue().strip())));
                    } catch (URISyntaxException | IllegalArgumentException e) {
                        throw new Carried(new Unusable("a redirect to no address", false));
                    }
                }
                if (status < 200 || status >= 300) {
                    // The server being unwell may pass; "not found" and "not for you" will not.
                    throw new Carried(new Unusable("status " + status, status >= 500 || status == 429));
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new Carried(new Unusable("an empty answer", false));
                }
                String type = entity.getContentType() == null ? "" : entity.getContentType();
                byte[] body = read(entity.getContent(), mostBytes, deadline);
                return new Answer(new Fetched(address, mediaType(type), charsetOf(type), body), null);
            });
        } catch (Carried carried) {
            if (carried.getCause() instanceof Refused refused) {
                throw refused;
            }
            throw (Unusable) carried.getCause();
        } catch (IOException e) {
            // The resolver's refusal arrives as the cause of an "unknown host".
            if (e.getCause() instanceof Refused refused) {
                throw refused;
            }
            // No answer, a broken one, a slow one: the network's doing, and possibly different next time.
            throw new Unusable(e.getClass().getSimpleName(), true);
        }
    }

    /** Reads the body, and gives up as soon as it is larger or slower than allowed. */
    private static byte[] read(InputStream body, int mostBytes, long deadline) throws IOException {
        try (body) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            for (int read; (read = body.read(chunk)) != -1; ) {
                if (bytes.size() + read > mostBytes) {
                    throw new Carried(new Unusable("larger than " + mostBytes + " bytes", false));
                }
                if (System.nanoTime() > deadline) {
                    throw new Carried(new Unusable("too slow", true));
                }
                bytes.write(chunk, 0, read);
            }
            return bytes.toByteArray();
        }
    }

    private static String mediaType(String contentType) {
        int end = contentType.indexOf(';');
        return (end < 0 ? contentType : contentType.substring(0, end)).strip().toLowerCase(Locale.ROOT);
    }

    private static String charsetOf(String contentType) {
        for (String part : contentType.split(";")) {
            String trimmed = part.strip();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = trimmed.substring("charset=".length()).replace("\"", "").strip();
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }

    /** Lets our own refusals travel through the client's callback, which only passes IOExceptions on. */
    private static final class Carried extends IOException {
        Carried(Exception cause) {
            super(cause);
        }
    }

    /**
     * Stands between the client and the name service: every address a name stands for is checked,
     * and the client connects to exactly what was checked.
     */
    private static final class CheckedResolver implements DnsResolver {

        private final Destinations destinations;

        CheckedResolver(Destinations destinations) {
            this.destinations = destinations;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
            try {
                destinations.checkResolved(host, addresses);
            } catch (Refused refused) {
                UnknownHostException refusal = new UnknownHostException("refused");
                refusal.initCause(refused);
                throw refusal;
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
        }
    }
}
