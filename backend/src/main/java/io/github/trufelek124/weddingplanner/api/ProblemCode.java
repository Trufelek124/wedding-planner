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
    /** Nobody has set the wedding up yet: show the setup. */
    WEDDING_NOT_SET_UP(HttpStatus.NOT_FOUND, "wedding-not-set-up", "The wedding is not set up yet"),
    /** The setup was sent a second time. */
    WEDDING_ALREADY_SET_UP(HttpStatus.CONFLICT, "wedding-already-set-up", "The wedding is already set up"),
    /** A third person signed in: a wedding has two members. */
    WEDDING_FULL(HttpStatus.FORBIDDEN, "wedding-full", "The wedding already has its two members"),
    /** Someone else saved the same thing in the meantime: load it again before changing it. */
    CHANGED_MEANWHILE(HttpStatus.CONFLICT, "changed-meanwhile", "Someone else changed this in the meantime"),
    /** A plan item that still has tasks: say what happens to them (keep or delete). */
    ENTRY_HAS_TASKS(HttpStatus.CONFLICT, "entry-has-tasks", "This plan item still has tasks"),
    /** The link would make plan items wait for each other in a circle. */
    DEPENDENCY_CYCLE(HttpStatus.CONFLICT, "dependency-cycle", "These plan items would wait for each other"),
    /** A board that still has inspirations: say what happens to them (keep or delete). */
    BOARD_HAS_INSPIRATIONS(HttpStatus.CONFLICT, "board-has-inspirations", "This board still has inspirations"),
    /** A vendor that plan items, tasks or inspirations point to: say that they may lose it. */
    VENDOR_IN_USE(HttpStatus.CONFLICT, "vendor-in-use", "Something still points to this vendor"),
    /** A suggestion was decided the other way first: show what was decided, and by whom. */
    ALREADY_DECIDED(HttpStatus.CONFLICT, "already-decided", "This suggestion has already been decided"),
    /** What was sent is larger than the server takes; a picture, for instance. */
    UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "upload-too-large", "That is too large to upload"),
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
            case 413 -> UPLOAD_TOO_LARGE;
            default -> BAD_REQUEST;
        };
    }
}
