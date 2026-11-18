package io.github.trufelek124.weddingplanner.inspirations.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** The public internet and nothing else: the rules of decision D-003. */
@Component
class PublicDestinations implements Destinations {

    @Override
    public void checkAddress(URI address) throws Refused {
        String scheme = address.getScheme() == null ? "" : address.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new Refused("not http or https");
        }
        if (address.getHost() == null || address.getHost().isBlank()) {
            throw new Refused("no host");
        }
        if (address.getRawUserInfo() != null) {
            throw new Refused("a user name in the address");
        }
        int usual = scheme.equals("https") ? 443 : 80;
        if (address.getPort() != -1 && address.getPort() != usual) {
            throw new Refused("an unusual port");
        }
    }

    @Override
    public void checkResolved(String host, InetAddress[] addresses) throws Refused {
        if (addresses == null || addresses.length == 0) {
            throw new Refused("the name stands for no address");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                // One private answer among public ones is refused as a whole: which one the connection
                // would pick is not ours to rely on.
                throw new Refused("the name stands for an address that is not public");
            }
        }
    }

    /** Whether an address belongs to the public internet. Everything not known to be public is not. */
    static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(bytes);
        }
        if (address instanceof Inet6Address) {
            return isPublicV6(bytes);
        }
        return false;
    }

    private static boolean isPublicV4(byte[] a) {
        int first = a[0] & 0xFF;
        int second = a[1] & 0xFF;
        int third = a[2] & 0xFF;
        boolean special =
                first == 0 // "this network"
                        || first == 10 // private
                        || (first == 100 && second >= 64 && second <= 127) // shared address space of providers
                        || first == 127 // this machine
                        || (first == 169 && second == 254) // link-local; cloud metadata services live here
                        || (first == 172 && second >= 16 && second <= 31) // private
                        || (first == 192 && second == 0 && third == 0) // protocol assignments
                        || (first == 192 && second == 0 && third == 2) // documentation
                        || (first == 192 && second == 88 && third == 99) // former 6to4 relays
                        || (first == 192 && second == 168) // private
                        || (first == 198 && (second == 18 || second == 19)) // benchmarking
                        || (first == 198 && second == 51 && third == 100) // documentation
                        || (first == 203 && second == 0 && third == 113) // documentation
                        || first >= 224; // multicast, reserved, broadcast
        return !special;
    }

    private static boolean isPublicV6(byte[] a) {
        int first = a[0] & 0xFF;
        int second = a[1] & 0xFF;
        // Only the block the public internet is handed out from, 2000::/3 ...
        if ((first & 0xE0) != 0x20) {
            return false; // ::, ::1, v4-mapped and v4-compatible, unique local fc00::/7, link-local fe80::/10, multicast ff00::/8
        }
        // ... and of that, not the parts that stand for something else.
        boolean special =
                (first == 0x20 && second == 0x01 && a[2] == 0x00 && a[3] == 0x00) // 2001:0::/32 Teredo: an IPv4 address inside
                        || (first == 0x20 && second == 0x01 && a[2] == 0x0D && (a[3] & 0xFF) == 0xB8) // 2001:db8::/32 documentation
                        || (first == 0x20 && second == 0x02); // 2002::/16 6to4: an IPv4 address inside
        return !special;
    }
}
