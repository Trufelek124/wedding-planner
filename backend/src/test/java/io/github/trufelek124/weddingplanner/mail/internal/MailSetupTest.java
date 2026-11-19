package io.github.trufelek124.weddingplanner.mail.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Parsed;
import java.time.Duration;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The import is on only when all four settings are there and valid; each missing one is named, and no value is. */
@ExtendWith(OutputCaptureExtension.class)
class MailSetupTest {

    private static final String SECRET = "test-client-secret-not-real-0001";
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static MailProperties complete() {
        return new MailProperties(
                "test-client.apps.googleusercontent.com",
                SECRET,
                "https://planner.example",
                KEY,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://oauth2.googleapis.com/revoke",
                "https://gmail.googleapis.com",
                Duration.ofSeconds(10));
    }

    private static MailProperties changed(UnaryOperator<MailProperties> change) {
        return change.apply(complete());
    }

    private static MailProperties with(String clientId, String secret, String publicUrl, String key) {
        MailProperties c = complete();
        return new MailProperties(clientId, secret, publicUrl, key, c.authorizationUri(), c.tokenUri(), c.revokeUri(), c.gmailUri(), c.googleTimeout());
    }

    @Test
    void withEverythingItIsSetUp() {
        Parsed parsed = MailSetup.parse(complete());

        assertThat(parsed.google()).isNotNull();
        assertThat(parsed.missing()).isEmpty();
        assertThat(parsed.invalid()).isEmpty();
        assertThat(parsed.google().returnAddress().toString()).isEqualTo("https://planner.example/api/mail/gmail/return");
        assertThat(parsed.google().page(MailSetup.Outcome.YES).toString()).isEqualTo("https://planner.example/mail?connected=yes");
    }

