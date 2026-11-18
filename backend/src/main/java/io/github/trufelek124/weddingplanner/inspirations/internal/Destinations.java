package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.net.InetAddress;
import java.net.URI;

/**
 * Where the server may go when it fetches something on a person's behalf. A saved link is an address
 * someone else chose; without these rules it could name the database next door, the host's
 * metadata service or anything else that only the server's own network can reach.
 */
interface Destinations {

    /**
     * Checks an address before anything is asked of it: the first one, and every one a redirect leads to.
     *
     * @throws Refused unless it is plain web: http or https, on the usual port, with no user name in it
     */
    void checkAddress(URI address) throws Refused;

    /**
     * Checks what a host name stands for. Called by the HTTP client at the moment of connecting, with the
     * very addresses it will connect to, so that a name cannot be looked up twice with two answers.
     *
     * @throws Refused unless every one of the addresses is a public one
     */
    void checkResolved(String host, InetAddress[] addresses) throws Refused;

    /** The server does not go there. The message is for the log; it is never sent to a client. */
    final class Refused extends Exception {
        Refused(String why) {
            super(why);
        }
    }
}
