package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ConnectCookieTest {

    @Test
    void anHttpsAddressMakesItSecureWhateverTheHopBeforeTheServerSaw() {
        MockHttpServletRequest plain = new MockHttpServletRequest();

        String header = ConnectCookie.set(plain, true, "value-of-a-test");

        assertThat(header).contains("Secure").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api/mail/gmail/return").contains("Max-Age=600");
    }

    @Test
    void aSecureRequestMakesItSecureToo() {
        MockHttpServletRequest secure = new MockHttpServletRequest();
        secure.setSecure(true);

        assertThat(ConnectCookie.set(secure, false, "value-of-a-test")).contains("Secure");
    }

    @Test
    void onlyADevelopersMachineOverPlainHttpGetsItWithoutSecure() {
        assertThat(ConnectCookie.set(new MockHttpServletRequest(), false, "value-of-a-test")).doesNotContain("Secure").contains("HttpOnly");
    }

    @Test
    void clearingUsesTheSameNamePathAndAttributes() {
        String header = ConnectCookie.clear(new MockHttpServletRequest(), true);

        assertThat(header).startsWith("wp_mail_connect=;").contains("Max-Age=0").contains("Path=/api/mail/gmail/return").contains("Secure").contains("HttpOnly");
    }

    @Test
    void theValueIsReadOnlyWhenThereIsExactlyOne() {
        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest one = new MockHttpServletRequest();
        one.setCookies(new Cookie("wp_mail_connect", "one"), new Cookie("other", "x"));
        MockHttpServletRequest two = new MockHttpServletRequest();
        two.setCookies(new Cookie("wp_mail_connect", "one"), new Cookie("wp_mail_connect", "two"));

        assertThat(ConnectCookie.valueIn(none)).isEmpty();
        assertThat(ConnectCookie.valueIn(one)).contains("one");
        assertThat(ConnectCookie.valueIn(two)).isEmpty();
    }
}
