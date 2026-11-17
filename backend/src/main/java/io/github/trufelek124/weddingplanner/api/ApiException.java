package io.github.trufelek124.weddingplanner.api;

import java.util.List;

/** Thrown by any module to answer a request with an error of a known kind. */
public class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final List<FieldProblem> fields;

    /**
     * @param detail what went wrong, for the person using the client; it is sent to the client,
     *     so it never holds secrets or anything about other people's data
     */
    public ApiException(ProblemCode code, String detail) {
        this(code, detail, List.of());
    }

    /** An error about particular fields of the request. */
    public ApiException(ProblemCode code, String detail, List<FieldProblem> fields) {
        super(detail);
        this.code = code;
        this.fields = List.copyOf(fields);
    }

    /** A request whose one field is not acceptable. */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", List.of(new FieldProblem(field, message)));
    }

    public ProblemCode code() {
        return code;
    }

    public List<FieldProblem> fields() {
        return fields;
    }
}
