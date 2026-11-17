package io.github.trufelek124.weddingplanner.identity.internal;

import io.github.trufelek124.weddingplanner.api.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests that the security filter turns away never reach a controller, so the error handler of
 * the api module does not see them. This writes the same Problem shape for them.
 */
@Component
class SecurityProblems {

    private final JsonMapper json;

    SecurityProblems(JsonMapper json) {
        this.json = json;
    }

    void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(ProblemCode.UNAUTHENTICATED, request, response);
    }

    void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(ProblemCode.FORBIDDEN, request, response);
    }

    private void write(ProblemCode code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", code.type().toString());
        problem.put("title", code.title());
        problem.put("status", code.status().value());
        problem.put("instance", request.getRequestURI());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(problem));
    }
}
