package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.internal.GoogleIdTokenVerifier.GoogleAccount;
import io.github.trufelek124.weddingplanner.identity.internal.UserRepository.User;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Signing in, staying signed in and signing out. Log lines here name users by id only. */
@Service
class SignInService {

    /** What a client holds after signing in or refreshing. */
    record Session(User user, String accessToken, Instant accessTokenExpiresAt, String refreshToken) {

        @Override
        public String toString() {
            return "Session[user=" + user.id() + "]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final GoogleIdTokenVerifier google;
    private final AllowList allowList;
    private final UserRepository users;
    private final AccessTokens accessTokens;
    private final RefreshTokens refreshTokens;
    private final Clock clock;

    SignInService(
            GoogleIdTokenVerifier google,
            AllowList allowList,
            UserRepository users,
            AccessTokens accessTokens,
            RefreshTokens refreshTokens,
            Clock clock) {
        this.google = google;
        this.allowList = allowList;
        this.users = users;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional
    Session signInWithGoogle(String idToken) {
        GoogleAccount account = google.verify(idToken)
                .orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "The sign-in could not be verified."));
        requireAllowed(account.email());
        String name = displayName(account.name(), account.email());
        User user = users.findByGoogleSubject(account.subject())
                .or(() -> users.findByEmail(account.email()))
                .map(known -> users.linkGoogleAccount(known, account.subject(), account.email(), name))
                .orElseGet(() -> users.create(account.email(), account.subject(), name, clock.instant()));
        log.info("User {} signed in with Google", user.id());
        return newSession(user);
    }

    /** Only reachable while the development login is switched on. */
    @Transactional
    Session signInForDevelopment(String email) {
        requireAllowed(email);
        User user = users.findByEmail(email)
                .orElseGet(() -> users.create(email, null, displayName(null, email), clock.instant()));
        log.warn("User {} signed in through the development login", user.id());
        return newSession(user);
    }

    /**
     * Not one transaction on purpose: when a token is presented a second time the rotation revokes
     * the whole session, and that must stay in the database although this request then fails.
     */
    Session refresh(String refreshToken) {
        RefreshTokens.Rotation rotation = refreshTokens.rotate(refreshToken).orElseThrow(SignInService::signedOut);
        // An address taken off the allow-list loses its sessions at the next refresh.
        User user = users.findById(rotation.userId())
                .filter(found -> allowList.allows(found.email()))
                .orElseThrow(SignInService::signedOut);
        AccessTokens.Issued access = accessTokens.issue(user.id());
        return new Session(user, access.token(), access.expiresAt(), rotation.refreshToken());
    }

    void signOut(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }

    User user(UUID id) {
        return users.findById(id).orElseThrow(SignInService::signedOut);
    }

    private Session newSession(User user) {
        AccessTokens.Issued access = accessTokens.issue(user.id());
        return new Session(user, access.token(), access.expiresAt(), refreshTokens.issue(user.id()));
    }

    private void requireAllowed(String email) {
        if (!allowList.allows(email)) {
            // The address is neither logged nor sent back.
            log.warn("A sign-in was refused: the account is not on the allow-list");
            throw new ApiException(ProblemCode.ACCOUNT_NOT_ALLOWED, "This account may not use this planner.");
        }
    }

    private static ApiException signedOut() {
        return new ApiException(ProblemCode.UNAUTHENTICATED, "You are signed out. Sign in again.");
    }

    private static String displayName(String name, String email) {
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        return email.substring(0, Math.max(email.indexOf('@'), 0)).strip();
    }
}
