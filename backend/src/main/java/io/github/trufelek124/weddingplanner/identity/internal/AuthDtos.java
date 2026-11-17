package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.identity.internal.SignInService.Session;
import io.github.trufelek124.weddingplanner.identity.internal.UserRepository.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * What the sign-in endpoints take and give. Request and response bodies end up in debug logs
 * through toString(), so every record here that holds an address, a name or a token hides it there.
 */
final class AuthDtos {

    private AuthDtos() {}

    @Schema(name = "AuthConfig", description = "What a client needs to know before anyone has signed in.")
    record AuthConfig(
            @Schema(description = "The Google OAuth client to sign in with; null when Google sign-in is not set up.", nullable = true)
                    String googleClientId,
            @Schema(description = "Whether the development login is available.") boolean devLoginEnabled) {}

    @Schema(name = "GoogleSignInRequest")
    record GoogleSignInRequest(
            @Schema(description = "The ID token Google handed to the client.") @NotBlank @Size(max = 8192) String idToken) {

        @Override
        public String toString() {
            return "GoogleSignInRequest[hidden]";
        }
    }

    @Schema(name = "DevSignInRequest")
    record DevSignInRequest(
            @NotBlank(message = "Enter an e-mail address.")
                    @Email(message = "Enter an e-mail address.")
                    @Size(max = 320, message = "Enter an e-mail address.")
                    String email) {

        @Override
        public String toString() {
            return "DevSignInRequest[hidden]";
        }
    }

    @Schema(name = "RefreshRequest")
    record RefreshRequest(
            @Schema(description = "The refresh token; a browser leaves it out, because its cookie carries it.", nullable = true)
                    @Size(max = 512)
                    String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[hidden]";
        }
    }

    @Schema(name = "Me", description = "The signed-in person.")
    record Me(UUID id, String displayName, String email) {

        static Me of(User user) {
            return new Me(user.id(), user.displayName(), user.email());
        }

        @Override
        public String toString() {
            return "Me[id=" + id + "]";
        }
    }

    @Schema(name = "Session", description = "What a client holds while someone is signed in.")
    record SessionResponse(
            @Schema(description = "Sent with every request as 'Authorization: Bearer ...'.") String accessToken,
            @Schema(description = "When the access token stops working; refresh before then.") Instant accessTokenExpiresAt,
            @Schema(description = "Exchanged for a new session when the access token expires; used once. "
                            + "Null for a browser: there it travels in a cookie that scripts cannot read.",
                    nullable = true)
                    String refreshToken,
            Me user) {

        static SessionResponse of(Session session, boolean refreshTokenInBody) {
            return new SessionResponse(
                    session.accessToken(),
                    session.accessTokenExpiresAt(),
                    refreshTokenInBody ? session.refreshToken() : null,
                    Me.of(session.user()));
        }

        @Override
        public String toString() {
            return "Session[user=" + user.id() + "]";
        }
    }
}
