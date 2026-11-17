package io.github.trufelek124.weddingplanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

/** Signs a person in for a test, through the real endpoint with a token from {@link TestGoogle}. */
public final class TestSignIn {

    public record Tokens(String accessToken, String refreshToken, String userId) {}

    private TestSignIn() {}

    /** Signs in the way the phone app does: the refresh token comes back in the answer. */
    public static Tokens signIn(TestHttp http, String email) throws Exception {
        HttpResponse<String> response = withGoogle(http, TestGoogle.idToken(email).build(), Map.of());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return tokens(response);
    }

    public static HttpResponse<String> withGoogle(TestHttp http, String idToken, Map<String, String> headers)
            throws Exception {
        return http.postJson("/api/auth/google", TestHttp.jsonOf(Map.of("idToken", idToken)), headers);
    }

    @SuppressWarnings("unchecked")
    public static Tokens tokens(HttpResponse<String> response) {
        Map<String, Object> body = TestHttp.json(response);
        Map<String, Object> user = (Map<String, Object>) body.get("user");
        return new Tokens((String) body.get("accessToken"), (String) body.get("refreshToken"), (String) user.get("id"));
    }
}
