package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.net.InetAddress;
import java.net.URI;

/**
 * The real rules, with one door opened for tests: the stand-in server on this machine, on its port.
 * Everything else is judged exactly as in production, so a test can still be refused.
 */
public final class StandInDestinations implements Destinations {

    private final Destinations real = new PublicDestinations();
    private final int standInPort;

    public StandInDestinations(int standInPort) {
        this.standInPort = standInPort;
    }

    @Override
    public void checkAddress(URI address) throws Refused {
        if (address.getPort() == standInPort && "http".equals(address.getScheme()) && address.getRawUserInfo() == null) {
            return;
        }
        real.checkAddress(address);
    }

    @Override
    public void checkResolved(String host, InetAddress[] addresses) throws Refused {
        if (addresses.length == 1 && addresses[0].isLoopbackAddress() && host.equals("127.0.0.1")) {
            return;
        }
        real.checkResolved(host, addresses);
    }
}