    @Test
    void eachOfTheFourMissingInTurnSwitchesItOffAndIsNamed() {
        MailProperties c = complete();

        assertThat(MailSetup.parse(with("", c.googleClientSecret(), c.publicUrl(), c.tokenKey())).missing())
                .containsExactly("GOOGLE_OAUTH_WEB_CLIENT_ID");
        assertThat(MailSetup.parse(with(c.googleClientId(), "", c.publicUrl(), c.tokenKey())).missing())
                .containsExactly("GOOGLE_OAUTH_CLIENT_SECRET");
        assertThat(MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), "", c.tokenKey())).missing())
                .containsExactly("APP_PUBLIC_URL");
        assertThat(MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), c.publicUrl(), "")).missing())
                .containsExactly("APP_MAIL_TOKEN_KEY");
        assertThat(MailSetup.parse(with(null, null, null, null)).missing()).hasSize(4);
        assertThat(MailSetup.parse(with("  ", "  ", "  ", "  ")).missing()).hasSize(4);
        for (MailProperties off : new MailProperties[] {
            with("", c.googleClientSecret(), c.publicUrl(), c.tokenKey()),
            with(c.googleClientId(), "", c.publicUrl(), c.tokenKey()),
            with(c.googleClientId(), c.googleClientSecret(), "", c.tokenKey()),
            with(c.googleClientId(), c.googleClientSecret(), c.publicUrl(), "")}) {
            assertThat(MailSetup.parse(off).google()).isNull();
        }
    }

    @Test
    void aKeyThatIsNot32BytesCountsAsNoKey() {
        String bytes31 = java.util.Base64.getEncoder().encodeToString(new byte[31]);
        String bytes33 = java.util.Base64.getEncoder().encodeToString(new byte[33]);
        String bytes16 = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        MailProperties c = complete();

        for (String bad : new String[] {bytes31, bytes33, bytes16, "not base64 at all!", "0123456789abcdef0123456789abcdef", "====", "MDEy"}) {
            Parsed parsed = MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), c.publicUrl(), bad));
            assertThat(parsed.google()).as(bad).isNull();
            assertThat(parsed.invalid()).as(bad).containsExactly("APP_MAIL_TOKEN_KEY");
        }
        assertThat(MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), c.publicUrl(),
                java.util.Base64.getEncoder().encodeToString(new byte[32]))).google()).isNotNull();
    }

    @Test
    void aPublicAddressThatIsNotHttpsIsInvalidExceptOnTheDevelopersMachine() {
        MailProperties c = complete();
        for (String bad : new String[] {
            "http://planner.example", "ftp://planner.example", "planner.example", "//planner.example", "https://", "javascript:alert(1)",
            "https://user:pw@planner.example", "https://planner.example/?x=1", "https://planner.example/#top", "http://localhost.example",
            "http://127.0.0.1:8080", "https://planner example"}) {
            Parsed parsed = MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), bad, c.tokenKey()));
            assertThat(parsed.google()).as(bad).isNull();
            assertThat(parsed.invalid()).as(bad).containsExactly("APP_PUBLIC_URL");
        }
        for (String good : new String[] {"https://planner.example", "https://planner.example/", "http://localhost", "http://localhost:8080", "https://planner.example/app"}) {
            assertThat(MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), good, c.tokenKey())).google()).as(good).isNotNull();
        }
        assertThat(MailSetup.parse(with(c.googleClientId(), c.googleClientSecret(), "https://planner.example/", c.tokenKey()))
                        .google().returnAddress().toString())
                .as("no double slash").isEqualTo("https://planner.example/api/mail/gmail/return");
    }

    @Test
    void googlesAddressesAreCheckedLikeTheOtherAddresses() {
        MailProperties c = complete();

        assertThat(MailSetup.parse(changed(p -> new MailProperties(p.googleClientId(), p.googleClientSecret(), p.publicUrl(), p.tokenKey(),
                "http://accounts.google.example/auth", p.tokenUri(), p.revokeUri(), p.gmailUri(), p.googleTimeout()))).invalid())
                .containsExactly("app.mail.authorization-uri");
        assertThat(MailSetup.parse(changed(p -> new MailProperties(p.googleClientId(), p.googleClientSecret(), p.publicUrl(), p.tokenKey(),
                p.authorizationUri(), p.tokenUri(), p.revokeUri(), "file:///etc/passwd", p.googleTimeout()))).invalid())
                .containsExactly("app.mail.gmail-uri");
        assertThat(MailSetup.parse(changed(p -> new MailProperties(p.googleClientId(), p.googleClientSecret(), p.publicUrl(), p.tokenKey(),
                p.authorizationUri(), p.tokenUri(), p.revokeUri(), p.gmailUri(), Duration.ofMinutes(5)))).invalid())
                .containsExactly("app.mail.google-timeout");
        assertThat(MailSetup.parse(changed(p -> new MailProperties(p.googleClientId(), p.googleClientSecret(), p.publicUrl(), p.tokenKey(),
                p.authorizationUri(), p.tokenUri(), p.revokeUri(), p.gmailUri(), Duration.ZERO))).invalid())
                .containsExactly("app.mail.google-timeout");
        assertThat(c.toString()).as("the settings print nothing of themselves").doesNotContain(SECRET).doesNotContain(KEY);
    }

    @Test
    void whenItIsOffNothingCanBeAskedForAndTheAnswerIsTheProblemThatSaysSo() {
        MailSetup off = new MailSetup(with("", "", "", ""));

        assertThat(off.ready()).isEmpty();
        assertThatThrownBy(off::require)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ProblemCode.MAIL_NOT_SET_UP));
    }

    @Test
    void startingSaysOnceWhichSettingIsMissingByNameAndNeverWhatAnyValueIs(CapturedOutput output) {
        MailProperties c = complete();

        new MailSetup(with(c.googleClientId(), "", c.publicUrl(), c.tokenKey()));

        String log = output.getAll();
        assertThat(log).contains("GOOGLE_OAUTH_CLIENT_SECRET");
        assertThat(log.split("The e-mail import is off", -1)).as("said once").hasSize(2);
        assertThat(log).doesNotContain(KEY).doesNotContain("planner.example").doesNotContain(c.googleClientId());
    }

    @Test
    void anInvalidSettingIsNamedAndItsValueIsNot(CapturedOutput output) {
        MailProperties c = complete();

        new MailSetup(with(c.googleClientId(), c.googleClientSecret(), "http://planner.example", "short-key-value"));

        String log = output.getAll();
        assertThat(log).contains("APP_PUBLIC_URL").contains("APP_MAIL_TOKEN_KEY").doesNotContain("short-key-value").doesNotContain("http://planner.example");
        assertThat(log).doesNotContain(SECRET);
    }

    @Test
    void whenItIsSetUpItSaysSoWithoutAnyValue(CapturedOutput output) {
        new MailSetup(complete());

        assertThat(output.getAll()).contains("The e-mail import is set up").doesNotContain(SECRET).doesNotContain(KEY);
    }

}
