package io.github.trufelek124.weddingplanner.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Every kind of error the API answers with. Clients decide what to do from {@link #type()},
 * never from the wording, so a code is never renamed or reused once clients know it.
 * Adding a code changes the API description: regenerate it and the clients.
 */
public enum ProblemCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "bad-request", "The request is not valid"),
    /** Nobody is signed in, or the sign-in is no longer valid: sign in (again). */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign-in required"),
    /** Signed in, but not allowed to do this. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "Not allowed"),
    /** The account is genuine but is not one of the planner's owners. */
    ACCOUNT_NOT_ALLOWED(HttpStatus.FORBIDDEN, "account-not-allowed", "This account may not use this planner"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Something went wrong on the server");

    private static final String PREFIX = "urn:wedding-planner:problem:";

    private final HttpStatus status;
    private final URI type;
    private final String title;

    ProblemCode(HttpStatus status, String slug, String title) {
        this.status = status;
        this.type = URI.create(PREFIX + slug);
        this.title = title;
    }

    /** The HTTP status an error of this kind is answered with unless the framework chose one. */
    public HttpStatus status() {
        return status;
    }

    /** The stable identifier clients switch on. */
    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    /** The code for an error the web framework raised itself, which is only known by its status. */
    public static ProblemCode forStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            default -> BAD_REQUEST;
        };
    }
}
