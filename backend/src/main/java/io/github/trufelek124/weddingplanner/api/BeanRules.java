package io.github.trufelek124.weddingplanner.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;

/**
 * Holds a request that was not made through HTTP to the rules its type declares, with the messages the same
 * rules give a person who sent it through HTTP: for a module that makes what another module's "add" makes.
 */
public final class BeanRules {

    private BeanRules() {}

    /** @throws ApiException BAD_REQUEST with a message per field when the request breaks a declared rule */
    public static void require(Validator validator, Object request) {
        List<FieldProblem> problems = validator.validate(request).stream()
                .map((ConstraintViolation<Object> violation) ->
                        new FieldProblem(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::message))
                .toList();
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The request is not valid.", problems);
        }
    }
}
