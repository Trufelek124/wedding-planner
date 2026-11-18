package io.github.trufelek124.weddingplanner.inspirations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.inspirations.internal.Destinations.Refused;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Where the server may go for a preview: the public internet and nothing else (decision D-003). */
class PublicDestinationsTest {

    private final PublicDestinations destinations = new PublicDestinations();

    // ------------------------------------------------------------ which addresses are public

    @ParameterizedTest
    @ValueSource(strings = {
        "1.1.1.1", "8.8.8.8", "93.184.215.14", "151.101.1.140", "172.15.255.255", "172.32.0.1", "100.63.255.255",
        "100.128.0.1", "192.0.1.1", "192.167.255.255", "198.17.255.255", "198.20.0.1", "223.255.255.254",
        "2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2001:4860:4860::8888",
    })
    void anAddressOfThePublicInternetIsAllowed(String address) throws Exception {
        assertThat(PublicDestinations.isPublic(InetAddress.getByName(address))).as(address).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, this machine",
        "127.255.255.254, this machine",
        "0.0.0.0, this network: reaches the machine itself",
        "0.1.2.3, this network",
        "10.0.0.1, private",
        "10.255.255.255, private",
        "172.16.0.1, private: where containers of one host talk",
        "172.31.255.255, private",
        "192.168.0.1, private",
        "192.168.255.255, private",
        "169.254.169.254, link-local: the metadata service of cloud hosts",
        "169.254.0.1, link-local",
        "100.64.0.1, shared address space of providers",
        "100.127.255.255, shared address space of providers",
        "192.0.0.1, protocol assignments",
        "192.0.2.1, documentation",
        "198.51.100.1, documentation",
        "203.0.113.1, documentation",
        "192.88.99.1, former 6to4 relays",
        "198.18.0.1, benchmarking",
        "198.19.255.255, benchmarking",
        "224.0.0.1, multicast",
        "239.255.255.255, multicast",
        "240.0.0.1, reserved",
        "255.255.255.255, broadcast",
        "::1, this machine",
        "::, unspecified",
        "::ffff:127.0.0.1, this machine written as IPv6",
        "::ffff:10.0.0.1, private written as IPv6",
        "::ffff:169.254.169.254, metadata service written as IPv6",
        "::127.0.0.1, IPv4-compatible",
        "64:ff9b::7f00:1, translated IPv4",
        "64:ff9b::a00:1, translated IPv4",
        "fc00::1, unique local",
        "fd12:3456:789a::1, unique local",
        "fe80::1, link-local",
        "fec0::1, site-local",
        "ff02::1, multicast",
        "2001:db8::1, documentation",
        "2001:0:4136:e378:8000:63bf:3fff:fdd2, Teredo: an IPv4 address inside",
        "2002:7f00:1::1, 6to4: 127.0.0.1 inside",
        "2002:a00:1::1, 6to4: 10.0.0.1 inside",
        "100::1, discard",
        "4000::1, not handed out",
    })
    void anythingElseIsNot(String address, String what) throws Exception {
        assertThat(PublicDestinations.isPublic(InetAddress.getByName(address))).as(address + " (" + what + ")").isFalse();
    }

    // ------------------------------------------------------------ what a name stands for

    @Test
    void aNameIsRefusedAsAWholeWhenOneOfItsAddressesIsNotPublic() throws Exception {
        InetAddress open = InetAddress.getByName("1.1.1.1");
        InetAddress inside = InetAddress.getByName("10.0.0.5");

        assertThatCode(() -> destinations.checkResolved("example.org", new InetAddress[] {open})).doesNotThrowAnyException();
        assertThatThrownBy(() -> destinations.checkResolved("rebinding.example", new InetAddress[] {open, inside})).isInstanceOf(Refused.class);
        assertThatThrownBy(() -> destinations.checkResolved("rebinding.example", new InetAddress[] {inside, open})).isInstanceOf(Refused.class);
        assertThatThrownBy(() -> destinations.checkResolved("nothing.example", new InetAddress[0])).isInstanceOf(Refused.class);
    }

    // ------------------------------------------------------------ which addresses are asked at all

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.instagram.com/p/abc/", "http://example.com/", "https://example.com:443/a?b=c", "http://example.com:80/",
        "HTTPS://EXAMPLE.COM/",
    })
    void plainWebAddressesAreAsked(String address) {
        assertThatCode(() -> destinations.checkAddress(URI.create(address))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
        "http://example.com:8080/, another port: an admin page or a database's web console",
        "https://example.com:5432/, another port",
        "http://example.com:443/, the other scheme's port",
        "https://example.com:80/, the other scheme's port",
        "ftp://example.com/file, not the web",
        "file:///etc/passwd, a file on the server",
        "gopher://example.com/, not the web",
        "jar:http://example.com/a.jar!/, not the web",
        "http://user:secret@example.com/, a user name in the address",
        "http://user@example.com/, a user name in the address",
        "http:///no-host, no host",
        "/just/a/path, no scheme",
    })
    void othersAreNot(String address, String what) {
        assertThatThrownBy(() -> destinations.checkAddress(URI.create(address))).as(what).isInstanceOf(Refused.class);
    }
}
