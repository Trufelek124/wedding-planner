package io.github.trufelek124.weddingplanner.api.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place where an error becomes a response. Every error has the same shape
 * (RFC 9457 problem details) and a {@code type} taken from {@link ProblemCode}.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> known(ApiException exception, WebRequest request) {
        ProblemCode code = exception.code();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), exception.getMessage());
        return handleExceptionInternal(exception, body, new HttpHeaders(), code.status(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        log.error("Unhandled exception while answering a request", exception);
        ProblemCode code = ProblemCode.INTERNAL_ERROR;
        return handleExceptionInternal(
                exception, ProblemDetail.forStatus(code.status()), new HttpHeaders(), code.status(), request);
    }

    /** Every error passes through here, including the ones the web framework raises itself. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemCode code = exception instanceof ApiException known ? known.code() : ProblemCode.forStatus(status);
        ProblemDetail problem = body instanceof ProblemDetail given ? given : ProblemDetail.forStatus(status);
        problem.setType(code.type());
        problem.setTitle(code.title());
        if (status.is5xxServerError()) {
            // What broke inside the server is for the log, never for the client.
            problem.setDetail(null);
        }
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }
}
