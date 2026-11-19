package io.github.trufelek124.weddingplanner.mailreading.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactDetailsTest {

    @Test
    void aWebsiteWithHttpOrHttpsIsKeptAsItIs() {
        assertThat(ContactDetails.website("https://www.palace.example/offer")).isEqualTo("https://www.palace.example/offer");
        assertThat(ContactDetails.website("http://palace.example")).isEqualTo("http://palace.example");
        assertThat(ContactDetails.website("HTTPS://Palace.example")).isEqualTo("HTTPS://Palace.example");
    }

    @Test
    void aHostWithoutASchemeGetsHttpsInFront() {
        assertThat(ContactDetails.website("www.palace.example")).isEqualTo("https://www.palace.example");
        assertThat(ContactDetails.website("palace.example/offer")).isEqualTo("https://palace.example/offer");
        assertThat(ContactDetails.website("  palace.example ")).isEqualTo("https://palace.example");
    }

    @Test
    void anythingThatIsNotAnHttpAddressIsLeftOut() {
        for (String not : new String[] {
            "javascript:alert(1)", "JavaScript:alert(1)", "ftp://files.palace.example/x", "mailto:jan@palace.example",
            "data:text/html,<b>x</b>", "file:///etc/passwd", "//palace.example", "https://", "http://user:pw@palace.example",
            "call us", "palace", "palace.example is ours", "palace.example\\evil", "https://pal ace.example", "javascript:palace.example",
            "", "   "}) {
            assertThat(ContactDetails.website(not)).as(not).isNull();
        }
        assertThat(ContactDetails.website(null)).isNull();
    }

    @Test
    void anAddressIsKeptOnlyWhenItHasTheFormOfOne() {
        assertThat(ContactDetails.email("jan@palace-on-the-hill.example")).isEqualTo("jan@palace-on-the-hill.example");
        assertThat(ContactDetails.email("jan.k+offers@palace.example")).isEqualTo("jan.k+offers@palace.example");
        for (String not : new String[] {"not an address", "jan@", "@palace.example", "jan@palace", "jan palace@x.example", "<jan@palace.example>",
            "jan@palace.example, anna@palace.example", "a".repeat(250) + "@palace.example", ""}) {
            assertThat(ContactDetails.email(not)).as(not).isNull();
        }
        assertThat(ContactDetails.email(null)).isNull();
    }

    @Test
    void aPhoneNumberIsKeptOnlyUpToFortyCharacters() {
        assertThat(ContactDetails.phone("+48 600 100 200")).isEqualTo("+48 600 100 200");
        assertThat(ContactDetails.phone("1".repeat(40))).hasSize(40);
        assertThat(ContactDetails.phone("1".repeat(41))).isNull();
        assertThat(ContactDetails.phone("  ")).isNull();
        assertThat(ContactDetails.phone(null)).isNull();
    }
}
