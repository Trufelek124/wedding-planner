package io.github.trufelek124.weddingplanner.api;

/** Thrown by any module to answer a request with an error of a known kind. */
public class ApiException extends RuntimeException {

    private final ProblemCode code;

    /**
     * @param detail what went wrong, for the person using the client; it is sent to the client,
     *     so it never holds secrets or anything about other people's data
     */
    public ApiException(ProblemCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ProblemCode code() {
        return code;
    }
}
